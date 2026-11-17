package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 发包侧接管：包离开服务端之前改写血量，或丢弃"针对受保护玩家"的移除/击杀包。
 *
 * <p>写侧闸门只保证“服务端不接受减血写入”；但如果减血值已经进了某次同步、或者外部实现直接构造包
 * 发给客户端，客户端仍会看到错误血量。这里在 {@code Connection.send} 这一层（还未序列化）拦截。
 *
 * <p>处理四类包：
 * <ul>
 *   <li>{@link ClientboundSetHealthPacket}：血量/饥饿/饱和，直接重建为权威血量版本；</li>
 *   <li>{@link ClientboundSetEntityDataPacket}：只改 FLOAT 序列化且低于权威值的数据项；</li>
 *   <li>{@link ClientboundRemoveEntitiesPacket}：剔除受保护玩家 id，空了就<b>丢包</b>；</li>
 *   <li>{@link ClientboundPlayerCombatKillPacket}：目标受保护就<b>丢包</b>（客户端死亡界面）。</li>
 * </ul>
 *
 * <p><b>丢包的表示</b>：返回 {@code null}，并且调用方的注入片段必须在赋值后
 * {@code if ($1 == null) { return; }}。{@code Connection.send} 是 void，把 null 交给原方法会在
 * {@code ConnectionProtocol.getProtocolForPacket} 里 {@code null.getClass()} 直接 NPE。
 *
 * <p>接收者从 {@code Connection.getPacketListener()} 反查（{@code ServerGamePacketListenerImpl.player}）；
 * 实体数据包的主体用该玩家所在世界按 entityId 反查。任何一步拿不到就原样放行。
 *
 * <p>边界：只覆盖 {@code Connection.send}。直接 {@code channel.writeAndFlush(...)}、或者把这两个包
 * 塞进 {@code ClientboundBundlePacket} 的内层，都绕过本过滤。
 */
// [DEFENSE-MARK] 防御：发包侧把血量纠正为权威值。
public final class OutgoingFilter {

    private OutgoingFilter() {
    }

    /** 供注入代码调用：返回要真正发出去的包；返回 {@code null} 表示<b>丢包</b>。 */
    // [DEFENSE-MARK] 防御：发包侧纠正血量 / 丢弃针对受保护玩家的移除与击杀包。
    public static Object filterOutgoing(Object connection, Object packet) {
        long stat = ReconcileStats.OUTGOING.start();
        Object result = computeFilterOutgoing(connection, packet);
        ReconcileStats.OUTGOING.end(stat, result != packet);
        return result;
    }

