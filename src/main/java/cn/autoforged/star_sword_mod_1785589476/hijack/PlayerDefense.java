package cn.autoforged.star_sword_mod_1785589476.hijack;

import cn.autoforged.star_sword_mod_1785589476.helper.EntityIntegrityProbe;
import cn.autoforged.star_sword_mod_1785589476.helper.HealthWritePolicy;
import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.RawState;
import cn.autoforged.star_sword_mod_1785589476.helper.ReadSideGuard;
import cn.autoforged.star_sword_mod_1785589476.helper.RejectStats;
import cn.autoforged.star_sword_mod_1785589476.helper.RemovalPolicy;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.AttributeGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.AuthorityHealthStore;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.ForeignChannelGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthAuthority;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.HealthDataItemInstaller;
import cn.autoforged.star_sword_mod_1785589476.hijack.authority.VanillaStateLock;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 星辉剑玩家防御核心（方向一 / 四 / 五 的落地实现，并为方向二 / 三 的字节码 advice 提供数据）。
 *
 * <p><b>方向一（真实血量旁路存储）</b>：持有星辉剑的玩家在 {@link #REAL_HEALTH} 里维护一份
 * “旁路真实血量”。该 Map 只由本类读写，写入路径不经过任何可能被其它模组劫持的
 * {@code getHealth()/setHealth()} 方法。{@code serverTick} <b>不读取原版血量</b>
 * （其它模组正是通过字段/同步数据改血，读进来只会污染旁路存储）：每 tick 把旁路值
 * 固定恢复为 {@link #BYPASS_HEALTH}（20），随后通过 {@link #forceWriteVanillaHealth}
 * 把该值强制同步写回原版血量同步数据。
 *
 * <p><b>方向四（死亡检测与补血收归自己）</b>：tick 里不再以 {@code getHealth()/字段} 做决策——
 * 旁路血量每 tick 恢复为 20（等价补血收归自己），保证持剑玩家持续满血。
 *
 * <p><b>方向三（移除状态写监控）</b>：1.20.1 没有 boolean removed 字段，{@code isRemoved()}
 * 读的是私有 {@code removalReason}（SRG {@code f_146795_}）。tick 里轮询该字段，若在血量 &gt; 0
 * 时被置为 KILLED/DISCARDED，立刻还原为 null；配合 {@code EntityIsRemovedMixin} 在方法体
 * 层面把返回值锁死。
 *
 * <p><b>方向五（setHealth 入参过滤 + 高频写入诊断）</b>：{@code LivingEntityMixin} 在
 * {@code setHealth} 入口把入参交给 {@link #filterHealthWrite}——受保护玩家的一切写入都被替换为
 * 旁路真实血量 {@link #REAL_HEALTH}（与 Mixin 的“非法值兜底”职责分离）；同时保留原有的每 tick
 * 写入计数，超阈值时打开短暂“血量锁定窗口”（invulnerable + 每 tick 覆盖字段）作为诊断/兜底。
 *
 * <p><b>持久化</b>：{@link #saveRealHealth}/{@link #loadRealHealth} 经玩家 PersistentData 键
 * {@link #NBT_REAL_HEALTH} 存取，分别在 {@code PlayerEvent.Clone} 与 {@code PlayerLoggedInEvent}
 * 中调用。
 *
 * <p>原字节码级安装入口 {@code PlayerDefenseHijack} / 三个 Advice 类已随 ByteBuddy 子系统移除，
 * 三个回调改由 Mixin 直接调用（见 {@code LivingEntityMixin} / {@code EntityIsRemovedMixin}）。
 */
// ============================================================================
// [DEFENSE-MARK] 防御性代码集中标记（下一步修改目标）
// [REVIVE-MARK] 复活：本类整体是星辉剑玩家防御核心：
//   旁路真实血量存储 / 每 tick 写回、getHealth 锁定、setHealth 与同步数据写入过滤、
//   isRemoved 压制、死亡/重生强制复活、真实血量跨端同步。
// ============================================================================
public final class PlayerDefense {

    /** 持有星辉剑玩家的旁路真实血量。存在即代表“该玩家处于防御状态”。 */
    private static final Map<UUID, Float> REAL_HEALTH = AuthorityHealthStore.asMap();

    /**
     * 已退役（保护到期但玩家仍存活）的旁路记录 UUID 集合。
     *
     * <p>需求要求“REAL_HEALTH 移除更保守”：{@link #onProtectionExpired} /
     * {@link #clearSyncedRealHealth} 在玩家还活着时不再删除旁路记录，只把该记录标记为退役，
     * 避免出现“活着但无记录”的窗口被别人趁虚写入。退役记录不再作为权威血量使用，
     * 等实体真正被 remove（{@link #onPlayerRemoved}）后才连同 {@link #REAL_HEALTH} 一起清除。
     */
    private static final Set<UUID> RETIRED = ConcurrentHashMap.newKeySet();

    /**
     * “读取侧已被篡改”的玩家集合 —— 针对“被替换的方法体在类加载期整体换掉 getHealth()”这一类攻击。
     *
     * <p>判定只看<b>事实矛盾</b>，不认外部实现是谁、不依赖注入点是否还在、也不依赖 {@code isProtected}：
     * 报告血量 &lt; 0（或 NaN / 无限）而 {@link #REAL_HEALTH} 里的权威值 &gt; 0，说明读取口返回的
     * 根本不是这份实体的真实血量。当前唯一的置位点是服务端 tick 的 {@link #tickReadHealth}
     * （它绕开我们自己的读取钩子取证）；集合用于冻结该玩家身上“只增不减”的私有 Float 同步通道
     * （{@link #guardPrivateFloatChannel}），掐掉外部累加量这类外部私有状态的源头。
     *
     * <p>客户端不依赖这个标记：那边的判据是"方法层心跳"（{@code MethodLayerState}）与三处共用的
     * 通道判据（{@code ForeignChannelGuard.decide}）。
     */
    private static final Set<UUID> READ_TAMPERED = ConcurrentHashMap.newKeySet();

    /** 私有 Float 同步通道的行为采样：uuid -> 采样器（纯逻辑见 {@link ReadSideGuard.ChannelFreezer}）。 */
    private static final Map<UUID, ReadSideGuard.ChannelFreezer> PRIVATE_CHANNELS =
            new ConcurrentHashMap<>();

    /** 连续多少次“只增不减”的写入才把私有通道判为可疑累加器。 */
    private static final int SUSPICIOUS_INCREASES = 3;

    /**
     * 私有 Float 通道的“只拦写入”总开关，<b>默认开启</b>。
     *
     * <p>历史（实测结论）：
     * <ul>
     *   <li>2026-11-15 2.0.4：拦截 + 把数据项/字段清零 → 周期性空血（清零动作误伤健康值本身）；</li>
     *   <li>2.0.8：整体关闭 → 被攻击后<b>持续</b>空血立正——说明这条拦截一直在挡外部实现对
     *       血量项的写入，关掉就等于开门。</li>
     * </ul>
     * 所以现在：<b>只拦写入（{@code ci.cancel()}），不再清零</b>。拦截不需要知道通道语义，
     * 而清零必须知道，后者已被证明会误伤。
     */
    private static final boolean PRIVATE_CHANNEL_GUARD =
            Boolean.parseBoolean(System.getProperty("star_sword.privateChannelGuard", "true"));

    /** 读取口连续多少 tick 不再矛盾后，撤销“被篡改”标记并解冻私有通道。 */
    private static final int READ_CLEAR_TICKS = 100;

    /** 读取口恢复计数：uuid -> 连续正常的 tick 数。 */
    private static final Map<UUID, Integer> READ_CLEAN_TICKS = new ConcurrentHashMap<>();

    /**
     * “原始读值探测”标记（同线程）。
     *
     * <p>问题：本模组自己的 HEAD/RETURN 钩子会把 {@code getHealth()} 纠正成权威值，从而把
     * “被替换的方法体”的证据一起遮住——不绕过自己的钩子就永远测不到真相。探测时置位本标记，
     * 所有读取侧钩子（agent HEAD / agent RETURN / Mixin RETURN）对这<b>一次</b>调用放行，
     * 于是拿到的就是外部私有状态换算出的原始读数。
     */
    private static final ThreadLocal<Boolean> RAW_READ_PROBE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 每 tick 血量写入统计与锁定窗口。 */
    private static final Map<UUID, WriteStat> WRITE_STATS = new ConcurrentHashMap<>();

    /** 高频写入触发后临时打开的 invulnerable 之前的值，窗口结束/失去星辉剑时还原。 */
    private static final Map<UUID, Boolean> PREV_INVULNERABLE = new ConcurrentHashMap<>();

    /**
     * 状态锁：本窗口内被拒绝的 Float 写入，<b>按通道聚合</b>。
     *
     * <p>为什么单独计数而不是逐条打日志：对手的写入频率实测可以超过 100 次/秒，如果每次拒绝都走
     * "限频判定 + 日志"这条路径，拦得越多开销越大，反而成了 DoS 面。热路径只做一次
     * {@code computeIfAbsent} + 一次原子自增；排序与 top-N 只在日志节拍（{@code TICK_INTERVAL}）里做。
     */
    private static final Map<UUID, RejectedWrites> REJECTED_WRITES = new ConcurrentHashMap<>();

    /** 窗口汇总里最多列出几条通道（其余折叠成"共 N 条"）。 */
    private static final int MAX_REJECT_CHANNELS_SHOWN = 3;

    /**
     * 客户端：本窗口内清了几次死亡字段。
     *
     * <p>用途是<b>区分"修好了"和"拉锯"</b>：如果某个写者每 tick 推进 {@code deathTime}，这个计数会持续
     * 大于 1；只在登录瞬间出现过一次则是 1。日志里同时给出清零后的值和这个次数。
     */
    private static final Map<UUID, Integer> DEATH_CLEARS = new ConcurrentHashMap<>();

    /** 一条通道的拒绝统计（计数原子、值允许轻微 race：纯诊断）。 */
    private static final class ChannelStat {
        private final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile float lastValue = Float.NaN;

        private void record(float value) {
            count.incrementAndGet();
            lastValue = value;
        }
    }

    /** 某个玩家的拒绝写入窗口统计。 */
    private static final class RejectedWrites {
        /** 通道 id → 本窗口该通道被拒次数。 */
        private final Map<Integer, ChannelStat> channels = new ConcurrentHashMap<>();
        /** 本会话里已经单独报过的通道：等价于被硬拒遮蔽掉的「通道探测」首现行。 */
        private final Set<Integer> reported = ConcurrentHashMap.newKeySet();

        private void note(int channelId, float value) {
            channels.computeIfAbsent(channelId, k -> new ChannelStat()).record(value);
        }
    }

    /** 上次执行防御 tick 的 tickCount，保证 inventoryTick 与 PlayerTick 两个调用点每 tick 只跑一次。 */
    private static final Map<UUID, Integer> LAST_GUARD_TICK = new ConcurrentHashMap<>();

    /**
     * 本模组自己写回原版血量时的旁路标记：{@code SynchedEntityDataMixin} 在
     * {@code SynchedEntityData.set} HEAD 处会拦“低于旁路血量的写入”，而
     * {@link #forceWriteVanillaHealth} 正是要把同步数据改回旁路值，必须跳过这次拦截。
     * 用 ThreadLocal 而非静态布尔：set 与原调用同线程，避免多线程下互相污染。
     */
    private static final ThreadLocal<Boolean> SYNCED_WRITE_BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 旁路真实血量每 tick 恢复到的目标值（需求明确要求为 20，不随 getMaxHealth 变化）。 */
    public static final float BYPASS_HEALTH = 20.0F;

    /** 向客户端同步旁路真实血量的最小间隔（tick），避免每 tick 发包。 */
    private static final int SYNC_INTERVAL_TICKS = 20;

    /** 上次向客户端同步旁路血量的 tickCount。 */
    private static final Map<UUID, Integer> LAST_SYNC_TICK = new ConcurrentHashMap<>();

    /** 一 tick 内超过该次数的 setHealth 视为异常高频写入。 */
    private static final int MAX_WRITES_PER_TICK = 5;

    /** 高频写入触发后的锁定时长（tick）。 */
    private static final int LOCK_TICKS = 40;

    /** 旁路真实血量持久化到玩家 PersistentData 的键（跨死亡克隆与重启登录保留）。 */
    public static final String NBT_REAL_HEALTH = "StarSwordRealHealth";

    private static volatile Field removalReasonField;
    private static volatile boolean removalReasonResolved;

    /** 原版 {@code LivingEntity.dead} 标志（protected，SRG {@code f_20890_}）反射句柄。 */
    private static volatile Field deadField;
    private static volatile boolean deadResolved;

    private PlayerDefense() {
    }

    /** 把当前旁路真实血量保存到玩家 PersistentData（供死亡克隆与重登恢复）。 */
    // [DEFENSE-MARK] 防御：旁路真实血量持久化（保存到玩家 PersistentData）。
    public static void saveRealHealth(Player player) {
        UUID id = player.getUUID();
        Float real = REAL_HEALTH.get(id);
        // 补非负校验：旁路值本身也必须合法，负数一律不落盘。
        if (real != null && !Float.isNaN(real) && !Float.isInfinite(real) && real >= 0.0F
                && !RETIRED.contains(id)) {
            player.getPersistentData().putFloat(NBT_REAL_HEALTH, real);
        }
    }

    /** 从玩家 PersistentData 读取旁路真实血量并写回 {@link #REAL_HEALTH}。 */
    // [DEFENSE-MARK] 防御：旁路真实血量持久化（从 PersistentData 读回）。
    public static void loadRealHealth(Player player) {
        CompoundTag tag = player.getPersistentData();
        if (tag.contains(NBT_REAL_HEALTH)) {
            REAL_HEALTH.put(player.getUUID(), tag.getFloat(NBT_REAL_HEALTH));
            RETIRED.remove(player.getUUID());
        }
    }

    /** 防御状态到期（剑丢失超过宽限窗口）时由 {@link SwordGuard} 调用，清理旁路数据并还原 invulnerable。 */
    // [DEFENSE-MARK] 防御：防御状态到期清理旁路数据并还原 invulnerable。
    public static void onProtectionExpired(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        // 语义层：防御态结束就把数据项换回原版项——不留在“我们已不再裁决、却仍挂着代理”的中间态。
        // 重新进入防御态时 serverTick / 登录 / Clone 的安装点会再次装上。
        HealthDataItemInstaller.restore(player);
        if (retainWhileAlive(player) && REAL_HEALTH.containsKey(id)) {
            // 需求：玩家还活着时不要删干净，只把记录标记为退役，避免“活着但无记录”的窗口。
            // 真正的删除推迟到实体 remove（EntityMixin -> onPlayerRemoved）。
            RETIRED.add(id);
            WRITE_STATS.remove(id);
            LAST_GUARD_TICK.remove(id);
            clearReadState(id);
            restoreInvulnerable(player);
            // 通知客户端该记录不再权威（但不删本地记录，客户端同样走退役逻辑）。
            syncToClient(player, 0.0F, false);
            LAST_SYNC_TICK.remove(id);
            ModLog.defenseThrottled("PlayerDefense.expire.retain:" + id, ModLog.TICK_INTERVAL,
                    "PlayerDefense 防御到期，玩家仍存活：保留旁路记录并标记退役", player, "");
            return;
        }
        REAL_HEALTH.remove(id);
        RETIRED.remove(id);
        WRITE_STATS.remove(id);
        LAST_GUARD_TICK.remove(id);
        clearReadState(id);
        restoreInvulnerable(player);
        // 服务端应通知客户端清除本地旁路，避免客户端继续用陈旧的真实血量覆盖原版伤害包。
        syncToClient(player, 0.0F, false);
        LAST_SYNC_TICK.remove(id);
        ModLog.defenseThrottled("PlayerDefense.expire.clear:" + id, ModLog.TICK_INTERVAL,
                "PlayerDefense 防御到期，清理旁路血量并还原 invulnerable", player, "");
    }

    /**
     * 判断是否应把旁路记录保留到实体真正 remove：玩家尚存活（未被移除 / 未死亡 /
     * 不处于被移除状态）时返回 true。
     */
    private static boolean retainWhileAlive(Player player) {
        try {
            // 不读 isRemoved() / isDeadOrDying() 这类**方法**：外部实现可以把它们整体替换成恒 true，
            // 那会让这里把还活着的玩家当成“已死”清掉记录（保护状态直接崩塌）。
            // 只用两个原始字段 + 旁路权威值判断。
            if (isDeadFlag(player)) {
                return false;
            }
            Entity.RemovalReason reason = readRemovalReason(player);
            return reason == null || reason == Entity.RemovalReason.CHANGED_DIMENSION;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取原版 {@code LivingEntity.dead} 字段（不经过任何可被替换的方法）。 */
    private static boolean isDeadFlag(Player player) {
        Field field = deadField();
        if (field == null) {
            return false;
        }
        try {
            return field.getBoolean(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 实体真正被 remove 时由 {@code EntityMixin} 调用：此时才允许彻底清掉旁路记录，
     * 与“玩家存活期间保留记录”的策略配对。跨维度（CHANGED_DIMENSION）由 clone/登录的
     * {@link #loadRealHealth} 重新载入，不在清理范围内。
     */
    public static void onPlayerRemoved(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        REAL_HEALTH.remove(id);
        RETIRED.remove(id);
        WRITE_STATS.remove(id);
        LAST_GUARD_TICK.remove(id);
        LAST_SYNC_TICK.remove(id);
        PREV_INVULNERABLE.remove(id);
        clearReadState(id);
        // 语义层：实体真正消失，清掉代理登记（代理对象随实体一起被回收）。
        HealthDataItemInstaller.forget(id);
    }

    // ===================== 服务端 -> 客户端血量同步 =====================

    /** 把旁路真实血量写入 {@code REAL_HEALTH} 并强制设置玩家血量（客户端收包 / 服务端死亡克隆/重生兜底共用）。 */
    // [DEFENSE-MARK] 防御：写入旁路真实血量并强制设置本地血量。
    public static void acceptSyncedRealHealth(Player player, float value) {
        if (player == null || Float.isNaN(value) || Float.isInfinite(value) || value < 0.0F) {
            return;
        }
        REAL_HEALTH.put(player.getUUID(), value);
        RETIRED.remove(player.getUUID());
        try {
            // 走 setHealth：LivingEntityMixin 的入参过滤会发现旁路已有记录并以它为准，
            // SynchedEntityData 写入拦截也因“值不小于旁路值”而放行，最终客户端读数被纠正为真实血量。
            player.setHealth(value);
        } catch (Throwable ignored) {
        }
        ModLog.healthThrottled("PlayerDefense.acceptSyncedRealHealth:" + player.getUUID(),
                ModLog.TICK_INTERVAL,
                "acceptSyncedRealHealth[层=客户端收包] 已写入旁路并强制设置本地血量", player,
                "值=" + value);
    }

    /**
     * 状态锁专用：把权威血量写成一个<b>固定值</b>（登录 / 重生后立即生效，不等下一次 tick）。
     *
     * <p>与 {@link #acceptSyncedRealHealth} 的区别只在语义：那个是"收到服务端同步的值"，
     * 这个是"我们把值钉死在 {@code value}"，日志措辞也不同——排查时不能混。
     */
    // [DEFENSE-MARK] 防御：状态锁把权威血量钉在固定值。
    public static void lockHealth(Player player, float value) {
        if (player == null || Float.isNaN(value) || Float.isInfinite(value) || value < 0.0F) {
            return;
        }
        UUID id = player.getUUID();
        REAL_HEALTH.put(id, value);
        RETIRED.remove(id);
        // 这是我们自己的写回：必须绕过状态锁的写侧硬拒，否则 setHealth 内部的 entityData.set
        // 会被自己拦掉，同步数据要等下一次 forceWriteVanillaHealth 才落地。
        SYNCED_WRITE_BYPASS.set(Boolean.TRUE);
        try {
            player.setHealth(value);
        } catch (Throwable ignored) {
        } finally {
            SYNCED_WRITE_BYPASS.set(Boolean.FALSE);
        }
        ModLog.healthThrottled("PlayerDefense.lockHealth:" + id, ModLog.TICK_INTERVAL,
                "lockHealth[层=状态锁] 权威血量钉在固定值", player, "值=" + value);
    }

    /** 客户端收到服务端“已不受保护”同步：清除本地旁路记录，恢复原版血量包行为。 */
    // [DEFENSE-MARK] 防御：服务端判定不再受保护，清除本地旁路。
    public static void clearSyncedRealHealth(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (retainWhileAlive(player) && REAL_HEALTH.containsKey(id)) {
            // 需求：玩家还活着时不要删干净，只标记退役；等实体真正 remove 后再清。
            RETIRED.add(id);
            clearReadState(id);
            ModLog.healthThrottled("PlayerDefense.clearSyncedRealHealth.retain:" + id,
                    ModLog.TICK_INTERVAL,
                    "clearSyncedRealHealth[层=客户端收包] 服务端判定不再受保护，保留旁路记录并标记退役",
                    player, "");
            return;
        }
        REAL_HEALTH.remove(id);
        RETIRED.remove(id);
        clearReadState(id);
        ModLog.healthThrottled("PlayerDefense.clearSyncedRealHealth:" + id,
                ModLog.TICK_INTERVAL,
                "clearSyncedRealHealth[层=客户端收包] 服务端判定不再受保护，清除本地旁路", player, "");
    }

    /** 读取旁路真实血量；无有效记录时返回 {@code fallback}。 */
    // [DEFENSE-MARK] 防御：读取旁路真实血量。
    public static float getRealHealth(Player player, float fallback) {
        Float real = REAL_HEALTH.get(player.getUUID());
        if (real != null && !Float.isNaN(real) && !Float.isInfinite(real) && real >= 0.0F
                && !RETIRED.contains(player.getUUID())) {
            return real;
        }
        return fallback;
    }

    /** 旁路是否有有效记录。 */
    // [DEFENSE-MARK] 防御：判断旁路是否有有效记录。
    public static boolean hasRealHealth(Player player) {
        Float real = REAL_HEALTH.get(player.getUUID());
        return real != null && !Float.isNaN(real) && !Float.isInfinite(real) && real >= 0.0F
                && !RETIRED.contains(player.getUUID());
    }

    // ===================== 读取侧自检（不依赖注入点） =====================

    /**
     * 是否存在可作为“读取侧权威值”的旁路血量（有效、&gt; 0、未退役）。
     *
     * <p>与 {@link #hasRealHealth} 的区别：这里要求 {@code > 0}，因为读取侧要回答的是
     * “这个玩家的真实血量是多少”，0 表示我们自己也认为他死了，不该再去覆盖别人的读数。
     */
    // [DEFENSE-MARK] 防御：读取侧权威值是否存在。
    public static boolean hasAuthoritativeHealth(Player player) {
        return authoritativeHealthOrNull(player) != null;
    }

    // ===================== 权威值归属（本地 / 服务端） =====================

    /**
     * "这个实体是不是本地玩家"的判定，由客户端在初始化时注册。
     *
     * <p>为什么用注册而不是直接引用 {@code Minecraft}：{@code PlayerDefense} 双端共用，
     * 引用客户端类会让专用服务器在类加载/校验阶段就失败。
     */
    private static volatile java.util.function.Predicate<Entity> localPlayerProbe;

    /** 客户端注册本地玩家判定（重复注册以最后一次为准）。 */
    public static void registerLocalPlayerProbe(java.util.function.Predicate<Entity> probe) {
        localPlayerProbe = probe;
    }

    /**
     * 该实体的权威值是否由本进程持有。
     *
     * <p>服务端：权威值就在本进程，任何实体都算"本地"；客户端：只有本地玩家有权威值
     * （来自同步包），其它玩家身上没有基准——动他们的通道就是误伤他人的自定义资源条。
     */
    public static boolean isAuthorityLocal(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            if (!entity.level().isClientSide) {
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        java.util.function.Predicate<Entity> probe = localPlayerProbe;
        try {
            return probe != null && probe.test(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Float authoritativeHealthOrNull(Player player) {
        if (player == null) {
            return null;
        }
        UUID id = player.getUUID();
        if (RETIRED.contains(id)) {
            return null;
        }
        Float real = REAL_HEALTH.get(id);
        if (real == null || Float.isNaN(real) || Float.isInfinite(real) || real <= 0.0F) {
            return null;
        }
        return real;
    }

    /**
     * 直接读原版血量数据项 {@code DATA_HEALTH_ID}——等价于 vanilla {@code getHealth()} 的方法体，
     * 绕过任何被整体替换的公式。
     *
     * <p>读取顺序刻意是"先字段、后方法"：
     * <ol>
     *   <li>先经 {@link RawState#rawFloatOf} 直读 {@code Entity.entityData} →
     *       {@code itemsById} → {@code DataItem.value} 三个字段，完全不经过
     *       {@code SynchedEntityData.get(...)}（外部模组可能连该方法一起替换）；</li>
     *   <li>字段路径不可用时才退回 {@code getEntityData().get(key)}。</li>
     * </ol>
     *
     * <p>关键点：<b>客户端的数据项本身就是权威值</b>（服务端每 tick 把旁路血量写回该数据项并同步过来），
     * 而客户端的 {@code REAL_HEALTH} 可能为空（尚未收到同步包 / 记录已退役）。所以在这个位置不能
     * 依赖 {@code REAL_HEALTH}。
     *
     * @return 数据项里的血量；无法读取或实体数据尚未就绪时返回 {@link Float#NaN}
     */
    // [DEFENSE-MARK] 防御：绕过被替换的 getHealth / SynchedEntityData.get，直读字段取血量。
    public static float rawSyncedHealth(LivingEntity self) {
        if (self == null) {
            return Float.NaN;
        }
        EntityDataAccessor<Float> key = ReflectionHelper.healthDataAccessor();
        if (key == null) {
            return Float.NaN;
        }
        // 1) 最深层：只碰字段。
        float deep = RawState.rawFloatOf(self, key);
        if (!Float.isNaN(deep)) {
            return deep;
        }
        // 2) 字段路径不可用（映射漂移/反射失败）：退回普通读取。
        try {
            Float value = self.getEntityData().get(key);
            return value == null ? Float.NaN : value;
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * 客户端收到 {@code ClientboundSetEntityDataPacket} 时的过滤（包进入原版处理之前）。
     *
     * <p>日常同步走的是这个包（{@code ClientPacketListener.handleSetEntityData} →
     * {@code SynchedEntityData.assignValues}），而不是 {@code ClientboundSetHealthPacket}。
     * 处理两类项：
     * <ul>
     *   <li><b>原版血量键</b>：非法值（NaN / 无限 / 负数）→ 换成权威值，没有权威记录时换成当前数据项
     *       或最大血量；有权威记录时不允许被改小；</li>
     *   <li><b>非白名单浮点项</b>：与 {@code assignValues} 过滤、每 tick 中和共用同一个判据
     *       （{@link ForeignChannelGuard#decide}）。默认 observe 只观测；clear 模式压回 0；
     *       状态锁硬拒模式下把条目从包里摘掉（不写入、也不清零）。</li>
     * </ul>
     * 包级只能改同步项的值，通道背后按值匹配的实体字段仍由每 tick 的中和处理负责。
     * 需要改写时构造一个新包，否则原包返回（避免无谓分配）。
     */
    // [DEFENSE-MARK] 防御：纠正实体数据包里的血量项。
    public static ClientboundSetEntityDataPacket sanitizeIncomingHealthPacket(
            ClientboundSetEntityDataPacket packet, Player player) {
        if (packet == null || player == null) {
            return packet;
        }
        List<SynchedEntityData.DataValue<?>> items = packet.packedItems();
        List<SynchedEntityData.DataValue<?>> filtered = filterIncomingData(player, items,
                "sanitizeIncomingHealthPacket[层=handleSetEntityData]");
        return filtered == items ? packet : new ClientboundSetEntityDataPacket(packet.id(), filtered);
    }

    /**
     * 包级（{@code ClientboundSetEntityDataPacket}）与客户端 {@code assignValues} 共用的收包过滤。
     *
     * <p>两处<b>必须共用同一份判据</b>，否则会出现"服务端包已改、客户端没改"的半同步状态——这正是
     * private Float 通道在客户端一路涨上去的原因（{@code assignValues} 不经过 {@code set}）。
     *
     * <p>对非白名单浮点通道的处理随模式变化：
     * <ul>
     *   <li><b>clear 模式</b>：压回 0（旧行为，需要显式 {@code -Dstar_sword.foreignChannelGuard=clear}）；</li>
     *   <li><b>硬拒模式</b>（状态锁默认开）：把该条目<b>从包里摘掉</b>——不写入、也不清零，客户端保持原值，
     *       这才是"拒绝这次状态写入"；</li>
     *   <li><b>observe 模式</b>：原样放过，只记一行观测，一个字节都不改。</li>
     * </ul>
     *
     * <p>原版血量键永远走纠正（非法值 / 变小 → 权威值），与本类其它入口一致。
     */
    private static List<SynchedEntityData.DataValue<?>> filterIncomingData(Player player,
            List<SynchedEntityData.DataValue<?>> items, String layer) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
            int healthId = healthKey == null ? Integer.MIN_VALUE : healthKey.getId();
            Float real = authoritativeHealthOrNull(player);
            boolean authorityLocal = isAuthorityLocal(player);
            boolean protectedPlayer = SwordGuard.isProtected(player);
            int absorptionId = ForeignChannelGuard.absorptionChannelId();
            boolean clearMode = ForeignChannelGuard.mode() == ForeignChannelGuard.Mode.ON;
            boolean rejectForeign = VanillaStateLock.rejectStateWrites();

            List<SynchedEntityData.DataValue<?>> out = new ArrayList<>(items.size());
            boolean changed = false;
            for (SynchedEntityData.DataValue<?> item : items) {
                if (!(item.value() instanceof Float incoming)) {
                    out.add(item);
                    continue;
                }
                if (item.id() == healthId) {
                    float corrected = correctIncomingHealth(player, incoming, real);
                    if (corrected == incoming) {
                        out.add(item);
                        continue;
                    }
                    // 保留原 id / serializer，只换值。
                    out.add(replacementValue(item, corrected));
                    changed = true;
                    ModLog.healthThrottled("PlayerDefense.entityDataPacket:" + player.getUUID(),
                            ModLog.TICK_INTERVAL,
                            layer + " 纠正包内血量", player,
                            "包内=" + incoming + " -> " + corrected);
                    continue;
                }
                ForeignChannelGuard.Decision decision = ForeignChannelGuard.decide(item.id(), incoming,
                        healthId, absorptionId, authorityLocal, protectedPlayer, real);
                if (!decision.neutralize()) {
                    out.add(item);
                    continue;
                }
                if (clearMode) {
                    out.add(replacementValue(item, 0.0F));
                    changed = true;
                    ModLog.healthThrottled("PlayerDefense.entityDataChannel:" + player.getUUID()
                                    + ":" + item.id(), ModLog.TICK_INTERVAL,
                            layer + " 非白名单浮点项压回 0", player,
                            "id=" + item.id() + " 原值=" + incoming + " 判据=" + decision.reason());
                } else if (rejectForeign) {
                    // 不放进 out：等于从包里摘掉，客户端保持原值（拒绝写入）。
                    changed = true;
                    ModLog.healthThrottled("PlayerDefense.entityDataReject:" + player.getUUID()
                                    + ":" + item.id(), ModLog.TICK_INTERVAL,
                            layer + " 拒绝非白名单浮点项（包内摘除）", player,
                            "id=" + item.id() + " 原值=" + incoming + " 判据=" + decision.reason());
                } else {
                    out.add(item);
                    ModLog.healthThrottled("PlayerDefense.entityDataObserve:" + player.getUUID()
                                    + ":" + item.id(), ModLog.TICK_INTERVAL,
                            layer + " 观测到非白名单浮点项（observe，未改写）", player,
                            "id=" + item.id() + " 原值=" + incoming + " 判据=" + decision.reason());
                }
            }
            return changed ? out : items;
        } catch (Throwable t) {
            return items;
        }
    }

    /**
     * 客户端应用同步数据之前的过滤（{@code SynchedEntityData.assignValues}）。
     *
     * <p>为什么要单独一条：客户端收包走的是 {@code assignValues}，**不经过 {@code set}**，
     * 所以 {@code set} 上的写侧闸门对“服务端同步下来的私有 Float 通道”完全无效（日志实测：
     * 服务端写入已被拦住，客户端那个累加值仍会继续上涨）。
     *
     * <p>处理两类项：
     * <ul>
     *   <li>原版血量键 → 走既有纠正（非法值/变小都改回权威值）；</li>
     *   <li>非白名单浮点通道 → 与包级过滤共用同一份判据与同一种处理（observe 只观测 /
     *       clear 压回 0 / 硬拒包内摘除）。</li>
     * </ul>
     * <p><b>不再是"读取口被证伪才动手"</b>：旧判据 {@code READ_TAMPERED} 在"
     * 客户端 + agent 生效"这条路径上是死信号（置位点全部随方法体替换一起消失），
     * 只会在下一 tick 由 {@code ForeignChannelGuard} 兜底，留下一个 tick 的窗口。
     * 现在门控与包级完全一致：仅本地玩家 + 受保护 + 有权威 + 非白名单 + 非镜像。
     * 只在需要改写时复制列表，否则原列表返回。
     */
    // [DEFENSE-MARK] 防御：客户端 apply 同步数据前过滤私有累加通道与血量项。
    public static List<SynchedEntityData.DataValue<?>> sanitizeAssignedValues(
            Entity self, List<SynchedEntityData.DataValue<?>> entries) {
        if (!(self instanceof Player player) || entries == null || entries.isEmpty()) {
            return entries;
        }
        // 与包级过滤共用同一份判据（含 observe / 硬拒 / clear 三种处理），避免"包改了客户端没改"。
        return filterIncomingData(player, entries, "assignValues[层=客户端收包]");
    }

    /** 用同一个 id/serializer 造一个新数据项（泛型在运行时被擦除，这里按原始类型构造）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> replacementValue(
            SynchedEntityData.DataValue<?> item, float value) {
        return new SynchedEntityData.DataValue(item.id(), item.serializer(), value);
    }

    /** 单个血量项该取什么值；{@code authoritative} 为 null 表示没有旁路记录（典型是客户端）。 */
    private static float correctIncomingHealth(Player player, float incoming, Float authoritative) {
        if (ReadSideGuard.isIllegalReport(incoming)) {
            if (authoritative != null) {
                return authoritative;
            }
            float current = rawSyncedHealth(player);
            if (ReadSideGuard.isUsableRaw(current)) {
                return current;
            }
            float max = player.getMaxHealth();
            return ReadSideGuard.isUsableRaw(max) && max > 0.0F ? max : 0.0F;
        }
        if (authoritative != null && incoming < authoritative) {
            return authoritative;
        }
        return incoming;
    }

    private static void markReadTampered(Player player, float reported, float authoritative) {
        UUID id = player.getUUID();
        boolean first = READ_TAMPERED.add(id);
        ModLog.healthThrottled("PlayerDefense.readTampered:" + id, ModLog.TICK_INTERVAL,
                "读取侧自检[层=getHealth 消费端] 报告血量与内部权威值矛盾，强制返回内部值", player,
                "报告=" + reported + " 内部=" + authoritative
                        + (first ? "（首次判定：开始冻结可疑累加通道）" : ""));
    }

    /**
     * 私有 Float 同步通道防护 —— 不认 key 名字，只认行为模式。
     *
     * <p>背景：外部实现把一份外部私有状态存在一个<b>自己的</b> {@code EntityDataAccessor<Float>} 里，
     * 与原版 {@code DATA_HEALTH_ID} 完全无关。只认血量键的写侧拦截（{@code key != healthKey}
     * 直接放行）对这种通道完全失效，而它只增不减，越过 1 之后任何按它计算的读数都会是负数。
     *
     * <p>这里不猜 key 叫什么，只看两件事同时成立：
     * <ol>
     *   <li>该玩家<b>已被证明</b>读取口被篡改（{@link #READ_TAMPERED}，来自服务端
     *       {@link #tickReadHealth} 的实测矛盾，或 {@code forceGetHealth} 的取值矛盾）；</li>
     *   <li>这个非血量 Float 通道连续 {@value #SUSPICIOUS_INCREASES} 次写入只增不减。</li>
     * </ol>
     * 两者同时成立才冻结（拒绝本次递增写入），正常玩家的合法同步通道不会被动到；值回落时计数清零，
     * 读取口恢复后（{@link #tickReadHealth}）标记解除、冻结自动失效。
     *
     * @return true 表示应拒绝这次写入
     */
    // [DEFENSE-MARK] 防御：按行为模式冻结可疑的私有 Float 累加通道。
    public static boolean guardPrivateFloatChannel(Player player, EntityDataAccessor<?> key, float value) {
        // 默认关闭。实测：这条规则会命中“健康值本身”，把它清零 → 空血 / 立正 / 背包打不开，
        // 服务端写回 20 后恢复，下一次同步又清零 → 周期性复发。
        // 也就是说：在不知道外部私有通道语义的情况下，任何“按行为清零”都会误伤。
        // 需要重新试验时用 -Dstar_sword.privateChannelGuard=true 显式开启。
        if (!PRIVATE_CHANNEL_GUARD) {
            return false;
        }
        if (player == null || key == null) {
            return false;
        }
        // 按 id 排除原版血量键：外部实现的 accessor 可能是另一个实例（甚至同 id 的自定义键），
        // 只比对象身份会把血量键误判成“私有通道”。
        EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
        if (healthKey != null && key.getId() == healthKey.getId()) {
            return false;
        }
        if (authoritativeHealthOrNull(player) == null) {
            return false;
        }
        UUID id = player.getUUID();
        ReadSideGuard.ChannelFreezer freezer =
                PRIVATE_CHANNELS.computeIfAbsent(id, k -> new ReadSideGuard.ChannelFreezer(SUSPICIOUS_INCREASES));
        boolean suspicious = freezer.record(key, value);
        if (!suspicious || !READ_TAMPERED.contains(id)) {
            return false;
        }
        // 只拒绝本次写入；不再做任何“清零/清字段”——那已被实测证明会误伤健康值本身。
        ModLog.healthThrottled("PlayerDefense.privateChannel:" + id + ":"
                        + System.identityHashCode(key), ModLog.TICK_INTERVAL,
                "私有通道防护[层=SynchedEntityData.set] 拒绝只增不减的私有 Float 通道写入", player,
                "通道=" + key + " 本次写入=" + value + " 连续递增="
                        + freezer.increaseStreakOf(key));
        return true;
    }

    /**
     * 把可疑累加通道的当前值压回 0（字段直写 + 一次正常 set 以标记 dirty 同步给客户端）。
     *
     * <p>为什么必须归零而不是只拦写入：日志里外部实现的累加器在“已冻结”之后仍然只增不减
     * （写入走了别的路径），而按外部私有状态换算出的读数只要累加量停在 1 以上，
     * 客户端读到的就永远是负数、死亡界面就一直重开。归零是唯一不依赖注入点是否还在的手段。
     *
     * <p>限制：只在值已经 {@code > 1} 时动手（低于 1 不会算出负数），每 20 tick 最多一次，
     * 且只对“读取口已被证明篡改 + 白名单外通道”的玩家生效。
     */
    private static void neutralizePrivateChannel(Player player, EntityDataAccessor<?> key) {
        @SuppressWarnings("unchecked")
        EntityDataAccessor<Float> floatKey = (EntityDataAccessor<Float>) key;
        float stored = RawState.rawFloatOf(player, floatKey);
        if (Float.isNaN(stored) || stored <= 1.0F) {
            return;
        }
        // 不限频：外部实现的字段每 tick 都会把同步项写回来，必须跟它同频清零才压得住。
        RawState.writeRawFloat(player, floatKey, 0.0F);
        // 关键一步：把背后的 float/double 字段一起清零。
        // 只清数据项会被外部实现下一 tick 用字段值覆盖（日志实测：清零后仍会继续上涨）。
        int clearedFields = RawState.zeroMatchingFloatFields(player, stored);
        // 再走一次正常 set：标记 dirty，让客户端也收到 0（否则只有服务端的公式恢复正常）。
        SYNCED_WRITE_BYPASS.set(Boolean.TRUE);
        try {
            player.getEntityData().set(floatKey, 0.0F);
        } catch (Throwable ignored) {
        } finally {
            SYNCED_WRITE_BYPASS.set(Boolean.FALSE);
        }
        ModLog.health("私有通道防护 已把可疑累加通道压回 0", player,
                "通道=" + key + " 原值=" + stored + " 同时清零浮点字段数=" + clearedFields);
    }

    /** 每 tick 复查读取口是否已恢复；连续正常 {@value #READ_CLEAR_TICKS} tick 后解除冻结。 */
    private static void tickReadHealth(Player player) {
        UUID id = player.getUUID();
        boolean contradict;
        float reported;
        // 必须绕过本模组自己的读取钩子，否则永远只能读到被我们纠正后的 20，取证失效。
        reported = probeRawReportedHealth(player);
        contradict = Float.isNaN(reported) || Float.isInfinite(reported) || reported < 0.0F;
        if (contradict) {
            READ_CLEAN_TICKS.remove(id);
            Float real = authoritativeHealthOrNull(player);
            if (real != null && !READ_TAMPERED.contains(id)) {
                markReadTampered(player, reported, real);
            }
            return;
        }
        if (!READ_TAMPERED.contains(id)) {
            return;
        }
        int clean = READ_CLEAN_TICKS.merge(id, 1, Integer::sum);
        if (clean >= READ_CLEAR_TICKS) {
            READ_TAMPERED.remove(id);
            PRIVATE_CHANNELS.remove(id);
            READ_CLEAN_TICKS.remove(id);
            ModLog.health("读取侧自检 读取口已恢复正常，解除私有通道冻结", player,
                    "连续正常tick=" + clean);
        }
    }

    private static void clearReadState(UUID id) {
        READ_TAMPERED.remove(id);
        PRIVATE_CHANNELS.remove(id);
        READ_CLEAN_TICKS.remove(id);
    }

    /** 当前线程是否正处于“原始读值探测”中；读取侧钩子据此跳过纠正。 */
    public static boolean isRawReadProbe() {
        return Boolean.TRUE.equals(RAW_READ_PROBE.get());
    }

    /**
     * 探测一次“未经本模组纠正”的 {@code getHealth()} 返回值。
     *
     * <p>只有绕过自己的钩子才能看到被替换的方法体到底返回什么：HEAD 提前 return 和
     * RETURN 改写都会掩盖证据，所以探测期间三处钩子全部放行。异常按“矛盾”处理。
     */
    private static float probeRawReportedHealth(Player player) {
        RAW_READ_PROBE.set(Boolean.TRUE);
        try {
            return player.getHealth();
        } catch (Throwable t) {
            return Float.NaN;
        } finally {
            RAW_READ_PROBE.set(Boolean.FALSE);
        }
    }

    // ===================== 持久化补校验 =====================

    /**
     * {@code LivingEntity.addAdditionalSaveData} 之后：把即将落盘的 {@code Health} 改写成权威值。
     *
     * <p>外部实现把 {@code getHealth()} 换成返回负数后，原版存档会把负数写进 {@code Health}：
     * 服务端读口赢了也没用，重登照样是负数。这里在存档方法返回后直接改写 tag，不经过任何 getter，
     * 是 agent 未附加时（{@code AgentHooks.beforeSave} 缺席）的等价兜底。
     */
    // [DEFENSE-MARK] 防御：存档落盘前把 Health 改写成权威值。
    public static void afterVanillaSave(LivingEntity self, CompoundTag tag) {
        if (!(self instanceof Player player) || tag == null) {
            return;
        }
        Float real = authoritativeHealthOrNull(player);
        if (real == null) {
            return;
        }
        float written = tag.getFloat("Health");
        if (written == real && tag.getShort("DeathTime") == 0) {
            return;
        }
        // 加非负校验：内部权威值本身必须合法，才允许覆盖落盘内容。
        if (real < 0.0F) {
            return;
        }
        tag.putFloat("Health", real);
        tag.putShort("DeathTime", (short) 0);
        ModLog.healthThrottled("PlayerDefense.afterVanillaSave:" + player.getUUID(), ModLog.TICK_INTERVAL,
                "存档前修正[层=addAdditionalSaveData] 改写落盘血量", player,
                "写入=" + written + " -> " + real);
    }

    /**
     * {@code LivingEntity.readAdditionalSaveData} 之后：修复已经落盘的负数/死亡存档。
     *
     * <p>只修“Health &lt;= 0 或 DeathTime != 0”这类明确污染的存档；此时旁路记录可能尚未从
     * PersistentData 载入（登录事件在后），所以优先用旁路值，没有则用最大血量兜底。
     */
    // [DEFENSE-MARK] 防御：读取存档后修复污染值。
    public static void repairPersistedHealth(LivingEntity self, CompoundTag tag) {
        if (!(self instanceof Player player) || tag == null) {
            return;
        }
        float stored = tag.contains("Health") ? tag.getFloat("Health") : Float.NaN;
        short deathTime = tag.getShort("DeathTime");
        boolean broken = Float.isNaN(stored) || Float.isInfinite(stored) || stored <= 0.0F || deathTime != 0;
        if (!broken) {
            return;
        }
        Float real = authoritativeHealthOrNull(player);
        float repaired;
        if (real != null) {
            repaired = real;
        } else {
            float max = player.getMaxHealth();
            repaired = (Float.isNaN(max) || Float.isInfinite(max) || max <= 0.0F) ? 1.0F : max;
        }
        tag.putFloat("Health", repaired);
        tag.putShort("DeathTime", (short) 0);
        ModLog.health("存档修复[层=readAdditionalSaveData] 落盘血量非法，改写为合法值", player,
                "原Health=" + stored + " 原DeathTime=" + deathTime + " -> " + repaired);
    }

    /** 登录 / 克隆后主动向客户端补发一次旁路血量（不受 20 tick 限频）。 */
    // [DEFENSE-MARK] 防御：登录/克隆后主动补发一次旁路血量。
    public static void syncRealHealth(ServerPlayer player) {
        if (player == null || !SwordGuard.isProtected(player)) {
            return;
        }
        LAST_SYNC_TICK.put(player.getUUID(), player.tickCount);
        ModMessages.sendRealHealthSync(player, BYPASS_HEALTH, true);
        ModLog.health("PlayerDefense.syncRealHealth 登录/克隆后补发旁路真实血量", player,
                "值=" + BYPASS_HEALTH);
    }

    /** 限频向客户端同步旁路血量；{@code active=false} 时立即发一次清除包。 */
    // [DEFENSE-MARK] 防御：限频向客户端同步旁路血量。
    private static void syncToClient(Player player, float value, boolean active) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (active) {
            int tick = player.tickCount;
            Integer last = LAST_SYNC_TICK.get(player.getUUID());
            if (last != null && tick - last < SYNC_INTERVAL_TICKS) {
                return;
            }
            LAST_SYNC_TICK.put(player.getUUID(), tick);
        }
        ModMessages.sendRealHealthSync(serverPlayer, value, active);
    }

    // ---- 客户端待处理同步槽（由 SyncRealHealthPacket 写入、客户端 tick 消费）----

    private static volatile boolean HAS_PENDING_CLIENT_SYNC;
    private static volatile boolean PENDING_CLIENT_ACTIVE;
    private static volatile float PENDING_CLIENT_HEALTH;

    /** 客户端收到服务端同步包时暂存（不引用任何客户端类）。 */
    // [DEFENSE-MARK] 防御：客户端暂存服务端同步的旁路血量。
    public static void setPendingClientRealHealth(float health, boolean active) {
        PENDING_CLIENT_HEALTH = health;
        PENDING_CLIENT_ACTIVE = active;
        HAS_PENDING_CLIENT_SYNC = true;
    }

    /** 客户端 tick 调用：把暂存的同步值写入旁路并刷新本地血量。 */
    // [DEFENSE-MARK] 防御：客户端 tick 消费暂存的旁路血量并刷新本地血量。
    public static void applyPendingClientRealHealth(Player player) {
        if (!HAS_PENDING_CLIENT_SYNC || player == null) {
            return;
        }
        HAS_PENDING_CLIENT_SYNC = false;
        if (PENDING_CLIENT_ACTIVE) {
            acceptSyncedRealHealth(player, PENDING_CLIENT_HEALTH);
        } else {
            clearSyncedRealHealth(player);
        }
    }

    // ===================== 强制复活 =====================

    /**
     * 强制清除玩家的死亡状态并回满血（{@code ModForgeEvents.onLivingDeath} 取消死亡事件后调用）。
     *
     * <p>取消 {@code LivingDeathEvent} 只挡掉 {@code die()} 里事件判定之后的分支；伤害管道或
     * 其它模组可能已经留下 {@code dead} 标志、受伤动画、着火、窒息值等。这里逐项清理：
     * <ul>
     *   <li>{@code dead=false}：1.20.1 无 {@code Player.revive()}，字段为 protected，走反射；</li>
     *   <li>{@code getCombatTracker().recheckStatus()}：1.20.1 无 {@code reset()}，用原版等价清理；</li>
     *   <li>{@code clearFire()} / {@code setAirSupply(300)} / {@code hurtTime=0} / {@code hurtDuration=0}；</li>
     *   <li>回满血：先写旁路权威值，再按需求 {@code setHealth(getMaxHealth())}；</li>
     *   <li>若已进入 KILLED/DISCARDED 移除状态，立即还原 {@code removalReason=null}。</li>
     * </ul>
     */
    // [REVIVE-MARK] 复活：强制清除死亡状态并回满血（死亡事件取消后调用）。
    public static void forceRevive(Player player) {
        if (player == null) {
            return;
        }
        try {
            resetDeadFlag(player);
            resetDeathTime(player);
            player.getCombatTracker().recheckStatus();
            player.clearFire();
            player.setAirSupply(300);
            player.hurtTime = 0;
            player.hurtDuration = 0;
        } catch (Throwable t) {
            ModLog.health("forceRevive[层=强制复活] 清理死亡状态时异常", player, "异常=" + t);
        }
        // 旁路权威值置满，再走一次 setHealth(getMaxHealth())（会被 filterHealthWrite 归一为旁路值）。
        acceptSyncedRealHealth(player, BYPASS_HEALTH);
        try {
            player.setHealth(player.getMaxHealth());
        } catch (Throwable ignored) {
        }
        // 白名单内的理由（跨维度 / 退服）不动；白名单外的（KILLED / DISCARDED / 未知）一律清掉。
        Entity.RemovalReason reason = readRemovalReason(player);
        if (reason != null && !isRemovalAllowed(player, reason)) {
            writeRemovalReason(player, null);
        }
        ModLog.defense("forceRevive[层=强制复活] 已清除死亡状态并回满血", player,
                "maxHealth=" + player.getMaxHealth());
    }

    /**
     * 客户端收到 {@code ClientboundPlayerCombatKillPacket} 时立即清掉本地玩家的死亡态，
     * 避免 {@code LocalPlayer} 自认为已死（需求：setHealth(max) / dead=false / deathTime=0 /
     * hurtTime=0 / hurtDuration=0 / setScreen(null)）。
     *
     * <p>{@code dead} 是 protected 且 SRG {@code f_20890_}，客户端 Mixin 无法直接写，走反射；
     * {@code deathTime}/{@code hurtTime}/{@code hurtDuration} 是 public 直接置 0。
     * 血量走 {@code setHealth(getMaxHealth())}，受保护玩家会被 {@code filterHealthWrite}
     * 归一为旁路真实血量。关屏由调用方走 {@code ModClientForgeEvents.safeCloseScreen}：
     * 死亡态下裸调 {@code setScreen(null)} 会被 Forge 换成 DeathScreen 并再次 post Opening，
     * 和 Opening handler 形成自递归。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：客户端收到击杀包后清除本地死亡态。
    public static void clientClearDeathState(Player player) {
        if (player == null) {
            return;
        }
        try {
            resetDeadFlag(player);
            player.deathTime = 0;
            player.hurtTime = 0;
            player.hurtDuration = 0;
            player.setHealth(player.getMaxHealth());
        } catch (Throwable t) {
            ModLog.health("clientClearDeathState[层=客户端死亡包] 清理本地死亡态异常", player, "异常=" + t);
        }
    }

    /**
     * 客户端每 tick 兜底（需求③）：{@code LivingEntity.tick()} 每 tick 先
     * {@code if (isDeadOrDying()) tickDeath()}。只恢复血量不够——只要 {@code deathTime} 还在累加，
     * 死亡姿势/移动就会卡住。这里在血量为 0 / 正在死亡计时 / {@code dead} 标志为 true 时，
     * 把本地玩家的死亡字段整体恢复（{@link #clientClearDeathState}）。
     *
     * <p>调用方（{@code ModClientForgeEvents.onClientTick}）已用 {@link SwordGuard#isProtected} 门控，
     * 方向 B 的“未知即暂保”期间也会生效。
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：客户端每 tick 兜底恢复死亡字段。
    public static void clientRecoverDeathStateIfNeeded(Player player) {
        if (player == null) {
            return;
        }
        try {
            // 客户端也要装代理：否则外部实现对血量数据项的直写会绕过裁决（这正是「只有客户端算错」的入口）。
            HealthDataItemInstaller.ensureInstalled(player);
            // 读数据项而不是 getHealth：被替换的方法体在客户端同样生效，而我们的读取钩子
            // 未必还活着（transform 顺序不由我们决定）。数据项读不到时才退回 getHealth。
            // 注意：必须走 HealthAuthority.read（带"非法读数"裁决）。直接读原始值会把
            // 算式写进来的负数当成血量，`<= 0` 立刻判成死亡——负血与零血在死亡语义上等价。
            float health = HealthAuthority.read(player);
            if (Float.isNaN(health)) {
                health = player.getHealth();
            }
            // 需求③：每 tick 执行，直到 deadOrDying 为 false。health<=0 / deathTime>0 / dead 标志
            // 任一为真都视为“仍处于死亡态”，下一 tick 会再判定一次，直到三者全部复位。
            boolean dying = health <= 0.0F || player.deathTime > 0 || isDeadFlag(player);
            if (dying) {
                int before = player.deathTime;
                // 强制重置 dead 标志（clientClearDeathState 内用反射写 LivingEntity.dead）并回血。
                // “恢复 input”由客户端每 tick 的 ModClientForgeEvents.forceClientControlRecovery 负责：
                // 本类是 common 类，禁止直接引用 net.minecraft.client.*，否则专用服加载会崩。
                clientClearDeathState(player);
                // 再显式兜底一次死亡计时字段（等价需求里的 f_20919_/f_20916_/f_20917_），
                // 保证即使反射重置 dead 失败也不会残留死亡动画。
                player.deathTime = 0;
                player.hurtTime = 0;
                player.hurtDuration = 0;
                int clears = DEATH_CLEARS.merge(player.getUUID(), 1, Integer::sum);
                // 日志打「清零后」的值：以前打的是清零前的值，于是每个窗口都显示 deathTime=1，
                // 看起来像没修好。现在同时给出本窗口清零次数——持续 >1 说明还有写者在推进它。
                ModLog.defenseThrottled("PlayerDefense.clientRecover:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "clientRecoverDeathStateIfNeeded[层=客户端每tick兜底] 恢复被覆盖的死亡字段",
                        player, "health=" + health + " 原deathTime=" + before + " -> "
                                + player.deathTime + " 本窗口清零=" + clears + "次");
                DEATH_CLEARS.put(player.getUUID(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 取消死亡计时并清零死亡字段（供 {@code LocalPlayer.tickDeath} 覆写处的拦截调用）。
     *
     * <p>为什么需要单独一个入口：{@code LocalPlayer} <b>覆写</b>了 {@code LivingEntity.tickDeath()}，
     * 而 mixin 只织入父类方法——客户端这条覆写完全不受 {@code LivingEntityMixin} 管辖。它每 tick
     * {@code ++deathTime}，我们每 tick 再清零，日志里就是"deathTime 恒为 1"的拉锯。
     * 只清零字段、不碰血量：血量由状态锁/权威存储负责，这里再写一次会造成两条写者。
     *
     * @return true 表示该玩家处于防御态、已取消并清零
     */
    // [DEFENSE-MARK] 防御 / [REVIVE-MARK] 复活：取消 LocalPlayer.tickDeath 的死亡计时。
    public static boolean clearDeathFieldsIfProtected(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (!SwordGuard.isProtected(player)) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        try {
            int before = player.deathTime;
            resetDeadFlag(player);
            player.deathTime = 0;
            player.hurtTime = 0;
            player.hurtDuration = 0;
            ModLog.defenseThrottled("PlayerDefense.localTickDeath:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "tickDeath[层=LocalPlayer 覆写] 取消死亡计时并清零死亡字段", player,
                    "原deathTime=" + before + " -> 0");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 客户端在方向 B 的“未知即暂保”窗口里可能已阻止死亡/覆盖血量；服务端随后广播
     * {@code protected=false} 时调用本方法清理这些被错误覆盖的状态：清除旁路记录并归零死亡字段。
     * 之后本模组在客户端不再介入，原版死亡/血量包行为恢复。
     */
    // [DEFENSE-MARK] 防御：方向 B 清理，服务端判定不受保护后清掉临时保护残留。
    public static void clearClientProtectionArtifacts(Player player) {
        if (player == null) {
            return;
        }
        clearSyncedRealHealth(player);
        try {
            resetDeadFlag(player);
            player.deathTime = 0;
            player.hurtTime = 0;
            player.hurtDuration = 0;
        } catch (Throwable ignored) {
        }
    }

    private static Field deadField() {
        if (!deadResolved) {
            synchronized (PlayerDefense.class) {
                if (!deadResolved) {
                    deadField = ReflectionHelper.findFieldCompat(
                            LivingEntity.class, "f_20890_", "dead");
                    deadResolved = true;
                }
            }
        }
        return deadField;
    }

    private static void resetDeadFlag(Player player) {
        Field field = deadField();
        if (field == null) {
            return;
        }
        try {
            field.setBoolean(player, false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把 {@code deathTime} 归零。
     *
     * <p>它和 {@code dead} 不是一回事：{@code deathTime} 是普通 int 字段、**不在同步项里**（{@code SynchedEntityData}
     * 里没有它），所以任何"给某个通道加拦截"的做法从载体上就碰不到它；只能按字段白名单显式写 0。
     * 只写这一个字段、只写 0，不做"名字像时间就清零"的扫描——那种一刀切会把无关状态（旋转、计时器、
     * 网络 id）一起清掉，代价远大于收益。
     */
    private static void resetDeathTime(Player player) {
        try {
            Field field = ReflectionHelper.findFieldCompat(
                    net.minecraft.world.entity.LivingEntity.class, "f_20919_", "deathTime");
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            field.setInt(player, 0);
        } catch (Throwable ignored) {
            // 反射失败不影响其它清理项
        }
    }

    // ===================== Mixin 回调入口 =====================

    /** 方向二：{@code LivingEntityMixin} 在 getHealth() 返回路径把返回值锁定为旁路真实血量。 */
    // [DEFENSE-MARK] 防御：getHealth RETURN 锁定为旁路真实血量。
    public static float forceGetHealth(LivingEntity self, float returned) {
        if (isRawReadProbe()) {
            // 探测中：放行原始返回值，否则永远看不到被替换的方法体给出的真实读数。
            return returned;
        }
        if (!(self instanceof Player player)) {
            return returned;
        }
        // 有旁路记录时以它为准；没有时退回原版数据项（客户端没有服务端静态 Map 时的唯一权威来源）。
        // 原版同步数据的权威值由 serverTick 与 LivingEntityMixin 的 tick HEAD/RETURN 强制写回，
        // 服务端非受保护玩家的正常死亡（getHealth()==0）仍会原样返回 0，不会永远死不掉。
        Float real = REAL_HEALTH.get(player.getUUID());
        boolean retired = RETIRED.contains(player.getUUID());
        if (real != null && !Float.isNaN(real) && !Float.isInfinite(real) && !retired) {
            if (real != returned) {
                // 每 tick 会被 getHealth() 调用很多次：正常生效时限频 40t 输出一次，避免刷屏。
                ModLog.healthThrottled("PlayerDefense.forceGetHealth.hit:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "forceGetHealth[层=getHealth RETURN] 锁定返回值为旁路真实血量", player,
                        "底层返回=" + returned + " -> " + real);
            }
            if (Float.isNaN(returned) || Float.isInfinite(returned) || returned < 0.0F) {
                // 底层返回负数 = 读取口已被整体替换（外部实现把方法体换掉了）。
                // 记录这个事实，从而允许 guardPrivateFloatChannel 冻结可疑累加通道。
                if (READ_TAMPERED.add(player.getUUID())) {
                    ModLog.health("读取侧自检[层=getHealth RETURN] 底层返回非法值，判定读取口已被替换",
                            player, "底层返回=" + returned + " 内部=" + real);
                }
            }
            return real;
        }
        // 无旁路记录 —— 客户端就是这种情况：REAL_HEALTH 是服务端状态，客户端这份可能为空。
        // 外部实现改写的是 getHealth() 的方法体，DATA_HEALTH_ID 这个数据项没被动过：
        // 客户端收到的数据项就是服务端同步下来的真值，直接拿它兜底。
        // 这里刻意不依赖 isProtected：客户端那个状态来自服务端广播，赶不上这次读取。
        float raw = rawSyncedHealth(player);
        if (raw > 0.0F) {
            if (raw != returned) {
                ModLog.healthThrottled("PlayerDefense.forceGetHealth.raw:" + player.getUUID(),
                        ModLog.TICK_INTERVAL,
                        "forceGetHealth[层=getHealth RETURN] 无旁路记录，改用原版数据项", player,
                        "底层返回=" + returned + " 数据项=" + raw);
            }
            return raw;
        }
        // 数据项也是 0（确实死了）或读不到：原样放行，保留原版死亡流程。
        // 客户端兜底：受保护玩家在服务端同步包到达前，绝不返回负值/扣血值，至少返回最大血量，
        // 否则客户端会因其它模组的扣血包显示残血甚至触发死亡界面。服务端不受此兜底影响
        // （服务端非受保护玩家的 getHealth()==0 必须原样返回，否则永远死不掉）。
        try {
            if (!retired && player.level().isClientSide && SwordGuard.isProtected(player)) {
                float max = player.getMaxHealth();
                if (!Float.isNaN(max) && !Float.isInfinite(max) && max > 0.0F && returned < max) {
                    ModLog.healthThrottled("PlayerDefense.forceGetHealth.clientFallback:" + player.getUUID(),
                            ModLog.TICK_INTERVAL,
                            "forceGetHealth[层=getHealth RETURN] 客户端受保护但无旁路记录，兜底返回最大血量",
                            player, "底层返回=" + returned + " -> " + max);
                    return max;
                }
            }
        } catch (Throwable ignored) {
        }
        ModLog.healthThrottled("PlayerDefense.forceGetHealth.miss:" + player.getUUID(),
                ModLog.TICK_INTERVAL,
                "forceGetHealth[层=getHealth RETURN] 无旁路记录且数据项不可用，原样放行", player,
                "原返回值=" + returned + " 数据项=" + raw);
        return returned;
    }

    /** 方向三：{@code EntityIsRemovedMixin} 在 isRemoved() 返回路径压制被劫持的移除状态。 */
    // [DEFENSE-MARK] 防御：isRemoved RETURN 压制白名单之外的移除状态。
    public static boolean forceNotRemoved(Entity self, boolean returned) {
        if (!returned || !(self instanceof Player player)) {
            return returned;
        }
        Float real = REAL_HEALTH.get(player.getUUID());
        if (real == null || real <= 0.0F || RETIRED.contains(player.getUUID())) {
            return returned;
        }
        Entity.RemovalReason reason = readRemovalReason(player);
        if (reason == null) {
            return returned;
        }
        if (!isRemovalAllowed(player, reason)) {
            EntityIntegrityProbe.noteSuppressed(player);
            ModLog.defenseThrottled("PlayerDefense.forceNotRemoved.hit:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "forceNotRemoved[层=isRemoved RETURN] 压制 isRemoved()=true", player,
                    "removalReason=" + reason);
            return false;
        }
        ModLog.defenseThrottled("PlayerDefense.forceNotRemoved.fallback:" + player.getUUID(),
                ModLog.TICK_INTERVAL,
                "forceNotRemoved[层=isRemoved RETURN] 返回 true 但理由在白名单内，不压制",
                player, "removalReason=" + reason);
        return returned;
    }

    // ===================== 移除理由白名单 =====================

    /**
     * 该实体是否就是当前会话里的那个玩家。
     *
     * <p>重生（{@code PlayerList.respawn}）与重复登录清理（{@code ServerLevel.addPlayer}）都会在
     * 移除<b>旧</b>实体之前先把它从 {@code PlayerList} 摘掉（或还没加进去），因此“不在会话里”
     * 就说明这是原版在收拾现场，不该拦。
     */
    private static boolean isLiveSessionPlayer(Entity entity) {
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        try {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return false;
            }
            return server.getPlayerList().getPlayer(player.getUUID()) == player;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 移除理由白名单：默认拒绝，只放行原版必需的 {@code UNLOADED_WITH_PLAYER}（退服/踢出）与
     * {@code CHANGED_DIMENSION}（传送门/跨维度）；不是当前会话实体时一律放行（原版清理旧实体）。
     *
     * <p>判定逻辑在 {@link RemovalPolicy}（纯函数，可离线验证）。
     */
    // [DEFENSE-MARK] 防御：移除理由白名单。
    public static boolean isRemovalAllowed(Entity self, Entity.RemovalReason reason) {
        return RemovalPolicy.isAllowed(
                RemovalPolicy.reasonOf(reason == null ? null : reason.name()),
                isLiveSessionPlayer(self));
    }

    /**
     * 该玩家的权威血量是否已「退役」（保护到期时保留记录但标记退役，见 {@link #onProtectionExpired}）。
     *
     * <p>暴露给 {@code hijack.authority} 用：权威存储本身不知道退役语义，若它只看「记录存在」就会让
     * 退役值无限期权威——表现为血量 0 却不死、deathTime 冻结。判据必须只有一处。
     */
    public static boolean isRetired(UUID id) {
        try {
            return id != null && RETIRED.contains(id);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 当前是否处于「应当拒绝非常规移除」的防御态（有权威血量，或仍被判定为受保护）。 */
    // [DEFENSE-MARK] 防御：移除拦截的适用对象判定。
    public static boolean isRemovalGuarded(Player player) {
        if (player == null) {
            return false;
        }
        if (hasAuthoritativeHealth(player)) {
            return true;
        }
        try {
            return SwordGuard.isProtected(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 拦截 {@code SynchedEntityData.set}：受保护玩家血量数据键上的非法值 / 减血写入直接拒绝。
     *
     * <p>由 {@code SynchedEntityDataMixin} 在 {@code set} HEAD 调用（{@code ci.cancel()}）。
     * 判定条件（与需求一致）：实体是受保护玩家、写入的是血量数据键、值类型为 Float，
     * 且值非 NaN/无限/负数（非法血量）或小于旁路真实血量（想减血）。
     *
     * <p>只认血量数据键（{@link ReflectionHelper#healthDataAccessor()}）而不是拦截所有 Float 写入：
     * 玩家的吸收量等其它同步 Float 数据值可能小于当前血量，全拦会误伤正常同步。
     * 本模组自己的 {@link #forceWriteVanillaHealth} 通过 {@link #SYNCED_WRITE_BYPASS} 放行。
     */
    // [DEFENSE-MARK] 防御：拦截 SynchedEntityData.set 的非法 / 减血写入。
    public static boolean shouldBlockSynchedWrite(Entity self, EntityDataAccessor<?> key, Object value) {
        if (Boolean.TRUE.equals(SYNCED_WRITE_BYPASS.get())) {
            return false;
        }
        if (!(self instanceof Player player) || !(value instanceof Float f)) {
            return false;
        }
        // 状态锁硬拒：受保护期间，「状态」只承认我们自己的写回。任何其它来源（原版减血、外部模组、
        // 对手的 transformer 直调 set）写进来的 Float 一律不落地——这是"不再兼容别人状态语义"的
        // 落地方式；读侧仍然只认权威值，所以拒绝写入不会让读数卡在旧值上。
        if (rejectStateWrite(player, key, value)) {
            return true;
        }
        EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
        if (healthKey == null) {
            // 静默失败点：原版血量数据键解析不出来时，这里根本认不出“哪个 key 是血量”，拦截形同虚设。
            ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.nullKey",
                    ModLog.TICK_INTERVAL,
                    "shouldBlockSynchedWrite[层=SynchedEntityData.set] 血量数据键解析失败，无法判断是否拦截",
                    player, "key=" + key + " 值=" + f);
            return false;
        }
        if (key != healthKey) {
            // 非原版血量键：不认 key 名字，按“只增不减的私有 Float 通道”这一行为模式判断。
            // 只有该玩家的读取口已被证明篡改时才会真的冻结（见 guardPrivateFloatChannel），
            // 正常玩家的吸收量等合法同步通道不受影响。放在 isProtected 判定之前，
            // 是因为外部实现根本不看保护状态机，这里也不能把保护状态当作门槛。
            boolean blocked = guardPrivateFloatChannel(player, key, f);
            // 诊断：把每个非血量 Float 通道的 id/值打出来（100t 限频），用于确认运行时
            // 到底哪个 id 是血量、哪个是外部实现的累加器（启动横幅里也会打印血量键 id）。
            //
            // 这一行必须带「通道身份」而不只是 id：id 会随其它模组的 defineId 数量整体漂移，
            // 只看数字无法证明"这一项不是 DATA_HEALTH_ID 注册的那一项"。身份判据是 accessor 对象比较，
            // 再加上 serializer 类型与当前原始值，足够判断"是不是血量语义的通道"。
            String serializer = RawState.serializerName(key);
            EntityDataAccessor<?> absorptionKey = ForeignChannelGuard.absorptionAccessor();
            ModLog.healthThrottled("PlayerDefense.channelProbe:" + key.getId(), 100,
                    "通道探测[层=SynchedEntityData.set] 非血量 Float 通道写入", player,
                    "id=" + key.getId() + " 值=" + f + " 拦截=" + blocked
                            + " 实体=" + player.getClass().getSimpleName()
                            + " 类型=" + serializer
                            + " accessor=#" + System.identityHashCode(key)
                            + " 血量accessor=#" + System.identityHashCode(healthKey)
                            + " 是血量键=" + (key == healthKey)
                            + " 是吸收键=" + (absorptionKey != null && key == absorptionKey)
                            + " raw=" + currentRawFloat(player, key)
                            + " 血量键id=" + healthKey.getId());
            return blocked;
        }
        try {
            // 判据从「受保护」扩到「存在有效权威记录」：数据项代理会把低于锁定值的写入钳掉，
            // 但被拒绝的写入仍会走到 set() 尾部点脏（每 tick 一个包）。在 HEAD 直接 cancel 才是干净的。
            if (!SwordGuard.isProtected(player) && !HealthAuthority.isAuthoritative(player.getUUID())) {
                return false;
            }
        } catch (Throwable t) {
            ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.guardThrow:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "shouldBlockSynchedWrite[层=SynchedEntityData.set] isProtected 判定抛异常，放行",
                    player, "异常=" + t);
            return false;
        }
        UUID id = player.getUUID();
        Float real = REAL_HEALTH.get(id);
        boolean hasRecord = real != null && !Float.isNaN(real) && !Float.isInfinite(real)
                && real >= 0.0F && !RETIRED.contains(id);
        if (!hasRecord) {
            // 需求：前提供打破时不再无条件放行。受保护玩家却没有有效的旁路记录时，
            // 用“当前血量”兜底，拒绝非法值与低于当前血量的减血写入。
            float current = currentHealth(player);
            if (Float.isNaN(f) || Float.isInfinite(f) || f < 0.0F) {
                ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.noBypassIllegal:" + id,
                        ModLog.TICK_INTERVAL,
                        "shouldBlockSynchedWrite[层=SynchedEntityData.set] 无有效旁路，拒绝非法血量写入",
                        player, "值=" + f);
                return true;
            }
            if (f < current) {
                ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.noBypassLose:" + id,
                        ModLog.TICK_INTERVAL,
                        "shouldBlockSynchedWrite[层=SynchedEntityData.set] 无有效旁路，用当前血量兜底拒绝减血",
                        player, "值=" + f + " 当前血量=" + current);
                return true;
            }
            return false;
        }
        if (Float.isNaN(f) || Float.isInfinite(f) || f < 0.0F) {
            // 每 tick 可能被其它模组反复写入，限频输出；这是“被拒绝”的真实拦截，不是噪声。
            ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.illegal:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "shouldBlockSynchedWrite[层=SynchedEntityData.set] 拒绝非法血量写入", player,
                    "值=" + f);
            return true;
        }
        if (f < real) {
            ModLog.healthThrottled("PlayerDefense.shouldBlockSynchedWrite.lose:" + player.getUUID(),
                    ModLog.TICK_INTERVAL,
                    "shouldBlockSynchedWrite[层=SynchedEntityData.set] 拒绝减血写入", player,
                    "值=" + f + " 旁路真实血量=" + real);
            return true;
        }
        return false;
    }

    /**
     * 状态锁的写侧硬拒判据：<b>唯一实现</b>，mixin 与 agent 两条执行路径共用。
     *
     * <p>为什么必须共用：agent 生效时 mixin 会按"执行者唯一"让位（见 {@code SynchedEntityDataMixin}），
     * 如果硬拒只写在 mixin 调的那个方法里，正式环境（agent 常驻）的拦截就静默消失了。反过来也一样。
     *
     * <p>热路径只做一次 map 查表 + 一次原子自增：对手的写入实测可以超过 100 次/秒，逐条打日志会把
     * 拦截本身变成瓶颈。日志由 {@link #flushRejectedWrites} 在 tick 里汇总。
     *
     * @return true 表示这次写入应当被拒绝
     */
    public static boolean rejectStateWrite(Player player, EntityDataAccessor<?> key, Object value) {
        if (player == null || key == null || !(value instanceof Float f)) {
            return false;
        }
        if (!VanillaStateLock.rejectStateWrites()) {
            return false;
        }
        try {
            if (SwordGuard.isProtected(player) || HealthAuthority.isAuthoritative(player.getUUID())) {
                // 值没变 = 不改变状态，放行且不计数。
                //
                // 这一条是实测加的：filterHealthWrite 归一后返回 20.0，随后 setHealth 再走一次
                // SynchedEntityData.set(20.0)。如果不排除，我们每 2 秒窗口会把自己拒掉 120~171 次
                // （UOM 实测），日志与计数被这类"空写"淹没，真正来自对手的写入反而看不出来。
                if (isNoOpStateWrite(player, key, f)) {
                    return false;
                }
                REJECTED_WRITES.computeIfAbsent(player.getUUID(), k -> new RejectedWrites())
                        .note(key.getId(), f);
                return true;
            }
        } catch (Throwable ignored) {
            // 判定失败时放行，交给调用方后面的既有逻辑
        }
        return false;
    }

    /**
     * 这次写入是不是「空写」——写了也不会改变任何状态，因此既不该拒绝也不该计数。
     *
     * <p>两条判据，任一成立即空写：
     * <ol>
     *   <li><b>等于权威血量</b>：{@code filterHealthWrite} 归一后的返回值正是权威值，
     *       这类写入占了实测拒绝量的 99%（UOM 每 2 秒窗口 120~171 次）；</li>
     *   <li><b>等于该通道当前原始值</b>：非血量通道的重复写入。</li>
     * </ol>
     *
     * <p>为什么不能只靠第 2 条：血量数据项被换成了代理项，{@code RawState.rawFloatOf} 读到的是代理里
     * 的存储值，可能与"权威值"不是同一个数（实测就是这样，导致第 2 条兜不住，120+ 次空写仍被计数）。
     */
    private static boolean isNoOpStateWrite(Player player, EntityDataAccessor<?> key, float value) {
        try {
            if (HealthAuthority.isAuthoritative(player.getUUID())) {
                float authoritative = HealthAuthority.read(player);
                if (!Float.isNaN(authoritative) && authoritative == value) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到权威值：继续用原始值判定
        }
        float current = currentRawFloat(player, key);
        return !Float.isNaN(current) && current == value;
    }

    /** 读该通道当前的原始值；读不到返回 {@code NaN}（调用方据此不做“空写”判定）。 */
    private static float currentRawFloat(Player player, EntityDataAccessor<?> key) {
        try {
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> floatKey = (EntityDataAccessor<Float>) key;
            return RawState.rawFloatOf(player, floatKey);
        } catch (Throwable ignored) {
            // 读不到就当作“值未知”
            return Float.NaN;
        }
    }

    /**
     * 把「状态锁这一窗口拒绝了哪些写入」按通道汇总成日志。
     *
     * <p>由每 tick 的防御路径调用（服务端 {@link #serverTick} / 客户端 tick），因此每帧只有一次
     * map 查找 + 一次 {@code isEmpty} 判断，开销与对手的写入频率无关——这正是"写入可以超过
     * 100 次/秒"的前提；排序与 top-N 只在这个日志节拍里做。
     *
     * <p>两行输出：
     * <ol>
     *   <li><b>该通道第一次被拒</b>时单独一行（每通道每会话一次）。这就是硬拒提前 return 之后被遮蔽掉的
     *       「通道探测」的替代品：只要出现过，就一定能看到它的 id 和值；</li>
     *   <li><b>窗口汇总</b>一行：总次数 + top-N 通道（按次数降序，各带最近一次的值），
     *       其余折叠成"共 K 条通道"。</li>
     * </ol>
     */
    // [DEFENSE-MARK] 诊断：状态锁拒绝写入的窗口汇总（per-channel）。
    public static void flushRejectedWrites(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        RejectedWrites stat = REJECTED_WRITES.get(id);
        if (stat == null || stat.channels.isEmpty()) {
            return;
        }
        if (!ModLog.allowThrottled("PlayerDefense.stateLock.rejectStats:" + id, ModLog.TICK_INTERVAL)) {
            return;
        }
        // 快照本窗口：取计数后清零，再把空条目摘掉，保证窗口边界干净、下一个窗口重新统计。
        List<RejectStats.Sample> samples = new ArrayList<>();
        for (Map.Entry<Integer, ChannelStat> entry : stat.channels.entrySet()) {
            ChannelStat channel = entry.getValue();
            int count = channel.count.getAndSet(0);
            if (count <= 0) {
                stat.channels.remove(entry.getKey());
                continue;
            }
            samples.add(new RejectStats.Sample(entry.getKey(), count, channel.lastValue));
        }
        if (samples.isEmpty()) {
            return;
        }
        int total = RejectStats.total(samples);
        // 首现行：通道第一次被拒就报一次，替代被硬拒遮蔽的「通道探测」。
        for (RejectStats.Sample sample : samples) {
            if (stat.reported.add(sample.channel())) {
                ModLog.health("状态锁[层=SynchedEntityData.set] 首次拒绝该通道的写入", player,
                        "id=" + sample.channel() + " 值=" + sample.value()
                                + " 本窗口次数=" + sample.count());
            }
        }
        ModLog.health("状态锁[层=SynchedEntityData.set] 本窗口拒绝外部状态写入", player,
                "共" + total + "次 " + RejectStats.describe(samples, MAX_REJECT_CHANNELS_SHOWN));
    }

    /**
     * 方向五（改造后的主用途）：{@code LivingEntity.setHealth(float)} 的入参过滤——受保护玩家的
     * 写入一律以旁路真实血量为准。
     *
     * <p>与 Mixin 的职责分离：
     * <ul>
     *   <li>Mixin {@code @ModifyVariable} 只处理“数值合法性”（NaN / 无限 / 负数 → 最大血量），
     *       与游戏状态无关，对所有实体生效；</li>
     *   <li>本方法只写 REAL_HEALTH：是 Player、{@link SwordGuard#isProtected} 为 true 且旁路已有
     *       记录时，无论其它模组请求写入什么值，都返回旁路真实血量；否则原值放行。</li>
     *   <li>{@link #serverTick} 每 tick 强制写回原版同步数据，是最后一道防线。</li>
     * </ul>
     *
     * <p>判断只依赖旁路存储与是否受保护，不依赖任何模组名。外部实现每次
     * {@code setHealth(当前 - 0.5)} 都会被替换为旁路真实血量，血量只增不减。
     *
     * <p>计数逻辑（{@link #WRITE_STATS} / {@link #MAX_WRITES_PER_TICK} / {@link #LOCK_TICKS}）
     * 保留作为兜底：短时间内写入次数异常多即打开 invulnerable 锁定窗口，说明有模组在疯狂写血。
     *
     * @return 实际交给 {@code setHealth} 写入的值
     */
    // [DEFENSE-MARK] 防御：setHealth 入参过滤，受保护玩家写入一律以旁路血量为准。
    public static float filterHealthWrite(LivingEntity self, float value) {
        if (!(self instanceof Player player)) {
            return value;
        }
        // Mixin 注入自类加载期即生效，可能在玩家构造早期（inventory 尚未就绪）被 setHealth 调用，
        // 因此 isProtected 判定与后续逻辑一律容错：异常时原值放行。
        try {
            if (!SwordGuard.isProtected(player)) {
                return value;
            }
        } catch (Throwable t) {
            return value;
        }
        boolean overLimit = countWrite(player);

        // 只写 REAL_HEALTH：旁路记录存在时无条件以它为准，其它模组传入的减血/增血/非法值都被替换。
        UUID id = player.getUUID();
        Float real = REAL_HEALTH.get(id);
        boolean hasRecord = real != null && !Float.isNaN(real) && !Float.isInfinite(real)
                && real >= 0.0F && !RETIRED.contains(id);
        if (!hasRecord) {
            // 需求：前提供打破时不再无条件信任写入。受保护玩家却没有有效的旁路记录时，
            // 用“当前血量”兜底——高频裸写 / 减血写入都被挡在这里。
            float current = currentHealth(player);
            // 例外：合法的"抬高"写入必须放行。登录/重生/治疗都会先 setHealth(满血)，而那一刻旁路
            // 记录还没建立；如果一并压回当前值，进世界的那一次 setHealth(20) 会被压成 1.0，
            // 表现为"刚进世界只有一格血、下一 tick 才跳到 20"（实测日志正是这个序列）。
            boolean raise = value > current && !Float.isNaN(value) && !Float.isInfinite(value);
            if (raise && !overLimit) {
                ModLog.healthThrottled("PlayerDefense.filterHealthWrite.noBypassRaise:" + id,
                        ModLog.TICK_INTERVAL,
                        "filterHealthWrite[层=setHealth 入参过滤] 无旁路记录但为抬高写入，放行", player,
                        "请求setHealth(" + value + ") -> 当前血量=" + current);
                return value;
            }
            if (overLimit) {
                ModLog.healthThrottled("PlayerDefense.filterHealthWrite.noBypassOverLimit:" + id,
                        ModLog.TICK_INTERVAL,
                        "filterHealthWrite[层=setHealth 入参过滤] 无有效旁路且高频写入，强制以当前血量兜底",
                        player, "请求setHealth(" + value + ") -> 当前血量=" + current);
            } else if (value != current) {
                ModLog.healthThrottled("PlayerDefense.filterHealthWrite.noBypass:" + id,
                        ModLog.TICK_INTERVAL,
                        "filterHealthWrite[层=setHealth 入参过滤] 无有效旁路，以当前血量兜底", player,
                        "请求setHealth(" + value + ") -> 当前血量=" + current);
            }
            return current;
        }
        if (overLimit) {
            // 需求：每 tick 的写入次数独立设门。超过阈值后本次写入一律压回权威血量。
            ModLog.healthThrottled("PlayerDefense.filterHealthWrite.overLimit:" + id,
                    ModLog.TICK_INTERVAL,
                    "filterHealthWrite[层=setHealth 入参过滤] 高频写入超限，拒绝本次写入", player,
                    "请求setHealth(" + value + ") -> 旁路真实血量=" + real);
            return real;
        }
        // 写入语义统一交给策略层：默认「只拦减血、允许合法抬高（治疗）」，
        // -Dstar_sword.health.lockStrict=true 时改为「锁定值压过一切写入」。
        // 这一层与数据项代理的 renderWrite 必须同口径，否则 setHealth 会被这里无条件压回、
        // 让策略开关形同虚设（本层原先就是无条件 return real）。
        float maxHealth;
        try {
            maxHealth = player.getMaxHealth();
        } catch (Throwable t) {
            maxHealth = Float.NaN;
        }
        float resolved = HealthWritePolicy.resolve(value, real, maxHealth);
        if (resolved != value) {
            // 其它模组可能每 tick 调 setHealth 想减血：正常拒绝时限频 40t 一次，避免刷屏。
            ModLog.healthThrottled("PlayerDefense.filterHealthWrite:" + id,
                    ModLog.TICK_INTERVAL,
                    "filterHealthWrite[层=setHealth 入参过滤] 以旁路真实血量为准", player,
                    "请求setHealth(" + value + ") -> 旁路真实血量=" + real
                            + " 实际写入=" + resolved + " 语义=" + HealthWritePolicy.describe());
        }
        return resolved;
    }

    /**
     * 方向五兜底：统计 setHealth 调用频率，超阈值打开锁定窗口。
     *
     * @return 本 tick 的写入次数是否已超过 {@link #MAX_WRITES_PER_TICK}。超过时
     *         {@link #filterHealthWrite} 会拒绝本次写入（独立于 REAL_HEALTH 的“高频裸写”门）。
     */
    // [DEFENSE-MARK] 防御：统计 setHealth 调用频率，超阈值打开 invulnerable 锁定窗口。
    private static boolean countWrite(Player player) {
        if (player.level().isClientSide) {
            return false;
        }
        UUID id = player.getUUID();
        WriteStat stat = WRITE_STATS.computeIfAbsent(id, k -> new WriteStat());
        if (stat.tick != player.tickCount) {
            stat.tick = player.tickCount;
            stat.count = 0;
        }
        stat.count++;
        if (stat.count > MAX_WRITES_PER_TICK) {
            if (stat.lockUntilTick < player.tickCount) {
                PREV_INVULNERABLE.putIfAbsent(id, player.isInvulnerable());
                ModLog.healthThrottled("PlayerDefense.countWrite.lock:" + id, ModLog.TICK_INTERVAL,
                        "countWrite[层=setHealth入参过滤] 检测到血量高频写入，打开锁定窗口", player,
                        "本tick写入次数=" + stat.count + " 锁定tick数=" + LOCK_TICKS + " setInvulnerable(true)");
            }
            stat.lockUntilTick = player.tickCount + LOCK_TICKS;
            try {
                player.setInvulnerable(true);
            } catch (Throwable ignored) {
            }
            return true;
        }
        return false;
    }

    /** 读取玩家当前血量并做非负/非 NaN 兜底，供“无旁路记录”时的兜底值使用。 */
    private static float currentHealth(Player player) {
        try {
            // 同样优先数据项：公式可能已经让 getHealth 失真。
            float health = rawSyncedHealth(player);
            if (Float.isNaN(health)) {
                health = player.getHealth();
            }
            if (Float.isNaN(health) || Float.isInfinite(health) || health < 0.0F) {
                return 0.0F;
            }
            return health;
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    // ===================== 服务端 tick =====================

    /**
     * 由 {@code StarSwordItem.inventoryTick} 与 {@code ModForgeEvents.onPlayerTick} 两个调用点
     * 触发；内部按 tickCount 去重，每 tick 只真正执行一次。
     */
    // [DEFENSE-MARK] 防御：每 tick 恢复旁路血量、写回同步数据、还原 removalReason。
    public static void serverTick(Player player) {
        UUID id = player.getUUID();

        if (!SwordGuard.isProtected(player)) {
            onProtectionExpired(player);
            return;
        }

        int tick = player.tickCount;
        Integer last = LAST_GUARD_TICK.get(id);
        if (last != null && last == tick) {
            return;
        }
        LAST_GUARD_TICK.put(id, tick);

        // 重新进入防御状态：若记录曾因保护到期被标记退役，这里恢复其权威性。
        RETIRED.remove(id);
        // 需求：旁路真实血量每 tick 恢复到 20，并同步写回原版血量同步数据。
        // 不再读取原版血量/同步数据（raw）——其它模组正是通过字段或 entityData.set(...) 改血，
        // 读进来只会污染旁路存储；也不再用 getMaxHealth()（可能被其它模组改小），固定目标 20。
        // REAL_HEALTH 是唯一权威来源。
        float stored = BYPASS_HEALTH;
        REAL_HEALTH.put(id, stored);

        // 每个 tick 强制写回原版同步数据（DATA_HEALTH_ID），覆盖其它模组对本模组玩家的改血。
        forceWriteVanillaHealth(player);

        // 状态锁：默认作用域是客户端，这里的服务端调用在 scope=client 下是空操作（不改变服务端逻辑）；
        // scope=both 时才锁服务端的最大生命/血量/护甲/食物。
        VanillaStateLock.applyOn(player, false);

        // 状态锁拒绝写入的窗口汇总：热路径只记数，日志在这里落（与写入频率解耦）。
        flushRejectedWrites(player);

        // 数据层最后一道：把玩家身上「非原版的浮点同步通道」钉回 0。
        // 有些实现用自己的通道决定「是否算死」，既不读原版血量，也不看我们的注入点；
        // 但它必须持有那个值，所以按值中和是唯一不依赖方法体的手段。
        ForeignChannelGuard.neutralize(player);

        // 最大生命：只记基线 + 检测变化（默认不回滚，避免与合法的属性改动作战）。
        AttributeGuard.check(player);

        // 吸收量：原版另一个浮点同步通道，同样纳入代理（只做数值净化，不覆盖合法值）。
        HealthDataItemInstaller.ensureAbsorptionSanitized(player);

        // 读取侧自检：读一次报告值，判断“整体替换 getHealth”是否还在生效；恢复后解除通道冻结。
        tickReadHealth(player);

        // 把服务端权威的旁路真实血量同步给客户端（专用服客户端 JVM 独立，静态 Map 不会自动有值），
        // 20 tick 限频，避免每 tick 发包。
        syncToClient(player, stored, true);

        // 方向三：removalReason 写监控——白名单之外的移除理由（KILLED/DISCARDED/未知…）
        // 在血量 > 0 时一律还原为 null。
        Entity.RemovalReason reason = readRemovalReason(player);
        if (reason != null && stored > 0.0F && !isRemovalAllowed(player, reason)) {
            writeRemovalReason(player, null);
            ModLog.defenseThrottled("PlayerDefense.serverTick.removal:" + id, ModLog.TICK_INTERVAL,
                    "serverTick[层=每tick防御] 检测到白名单外的移除理由，还原 removalReason=null", player,
                    "原值=" + reason);
        }

        // 方向五：锁定窗口内每 tick 覆盖字段，窗口结束还原 invulnerable。
        WriteStat stat = WRITE_STATS.get(id);
        if (stat != null) {
            if (stat.lockUntilTick >= tick) {
                forceWriteVanillaHealth(player);
                ModLog.healthThrottled("PlayerDefense.serverTick.lockWindow:" + id, ModLog.TICK_INTERVAL,
                        "serverTick[层=每tick防御] 血量锁定窗口内覆盖同步数据", player, "写入=" + stored);
            } else if (stat.lockUntilTick != 0) {
                stat.lockUntilTick = 0;
                restoreInvulnerable(player);
                ModLog.health("serverTick 血量锁定窗口结束，还原 invulnerable", player, "");
            }
        }

        // 正常生效路径的 40t 一次汇总：证明旁路血量恢复 + 写回这条正向链路确实在跑。
        ModLog.healthThrottled("PlayerDefense.serverTick.normal:" + id, ModLog.TICK_INTERVAL,
                "serverTick[层=每tick防御] 正常：旁路血量恢复并写回原版同步数据", player,
                "旁路=" + stored);
    }

    /**
     * 强制把旁路真实血量写回原版 {@code LivingEntity.DATA_HEALTH_ID} 同步数据项。
     *
     * <p>只使用原版访问器（{@link ReflectionHelper#healthDataAccessor()}），不做按实体的
     * {@link ReflectionHelper#healthDataAccessor(LivingEntity)} 自定义键探测——玩家血量始终存这里，
     * 写原版键即可被客户端同步读到。
     *
     * <p>由 {@code serverTick} 与 {@code LivingEntityMixin} 的 {@code tick} HEAD/RETURN 注入调用：
     * 即使某个模组在 tick 中途用 {@code entityData.set(...)} 篡改了同步数据，tick 结束时也会被覆盖。
     *
     * <p>旁路没有记录时不动作（客户端等未建立旁路的一侧保持原版行为）。
     */
    // [DEFENSE-MARK] 防御：强制把旁路血量写回原版 DATA_HEALTH_ID 同步数据项。
    public static void forceWriteVanillaHealth(Player player) {
        UUID id = player.getUUID();
        // 语义层：确保血量数据项已被换成代理项（读/写/发包三处收口），再写才算真正权威。
        HealthDataItemInstaller.ensureInstalled(player);
        Float real = REAL_HEALTH.get(id);
        if (real == null || Float.isNaN(real) || Float.isInfinite(real) || RETIRED.contains(id)) {
            // 未建立旁路 / 记录已退役（保护到期）：这一层不动作，属正常放行。
            ModLog.healthThrottled("PlayerDefense.forceWriteVanillaHealth.noBypass:" + id,
                    ModLog.TICK_INTERVAL,
                    "forceWriteVanillaHealth[层=tick 强制写回] 旁路无记录/非法/已退役，跳过写回", player,
                    "旁路=" + real);
            return;
        }
        EntityDataAccessor<Float> key = ReflectionHelper.healthDataAccessor();
        if (key == null) {
            // 静默失败点：写不回去，客户端/原版读数不会被纠正，必须能被看见。
            ModLog.health("forceWriteVanillaHealth[层=tick 强制写回] 原版血量数据键解析失败，写回失效", player,
                    "旁路=" + real);
            return;
        }
        // 需求：把旁路血量（每 tick 恢复的 20）原样同步到原版同步数据，不再按 getMaxHealth 二次截断
        // （其它模组可能把 maxHealth 改小，截断会让原版血量读不到 20）。仅做非负兜底。
        float synced = Math.max(0.0F, real);
        // 这次写入就是要把同步数据改回旁路真实血量，必须跳过 SynchedEntityData.set 的“减血拦截”。
        SYNCED_WRITE_BYPASS.set(Boolean.TRUE);
        try {
            player.getEntityData().set(key, synced);
        } catch (Throwable t) {
            ModLog.health("forceWriteVanillaHealth[层=tick 强制写回] 写同步数据抛出异常", player,
                    "写入=" + synced + " 异常=" + t);
        } finally {
            SYNCED_WRITE_BYPASS.set(Boolean.FALSE);
        }
    }

    // ===================== removalReason 字段 =====================

    private static Field removalReasonField() {
        if (!removalReasonResolved) {
            synchronized (PlayerDefense.class) {
                if (!removalReasonResolved) {
                    removalReasonField = ReflectionHelper.findFieldCompat(
                            Entity.class, "f_146795_", "removalReason");
                    removalReasonResolved = true;
                }
            }
        }
        return removalReasonField;
    }

    private static Entity.RemovalReason readRemovalReason(Player player) {
        Field field = removalReasonField();
        if (field == null) {
            return null;
        }
        try {
            return (Entity.RemovalReason) field.get(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读 {@code Entity.removalReason} 的原始值（绕过我们的 Mixin，也不看任何压制）——给实体完整性自检用。
     *
     * @return {@code null} 表示"没被移除"或反射不可用（调用方须区分：先看 {@code removalReasonField()} 是否可用）
     */
    public static Entity.RemovalReason rawRemovalReason(Entity self) {
        if (self == null) {
            return null;
        }
        Field field = removalReasonField();
        if (field == null) {
            return null;
        }
        try {
            return (Entity.RemovalReason) field.get(self);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeRemovalReason(Player player, Entity.RemovalReason reason) {
        Field field = removalReasonField();
        if (field == null) {
            return;
        }
        try {
            field.set(player, reason);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreInvulnerable(Player player) {
        Boolean prev = PREV_INVULNERABLE.remove(player.getUUID());
        if (prev != null) {
            try {
                player.setInvulnerable(prev);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class WriteStat {
        int tick = Integer.MIN_VALUE;
        int count;
        int lockUntilTick;
    }
}
