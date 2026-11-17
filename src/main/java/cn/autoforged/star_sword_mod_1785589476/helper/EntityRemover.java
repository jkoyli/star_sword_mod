package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.mixin.PersistentEntitySectionManagerAccessor;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTickList;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

/**
 * 移除模式的实体摘除实现：绕过 {@code Entity.remove(RemovalReason)} 直接操作实体存储。
 *
 * <p>其他模组（以及本模组自己的 {@code EntityMixin}）可能拦截 {@code Entity.remove(KILLED)}，
 * 这里改为手工完成原版 {@code PersistentEntitySectionManager.Callback.onRemove} 的清理工作：
 * <ol>
 *   <li>{@code EntityLookup.remove(entity)}（byUuid + byId）；</li>
 *   <li>{@code EntitySection.remove(entity)}（所属 section）；</li>
 *   <li>{@code EntityTickList.remove(entity)}（活跃 tick 列表，防止摘除后仍被 tick）；</li>
 *   <li>直写 {@code removalReason = KILLED}（不调 {@code setRemoved}，避免触发 onRemove 回调被拦截）；</li>
 *   <li>断开 levelCallback 并清理 {@code knownUuids}；</li>
 *   <li>补发 {@code ClientboundRemoveEntitiesPacket}，否则客户端会留下幽灵实体。</li>
 * </ol>
 *
 * <p>玩家不适用（走 PlayerList + ServerGamePacketListenerImpl，直接摘 EntityLookup 会导致
 * 玩家列表与实体不一致），对玩家回退到正常的 {@code remove(KILLED)}。
 */
public final class EntityRemover {

    private static volatile Field entityManagerField;
    private static volatile boolean entityManagerResolved;
    private static volatile Field entityTickListField;
    private static volatile boolean entityTickListResolved;
    private static volatile Field removalReasonField;
    private static volatile boolean removalReasonResolved;

    private EntityRemover() {
    }

    public static void hardRemove(LivingEntity entity, ServerLevel level) {
        if (entity == null || level == null || entity.level().isClientSide) {
            ModLog.attack("EntityRemover.hardRemove 被拒：null/客户端侧", null, entity,
                    "entity=" + (entity == null ? "null" : "非null") + " level=" + (level == null ? "null" : "非null")
                            + " clientSide=" + (entity != null && entity.level().isClientSide));
            return;
        }
        if (entity instanceof Player) {
            // 玩家走 PlayerList + ServerGamePacketListenerImpl：直接摘 EntityLookup 会让玩家列表与实体不一致。
            ModLog.attack("EntityRemover.hardRemove[层=玩家回退] 对玩家改用 remove(KILLED)", null, entity);
            entity.remove(Entity.RemovalReason.KILLED);
            return;
        }
        // 先主动掉落（与正常路径共用 LOOT_DROPPED 单次保证），再摘除。
        LootHelper.dropDeathLoot(entity, ModConstants.createStarSwordAbsoluteDamageSource(level, null));

        removeFromStorage(entity, level);

        // 通知客户端移除；否则客户端会保留一个幽灵实体。
        try {
            level.getChunkSource().broadcastAndSend(entity, new ClientboundRemoveEntitiesPacket(entity.getId()));
        } catch (Throwable t) {
            ModLog.attack("EntityRemover.hardRemove[层=通知客户端] 发送 ClientboundRemoveEntitiesPacket 失败，"
                    + "客户端可能残留幽灵实体", null, entity, "异常=" + t);
        }
        ModLog.attack("EntityRemover.hardRemove 绕过 remove 直接摘除实体", null, entity);
    }