    private static Object computeFilterOutgoing(Object connection, Object packet) {
        if (!(connection instanceof Connection conn) || !(packet instanceof Packet<?> original)) {
            return packet;
        }
        try {
            if (original instanceof ClientboundSetHealthPacket healthPacket) {
                Player recipient = recipientOf(conn);
                if (recipient == null) {
                    return packet;
                }
                float authoritative = AgentHooks.authoritativeHealthOf(recipient);
                if (Float.isNaN(authoritative)) {
                    return packet;
                }
                if (ModLog.ENABLED) {
                    ModLog.healthThrottled("OutgoingFilter.health:" + recipient.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "OutgoingFilter[层=Connection.send] 观察到血量包", recipient,
                            "包内=" + healthPacket.getHealth() + " 权威=" + authoritative);
                }
                if (healthPacket.getHealth() == authoritative) {
                    return packet;
                }
                ModLog.health("OutgoingFilter 改写血量包", recipient,
                        "原=" + healthPacket.getHealth() + " -> " + authoritative);
                return new ClientboundSetHealthPacket(authoritative,
                        healthPacket.getFood(), healthPacket.getSaturation());
            }

            if (original instanceof ClientboundSetEntityDataPacket dataPacket) {
                Player recipient = recipientOf(conn);
                if (recipient == null) {
                    return packet;
                }
                Entity subject = recipient.level().getEntity(dataPacket.id());
                if (!(subject instanceof Player tracked)) {
                    return packet;
                }
                float authoritative = AgentHooks.authoritativeHealthOf(tracked);
                if (Float.isNaN(authoritative)) {
                    return packet;
                }
                List<SynchedEntityData.DataValue<?>> rewritten =
                        rewriteFloatItems(dataPacket.packedItems(), authoritative);
                if (rewritten == null) {
                    return packet;
                }
                if (ModLog.ENABLED) {
                    ModLog.healthThrottled("OutgoingFilter.entitydata:" + tracked.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "OutgoingFilter[层=Connection.send] 改写实体数据包里的血量", tracked,
                            "权威=" + authoritative + " 项数=" + rewritten.size());
                }
                return new ClientboundSetEntityDataPacket(dataPacket.id(), rewritten);
            }

            // 止损 1：实体移除包——剔除受保护玩家 id；剔空了就丢包。
            if (original instanceof ClientboundRemoveEntitiesPacket removePacket) {
                Player recipient = recipientOf(conn);
                if (recipient == null) {
                    return packet;
                }
                IntList source = removePacket.getEntityIds();
                IntList kept = new IntArrayList(source.size());
                boolean removedAny = false;
                for (int i = 0; i < source.size(); i++) {
                    int entityId = source.getInt(i);
                    if (isProtectedPlayer(recipient, entityId)) {
                        removedAny = true;
                    } else {
                        kept.add(entityId);
                    }
                }
                if (!removedAny) {
                    return packet;
                }
                if (kept.isEmpty()) {
                    if (ModLog.ENABLED) {
                        ModLog.defenseThrottled("OutgoingFilter.removeEntities.drop:" + recipient.getUUID(),
                                ModLog.TICK_INTERVAL,
                                "OutgoingFilter[层=Connection.send] 丢弃实体移除包（全部是受保护玩家）",
                                recipient, "ids=" + source);
                    }
                    return null;
                }
                if (ModLog.ENABLED) {
                    ModLog.defenseThrottled("OutgoingFilter.removeEntities.rebuild:" + recipient.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "OutgoingFilter[层=Connection.send] 重建实体移除包（剔除受保护玩家）",
                            recipient, "原=" + source + " -> " + kept);
                }
                return new ClientboundRemoveEntitiesPacket(kept);
            }

            // 止损 2：击杀包——目标是受保护玩家就丢（客户端据此弹死亡界面）。
            if (original instanceof ClientboundPlayerCombatKillPacket killPacket) {
                Player recipient = recipientOf(conn);
                if (recipient == null) {
                    return packet;
                }
                if (!isProtectedPlayer(recipient, killPacket.getPlayerId())) {
                    return packet;
                }
                if (ModLog.ENABLED) {
                    ModLog.defenseThrottled("OutgoingFilter.combatKill.drop:" + recipient.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "OutgoingFilter[层=Connection.send] 丢弃击杀包（目标是受保护玩家）",
                            recipient, "targetId=" + killPacket.getPlayerId());
                }
                return null;
            }
        } catch (Throwable t) {
            ModLog.health("OutgoingFilter 处理包失败，原样放行", null, "包=" + original.getClass().getName()
                    + " 异常=" + t);
        }
        return packet;
    }

    /** 只替换 FLOAT 序列化且低于权威值的数据项；无变化时返回 null 表示“包不用换”。 */
    private static List<SynchedEntityData.DataValue<?>> rewriteFloatItems(
            List<SynchedEntityData.DataValue<?>> items, float authoritative) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<SynchedEntityData.DataValue<?>> out = null;
        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> item = items.get(i);
            if (item.serializer() != EntityDataSerializers.FLOAT
                    || !(item.value() instanceof Float value)
                    || Float.isNaN(value) || value >= authoritative) {
                if (out != null) {
                    out.add(item);
                }
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(items.size());
                out.addAll(items.subList(0, i));
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            SynchedEntityData.DataValue<?> replacement = new SynchedEntityData.DataValue(
                    item.id(), (EntityDataSerializer) item.serializer(), authoritative);
            out.add(replacement);
        }
        return out;
    }

    /**
     * {@code entityId} 是否是"受保护玩家"。
     *
     * <p>先比对接收者自己，再扫世界玩家列表（玩家被移除后 {@code getEntity} 可能返回 null，
     * 玩家列表更可靠），最后才回退 {@code getEntity}。非玩家实体一律 false。
     */
    private static boolean isProtectedPlayer(Player recipient, int entityId) {
        if (recipient.getId() == entityId) {
            return SwordGuard.isProtected(recipient);
        }
        if (recipient.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (ServerPlayer candidate : serverLevel.players()) {
                if (candidate.getId() == entityId) {
                    return SwordGuard.isProtected(candidate);
                }
            }
        }
        Entity entity = recipient.level().getEntity(entityId);
        return entity instanceof Player player && SwordGuard.isProtected(player);
    }

    /** {@code Connection.getPacketListener()} → {@code ServerGamePacketListenerImpl.player}。 */
    private static Player recipientOf(Connection connection) {
        try {
            PacketListener listener = connection.getPacketListener();
            if (listener instanceof ServerGamePacketListenerImpl game) {
                return game.player;
            }
        } catch (Throwable ignored) {
            // 非玩家连接（握手/状态阶段）直接放行
        }
        return null;
    }
}
