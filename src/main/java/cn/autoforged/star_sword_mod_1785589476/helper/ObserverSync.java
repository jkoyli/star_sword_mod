package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多观察者一致性：把“权威血量”推给别人的客户端。
 *
 * <p>{@code SynchedEntityData.set} 只改服务端本地的数据项并把它标记为 dirty；真正把这些值
 * 发给观察者的是 {@code ChunkMap} 的广播路径。如果某次减血写入已经通过那一次同步发出去，
 * 服务端状态即使是对的，其他人看到的仍然是你横躺。
 *
 * <p>这里用原生入口 {@code ChunkMap.broadcast(Entity, Packet)}：它只发给该实体的追踪者
 * （{@code TrackedEntity.seenBy}），不需要自己遍历玩家列表，也不需要反射进 seenBy。
 * 构造的包是标准 {@link ClientboundSetEntityDataPacket}，与 vanilla 同步共用同一套客户端处理。
 *
 * <p>取权威值（{@link PlayerDefense#getRealHealth}）而不是当前槽值：观察者必须看到我们认定的
 * 真实血量，而不是外部实现刚写进去的那个数。
 *
 * <p>限频：同一实体 {@link #THROTTLE_TICKS} tick 内最多广播一次，避免闸门连击把广播打成风暴。
 */
// [DEFENSE-MARK] 防御：把权威血量同步给观察者，修正“服务端对、别人看到错”的窗口。
public final class ObserverSync {

    /** 同一实体两次广播之间的最小间隔（tick）。 */
    private static final int THROTTLE_TICKS = 10;

    private static final Map<UUID, Integer> LAST_BROADCAST = new ConcurrentHashMap<>();

    /** {@code ServerChunkCache.chunkMap}（SRG {@code f_8325_}）。 */
    private static volatile Field chunkMapField;
    private static volatile boolean chunkMapResolved;

    /** {@code ChunkMap.broadcast(Entity, Packet)}（SRG {@code m_140201_}）。 */
    private static volatile Method broadcastMethod;
    private static volatile boolean broadcastResolved;

    private ObserverSync() {
    }

    /**
     * 把 {@code authoritative} 血量以实体数据包形式广播给该实体的所有观察者。
     *
     * @param entity        目标实体（通常是受保护玩家）
     * @param authoritative 权威血量；NaN 表示“没有权威值”，此时不动作
     */
    // [DEFENSE-MARK] 防御：广播权威血量给观察者。
    public static void broadcastHealth(LivingEntity entity, float authoritative) {
        long stat = ReconcileStats.OBSERVER.start();
        boolean broadcast = computeBroadcastHealth(entity, authoritative);
        ReconcileStats.OBSERVER.end(stat, broadcast);
    }

    private static boolean computeBroadcastHealth(LivingEntity entity, float authoritative) {
        if (entity == null || Float.isNaN(authoritative) || Float.isInfinite(authoritative)) {
            return false;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        UUID id = entity.getUUID();
        int tick = entity.tickCount;
        Integer last = LAST_BROADCAST.get(id);
        if (last != null && tick - last < THROTTLE_TICKS) {
            return false;
        }
        LAST_BROADCAST.put(id, tick);

        try {
            EntityDataAccessor<Float> key = ReflectionHelper.healthDataAccessor();
            if (key == null) {
                ModLog.health("ObserverSync 血量数据键解析失败，跳过观察者广播", entity, "");
                return false;
            }
            SynchedEntityData.DataValue<Float> value =
                    SynchedEntityData.DataValue.create(key, authoritative);
            ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(
                    entity.getId(), List.<SynchedEntityData.DataValue<?>>of(value));

            if (broadcastViaChunkMap(level, entity, packet)) {
                ModLog.healthThrottled("ObserverSync.chunkmap:" + id, ModLog.TICK_INTERVAL,
                        "ObserverSync 通过 ChunkMap 向观察者广播权威血量", entity,
                        "值=" + authoritative);
                return true;
            }
            broadcastToLevelPlayers(level, packet, id, authoritative);
            return true;
        } catch (Throwable t) {
            ModLog.health("ObserverSync 广播异常", entity, "异常=" + t);
            return false;
        }
    }

    /** 原生路径：{@code ChunkMap.broadcast(entity, packet)} 只发给该实体的追踪者。 */
    private static boolean broadcastViaChunkMap(ServerLevel level, Entity entity, Packet<?> packet) {
        try {
            Object chunkSource = level.getChunkSource();
            Field field = chunkMapField();
            Method method = broadcastMethod();
            if (chunkSource == null || field == null || method == null) {
                return false;
            }
            Object chunkMap = field.get(chunkSource);
            if (chunkMap == null) {
                return false;
            }
            method.invoke(chunkMap, entity, packet);
            return true;
        } catch (Throwable t) {
            ModLog.health("ObserverSync ChunkMap 广播失败，回退到同维度玩家广播", entity,
                    "异常=" + t);
            return false;
        }
    }

    /** 兜底路径：同维度所有玩家（含自己，客户端需要这条纠正包）。 */
    private static void broadcastToLevelPlayers(ServerLevel level, Packet<?> packet, UUID id,
                                                float authoritative) {
        int sent = 0;
        for (ServerPlayer player : level.players()) {
            try {
                player.connection.send(packet);
                sent++;
            } catch (Throwable ignored) {
                // 单个连接异常不影响其它玩家
            }
        }
        ModLog.healthThrottled("ObserverSync.level:" + id, ModLog.TICK_INTERVAL,
                "ObserverSync 回退为同维度广播", null,
                "接收者=" + sent + " 值=" + authoritative);
    }

    private static Field chunkMapField() {
        if (!chunkMapResolved) {
            synchronized (ObserverSync.class) {
                if (!chunkMapResolved) {
                    chunkMapField = ReflectionHelper.findFieldCompat(
                            ServerChunkCache.class, "f_8325_", "chunkMap");
                    chunkMapResolved = true;
                }
            }
        }
        return chunkMapField;
    }

    private static Method broadcastMethod() {
        if (!broadcastResolved) {
            synchronized (ObserverSync.class) {
                if (!broadcastResolved) {
                    broadcastMethod = ReflectionHelper.findMethodCompat(
                            ChunkMap.class, "m_140201_", "broadcast", Entity.class, Packet.class);
                    broadcastResolved = true;
                }
            }
        }
        return broadcastMethod;
    }
}