    private static void removeFromStorage(Entity entity, ServerLevel level) {
        Object manager = entityManager(level);
        if (manager == null) {
            // 摘除实体存储失败：实体可能仍留在 level 里（幽灵/不死），必须能被看见。
            ModLog.attack("EntityRemover.removeFromStorage[层=EntityLookup] entityManager 反射拿不到，"
                    + "无法从实体存储摘除", null, entity);
        } else {
            try {
                @SuppressWarnings("unchecked")
                PersistentEntitySectionManagerAccessor<Entity> accessor =
                        (PersistentEntitySectionManagerAccessor<Entity>) manager;
                EntityLookup<Entity> lookup = accessor.starSword$getVisibleEntityStorage();
                if (lookup != null) {
                    lookup.remove(entity);
                    ModLog.attack("EntityRemover.removeFromStorage[层=EntityLookup] 已摘除", null, entity);
                } else {
                    ModLog.attack("EntityRemover.removeFromStorage[层=EntityLookup] visibleEntityStorage 为 null，"
                            + "跳过", null, entity);
                }
                EntitySectionStorage<Entity> sections = accessor.starSword$getSectionStorage();
                if (sections != null) {
                    long chunkKey = ChunkPos.asLong(entity.blockPosition());
                    sections.getExistingSectionsInChunk(chunkKey).forEach(section -> section.remove(entity));
                    ModLog.attack("EntityRemover.removeFromStorage[层=EntitySection] 已从所属 section 摘除",
                            null, entity, "chunkKey=" + chunkKey);
                } else {
                    ModLog.attack("EntityRemover.removeFromStorage[层=EntitySection] sectionStorage 为 null，跳过",
                            null, entity);
                }
                Set<UUID> knownUuids = accessor.starSword$getKnownUuids();
                if (knownUuids != null) {
                    knownUuids.remove(entity.getUUID());
                }
            } catch (Throwable t) {
                // @Accessor 织入失败 / 字段类型不符等都会走到这里：整段摘除失效。
                ModLog.attack("EntityRemover.removeFromStorage[层=EntityLookup/EntitySection] 抛异常，摘除失效",
                        null, entity, "异常=" + t);
            }
        }

        // 从活跃 tick 列表移除，否则实体被摘除后仍可能被 ServerLevel.tick 迭代到。
        Object tickList = entityTickList(level);
        if (tickList instanceof EntityTickList etl) {
            try {
                etl.remove(entity);
                ModLog.attack("EntityRemover.removeFromStorage[层=EntityTickList] 已从活跃 tick 列表移除",
                        null, entity);
            } catch (Throwable t) {
                ModLog.attack("EntityRemover.removeFromStorage[层=EntityTickList] 移除抛异常，实体可能仍被 tick",
                        null, entity, "异常=" + t);
            }
        } else {
            ModLog.attack("EntityRemover.removeFromStorage[层=EntityTickList] entityTickList 反射拿不到或类型不符，"
                    + "实体可能仍被 tick", null, entity, "tickList="
                    + (tickList == null ? "null" : tickList.getClass().getSimpleName()));
        }

        // 直写 removalReason，令 isRemoved() 返回 true（不调 remove/setRemoved，避开其他模组的拦截）。
        setRemovalReason(entity, Entity.RemovalReason.KILLED);
        // 断开 levelCallback，避免残留回调在区块卸载等时机再次操作这个实体。
        try {
            entity.setLevelCallback(EntityInLevelCallback.NULL);
        } catch (Throwable t) {
            ModLog.attack("EntityRemover.removeFromStorage[层=levelCallback] 断开 levelCallback 抛异常",
                    null, entity, "异常=" + t);
        }
    }

    // ===================== 反射字段 =====================

    private static Object entityManager(ServerLevel level) {
        Field field = entityManagerField();
        if (field == null) {
            return null;
        }
        try {
            return field.get(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field entityManagerField() {
        if (!entityManagerResolved) {
            synchronized (EntityRemover.class) {
                if (!entityManagerResolved) {
                    entityManagerField = ReflectionHelper.findFieldCompat(
                            ServerLevel.class, "f_143244_", "entityManager");
                    entityManagerResolved = true;
                }
            }
        }
        return entityManagerField;
    }

    private static Object entityTickList(ServerLevel level) {
        Field field = entityTickListField();
        if (field == null) {
            return null;
        }
        try {
            return field.get(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field entityTickListField() {
        if (!entityTickListResolved) {
            synchronized (EntityRemover.class) {
                if (!entityTickListResolved) {
                    entityTickListField = ReflectionHelper.findFieldCompat(
                            ServerLevel.class, "f_143243_", "entityTickList");
                    entityTickListResolved = true;
                }
            }
        }
        return entityTickListField;
    }

    private static void setRemovalReason(Entity entity, Entity.RemovalReason reason) {
        Field field = removalReasonField();
        if (field == null) {
            ModLog.attack("EntityRemover.setRemovalReason[层=removalReason] 反射字段 f_146795_/removalReason 解析失败，"
                    + "isRemoved() 不会变 true", null, entity);
            return;
        }
        try {
            field.set(entity, reason);
        } catch (Throwable t) {
            ModLog.attack("EntityRemover.setRemovalReason[层=removalReason] 直写抛出异常", null, entity,
                    "异常=" + t);
        }
    }

    private static Field removalReasonField() {
        if (!removalReasonResolved) {
            synchronized (EntityRemover.class) {
                if (!removalReasonResolved) {
                    removalReasonField = ReflectionHelper.findFieldCompat(
                            Entity.class, "f_146795_", "removalReason");
                    removalReasonResolved = true;
                }
            }
        }
        return removalReasonField;
    }
}
