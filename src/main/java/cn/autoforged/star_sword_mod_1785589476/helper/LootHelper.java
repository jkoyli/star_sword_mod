package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class LootHelper {

    /**
     * 主动驱动掉落：直接调用原版 {@code LivingEntity.dropAllDeathLoot(DamageSource)}。
     *
     * <p>{@code dropAllDeathLoot} 是唯一同时覆盖 loot table 与 {@code dropCustomDeathLoot}
     * 的入口，因此模组生物覆写的自定义掉落也能被覆盖。它在本项目里是 protected，
     * 由 {@link ReflectionHelper#invokeDropAllDeathLoot} 反射调用。
     *
     * <p>用 {@link ModConstants#LOOT_DROPPED} 做单次保证：无论原版 {@code die()} 已经掉过，
     * 还是本模组强制归零血量绕过 {@code die()} 后再补掉，都只掉一次，避免重复掉落。
     */
    public static void dropDeathLoot(LivingEntity entity, DamageSource source) {
        // 玩家不参与本模组的强制击杀/强制移除（死亡锁也显式排除玩家），
        // 故不对玩家主动补掉，避免其死亡被取消时反而丢出背包物品。
        if (entity == null || entity instanceof Player || entity.level().isClientSide) {
            ModLog.attack("LootHelper.dropDeathLoot 跳过：null/玩家/客户端侧不主动补掉", null,
                    entity instanceof Player ? null : entity,
                    "entity=" + (entity == null ? "null" : entity.getClass().getSimpleName()));
            return;
        }
        if (ModConstants.LOOT_DROPPED.add(entity.getUUID())) {
            ModLog.attack("LootHelper.dropDeathLoot 主动驱动掉落", null, entity,
                    "伤害来源=" + (source == null ? "null" : source.getMsgId()));
            if (!ReflectionHelper.invokeDropAllDeathLoot(entity, source)) {
                // 反射失败时撤销单次标记，避免把“没掉成”永久当成“已掉过”。
                ModConstants.LOOT_DROPPED.remove(entity.getUUID());
                ModLog.attack("LootHelper.dropDeathLoot 反射调用失败，撤销标记", null, entity);
            }
        } else {
            ModLog.attack("LootHelper.dropDeathLoot 跳过：LOOT_DROPPED 已有记录（原版已掉过）", null, entity);
        }
    }

    /** 先主动掉落再强制移除实体（顺序不可颠倒：移除后实体上下文已不可用）。 */
    public static void forceRemoveAndDrop(LivingEntity entity, Level level) {
        if (!entity.level().isClientSide) {
            dropDeathLoot(entity, ModConstants.createStarSwordAbsoluteDamageSource(level, null));
        }
        ModLog.attack("LootHelper.forceRemoveAndDrop 强制移除实体", null, entity);
        entity.remove(Entity.RemovalReason.KILLED);
    }
}
