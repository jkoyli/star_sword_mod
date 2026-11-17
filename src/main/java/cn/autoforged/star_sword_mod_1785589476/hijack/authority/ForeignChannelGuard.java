package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import cn.autoforged.star_sword_mod_1785589476.helper.RawState;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未知浮点通道中和：受保护期间，把玩家身上<b>非原版</b>的浮点同步通道钉在 0。
 *
 * <h2>要解决的通用问题</h2>
 * <p>有些实现会在实体上<b>新增</b>一条浮点同步通道，用自己的规则（累加、阈值、比例）把它换算成
 * 「是否算死」「血量是多少」，并且用运行期重定义整体替换 {@code getHealth()} / {@code isDeadOrDying()}
 * 的方法体。对这种实现：
 * <ul>
 *   <li>往方法里插代码没有意义——方法体会被覆盖；</li>
 *   <li>改原版血量数据项也没有意义——它根本不读那一项；</li>
 *   <li>但它要判断，就必须<b>持有</b>那个值：既在同步通道里，也在自己的字段里。</li>
 * </ul>
 * <p>因此唯一稳定的着力点是<b>值本身</b>：每个 tick 把这些外部浮点通道清零，并把它背后的
 * {@code float}/{@code double} 字段一并清零（只按值匹配，不认名字）。
 *
 * <h2>默认只观测，不动数据</h2>
 * <p>清零默认<b>关闭</b>（{@code -Dstar_sword.foreignChannelGuard=clear} 才写值）。原因是实测评测
 * 出两件事：一是外部通道语义不可知，按值清零会误伤其它模组的合法资源条；二是清零对"对方已把值
 * 锁存进自己字段"的实现无效——TTM 的 soul=500 被清回 0，胜负并不靠它。默认模式下本类退化成
 * <b>观测器 + 判据提供者</b>，写侧拦截交给 {@code PlayerDefense.shouldBlockSynchedWrite}。
 *
 * <h2>白名单</h2>
 * <p>原版玩家实体上只有两个浮点同步通道：<b>血量</b>与<b>吸收量</b>。本类只放过这两个，
 * 其余浮点通道一律视为外部通道。代价要说清楚：其它模组挂在受保护玩家身上的浮点同步量
 * （例如自定义资源条）也会被清零；这是刻意取舍——受保护期间只信任原版语义。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>只对「受保护 且 有可用权威血量」的玩家生效，保护一结束立即停止；</li>
 *   <li>每个通道第一次被中和时记一行日志，之后不再刷屏；</li>
 *   <li><b>Never throws</b>：任何反射失败都跳过该通道。</li>
 * </ul>
 */
public final class ForeignChannelGuard {

    /** 工作模式。 */
    public enum Mode {
        /** 真中和：清零通道并把按值匹配的浮点字段一并清零（需显式 {@code clear}，非默认）。 */
        ON,
        /** 灰度观测：只记录"如果中和会清掉什么"，不改写任何数据。 */
        OBSERVE,
        /** 关闭。 */
        OFF
    }

    /**
     * 模式开关：{@code -Dstar_sword.foreignChannelGuard=observe|clear|off}，<b>默认 {@code observe}</b>。
     *
     * <p>默认是观测而不是真中和，理由是被实测证过两次的：外部通道的语义不可知，按值清零会误伤
     * 别的模组的合法资源条（耐力 / 法力 / 温度 / 护盾），而且清零对"对方已经内部锁存了累加量"
     * 的实现无效——TTM 那次 soul=500 被清回 0，胜负并不靠它，靠的是 BODY 裁决 + 移除闸门。
     *
     * <p>需要真中和时显式 {@code clear}（旧写法 {@code true}/{@code on} 等价）。写侧拦截已由
     * {@code PlayerDefense.shouldBlockSynchedWrite} 的硬拒模式承担，本类只负责判定与观测。
     */
    private static final Mode MODE = resolveMode();

    private static Mode resolveMode() {
        String raw = System.getProperty("star_sword.foreignChannelGuard", "observe");
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return Mode.OBSERVE;
        }
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) {
            return Mode.OFF;
        }
        if ("clear".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)
                || "write".equalsIgnoreCase(value)) {
            return Mode.ON;
        }
        if ("observe".equalsIgnoreCase(value) || "dry".equalsIgnoreCase(value)
                || "dry-run".equalsIgnoreCase(value) || "record".equalsIgnoreCase(value)) {
            return Mode.OBSERVE;
        }
        return Mode.OBSERVE;
    }

    /** 已经记过日志的通道 id。 */
    private static final Set<Integer> LOGGED = ConcurrentHashMap.newKeySet();

    /** 命中过的通道样本（真中和与灰度观测都记），供启动横幅与 agent status 展示。 */
    private static final Map<Integer, Observation> OBSERVATIONS = new ConcurrentHashMap<>();

    /** 单条通道的观测摘要。 */
    private static final class Observation {
        private final java.util.concurrent.atomic.AtomicInteger count =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile float lastValue;
        private volatile int fields;
        private volatile String player = "";
        private volatile long lastSeenAt;

        private void record(float value, int clearedFields, String who) {
            count.incrementAndGet();
            lastValue = value;
            fields = clearedFields;
            player = who;
            lastSeenAt = System.currentTimeMillis();
        }
    }

    private ForeignChannelGuard() {
    }

    /**
     * 该通道是否属于「未知浮点通道」。
     *
     * <p>纯函数，便于离线验证：{@code healthId} 与 {@code absorptionId} 是原版通道，
     * 其它 id 一律算未知；id 未知（{@code < 0}）时保守地不动。
     *
     * @param channelId    待判定的通道 id
     * @param healthId     原版血量通道 id
     * @param absorptionId 原版吸收量通道 id（解析失败时为 {@code Integer.MIN_VALUE}）
     */
    public static boolean isForeignChannel(int channelId, int healthId, int absorptionId) {
        if (channelId < 0) {
            return false;
        }
        return channelId != healthId && channelId != absorptionId;
    }

    /**
     * 这个值是不是「权威血量的镜像」。
     *
     * <p>纯函数，便于离线验证。镜像通道要放过：清零它等于清血量，历史上正是这条误伤造成了
     * 周期性空血。宁可漏掉一个外部通道，也不要碰可能是血量的值。
     *
     * @param value         通道当前值
     * @param authoritative 权威血量；{@code null} 表示没有可用权威值
     */
    public static boolean isHealthMirror(float value, Float authoritative) {
        return authoritative != null && Math.abs(value - authoritative) < 1.0E-4F;
    }

    /**
     * 一次判定的结果：<b>该不该动</b>，以及<b>为什么</b>。
     *
     * <p>判定与写值在本类里彻底分开：{@link #decide} 只产出一个纯数据结论（不读静态状态、不写任何
     * 东西），真正的写值只发生在 {@link #writeNeutralized}。这样"观测模式"与"清空模式"共享同一份
     * 判据，不会出现两条路径各自漂移；日志也能直接写出判据理由，而不是只给一个布尔。
     */
    public record Decision(boolean neutralize, String reason) {
        static Decision no(String reason) {
            return new Decision(false, reason);
        }

        static Decision yes(String reason) {
            return new Decision(true, reason);
        }
    }

    /**
     * 唯一的判定（纯函数）：这一条同步项是否应该被压回 0。
     *
     * <p><b>四段门控，缺一不可</b>（包级过滤、{@code assignValues} 过滤、每 tick 中和三处共用）：
     * <ol>
     *   <li><b>仅"我们持有权威值"的实体</b>：服务端上权威值就在本进程，任何受保护玩家都算；
     *       客户端上只有本地玩家有权威值——别人的权威值天然为空，若不过滤，别人的自定义资源条
     *       （法力 / 口渴 / 温度）会被当成外部通道清零。</li>
     *   <li><b>受保护</b>：只有保护期间才接管。</li>
     *   <li><b>有可用权威值</b>：没有基准就无从"压回"。</li>
     *   <li><b>非白名单（原版血量 / 吸收量）且非血量镜像</b>：动手前最后一道护栏。</li>
     * </ol>
     *
     * <p>白名单本身不完整（血量键或吸收量键解析失败）时一律判"不动"——宁可漏，不可误伤。
     *
     * @param channelId       待判定的同步项 id
     * @param value           该项当前值
     * @param healthId        原版血量通道 id
     * @param absorptionId    原版吸收量通道 id（解析失败传 {@link #UNKNOWN_CHANNEL}）
     * @param authorityLocal  该实体是否由本进程持有权威值（服务端恒 true；客户端仅本地玩家）
     * @param protectedPlayer 该实体当前是否受保护
     * @param authoritative   权威血量；{@code null} 表示没有可用值
     */
    public static Decision decide(int channelId, float value, int healthId, int absorptionId,
                                  boolean authorityLocal, boolean protectedPlayer,
                                  Float authoritative) {
        if (!authorityLocal) {
            return Decision.no("权威值不在本进程");
        }
        if (!protectedPlayer) {
            return Decision.no("未受保护");
        }
        if (authoritative == null) {
            return Decision.no("无可用权威血量");
        }
        // 白名单不完整时整体不动：否则会把合法的吸收量通道当成外部通道清零。
        if (healthId < 0 || absorptionId == UNKNOWN_CHANNEL) {
            return Decision.no("白名单不完整（血量键或吸收键未解析）");
        }
        if (!isForeignChannel(channelId, healthId, absorptionId)) {
            return Decision.no("白名单通道（血量/吸收）");
        }
        if (isHealthMirror(value, authoritative)) {
            return Decision.no("数值等于权威血量（疑似镜像）");
        }
        return Decision.yes("非白名单通道且非血量镜像");
    }

    /**
     * 兼容旧入口：只有布尔结论，判定理由请用 {@link #decide}。
     *
     * @deprecated 判定与写值已分离，新代码请调用 {@link #decide} 并把 {@code reason} 写进日志。
     */
    @Deprecated
    public static boolean shouldNeutralize(int channelId, float value, int healthId, int absorptionId,
                                           boolean authorityLocal, boolean protectedPlayer,
                                           Float authoritative) {
        return decide(channelId, value, healthId, absorptionId, authorityLocal, protectedPlayer,
                authoritative).neutralize();
    }

    /**
     * 每 tick 调用一次（服务端与客户端都可）：把该玩家身上未知浮点通道的值清零。
     *
     * @param player 目标玩家；非玩家、未受保护、无可用权威血量时直接返回
     */
    public static void neutralize(LivingEntity player) {
        if (MODE == Mode.OFF || !(player instanceof Player p)) {
            return;
        }
        try {
            if (!SwordGuard.isProtected(p) || !HealthAuthority.isAuthoritative(p.getUUID())) {
                return;
            }
            EntityDataAccessor<Float> healthKey = ReflectionHelper.healthDataAccessor();
            if (healthKey == null) {
                return;
            }
            Map<Integer, SynchedEntityData.DataItem<?>> items = RawState.itemsOf(p);
            if (items == null || items.isEmpty()) {
                return;
            }
            int healthId = healthKey.getId();
            int absorptionId = absorptionChannelId();
            // 白名单不完整时整体跳过：否则会把合法的吸收量通道当外部通道清零。
            if (absorptionId == UNKNOWN_CHANNEL) {
                return;
            }
            boolean authorityLocal = PlayerDefense.isAuthorityLocal(p);
            boolean protectedPlayer = true; // 上面已经确认过受保护
            Float authoritative = AuthorityHealthStore.get(p.getUUID());
            for (Map.Entry<Integer, SynchedEntityData.DataItem<?>> entry : items.entrySet()) {
                try {
                    int id = entry.getKey();
                    SynchedEntityData.DataItem<?> item = entry.getValue();
                    if (item == null) {
                        continue;
                    }
                    if (!(item.getValue() instanceof Float value) || value == 0.0F) {
                        continue;
                    }
                    // 判据只有一份，与包级 / assignValues 两处过滤完全一致；这里只拿结论 + 理由，
                    // 写不写由模式决定（观测模式一个字节都不改）。
                    Decision decision = decide(id, value, healthId, absorptionId, authorityLocal,
                            protectedPlayer, authoritative);
                    // 趋势观测（纯记录）：回答"哪条通道在只增不减、有没有越过 1.0"。
                    // 越线这一步单独打一条，因为 20×(1-v) 这类换算式正是从 v>1 开始变负。
                    if (cn.autoforged.star_sword_mod_1785589476.helper.ChannelTrend
                            .observe(p.getUUID(), id, value)) {
                        ModLog.health("外部通道首次越过负值临界点（v>1 ⇒ 换算式变负）", p,
                                "id=" + id + " 值=" + value + "｜"
                                        + cn.autoforged.star_sword_mod_1785589476.helper.ChannelTrend.describe());
                    }
                    if (!decision.neutralize()) {
                        continue;
                    }
                    int cleared = MODE == Mode.ON
                            ? writeNeutralized(p, item, value)
                            : RawState.countMatchingFloatFields(p, value);
                    recordObservation(id, value, cleared, p, healthId, absorptionId, decision.reason());
                } catch (Throwable ignored) {
                    // 单个通道失败不影响其它通道
                }
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /**
     * 写值（唯一会改数据的地方，只在 {@link Mode#ON} 下被调用）：先清同步项，再清背后按值匹配的浮点字段。
     *
     * <p>只清同步项不够——外部实现会用自己的字段每 tick 写回来。
     *
     * @return 实际清零的字段个数
     */
    private static int writeNeutralized(Player player, SynchedEntityData.DataItem<?> item, float value) {
        RawState.writeRawFloat(player, accessorOf(item), 0.0F);
        return RawState.zeroMatchingFloatFields(player, value);
    }

    /**
     * 记录一条观测，并在该通道第一次出现时写一行日志（两种模式各用各的措辞）。
     *
     * <p>日志里带上这次判定用的白名单（血量 id / 吸收量 id）与判定理由：这两条最容易被误读成
     * "连血量都清了"，把 id 和理由写出来就能自证——id 等于白名单的条目根本不会走到这里。
     */
    private static void recordObservation(int id, float value, int fields, Player player,
                                          int healthId, int absorptionId, String reason) {
        OBSERVATIONS.computeIfAbsent(id, key -> new Observation())
                .record(value, fields, player.getName().getString());
        if (!LOGGED.add(id)) {
            return;
        }
        String whitelist = " 白名单=血量" + healthId + "/吸收" + absorptionId
                + (player.level().isClientSide ? " 端=客户端" : " 端=服务端")
                + " 判据=" + reason;
        if (MODE == Mode.OBSERVE) {
            ModLog.log(ModLog.HEALTH, "观测到未知浮点通道（observe，未做中和）：id=" + id
                    + " 原值=" + value + "，会清零的浮点字段数=" + fields
                    + " player=" + player.getName().getString() + "；本模式不改写任何数据" + whitelist);
        } else {
            ModLog.log(ModLog.HEALTH, "已中和未知浮点通道：id=" + id + " 原值=" + value
                    + "，同时清零浮点字段数=" + fields + " player=" + player.getName().getString()
                    + whitelist);
        }
    }

    /** 当前模式，供启动横幅与诊断展示。 */
    public static Mode mode() {
        return MODE;
    }

    /**
     * 一行摘要：模式 + 命中过的通道样本（最多 3 条）。
     *
     * <p>灰度观测的价值就在这里：{@code observe} 模式下每个通道的"会清零几个字段"是真实数据，
     * 可以用来判断通道背后到底有没有落字段，而不是靠猜。
     */
    public static String describe() {
        if (MODE == Mode.OFF) {
            return "mode=off";
        }
        StringBuilder sb = new StringBuilder("mode=")
                .append(MODE == Mode.ON ? "clear" : MODE.name().toLowerCase(java.util.Locale.ROOT));
        if (OBSERVATIONS.isEmpty()) {
            sb.append("，未观测到未知浮点通道");
            return sb.toString();
        }
        sb.append("，命中 ").append(OBSERVATIONS.size()).append(" 条通道");
        int shown = 0;
        for (Map.Entry<Integer, Observation> entry : OBSERVATIONS.entrySet()) {
            if (shown++ >= 3) {
                sb.append(" …");
                break;
            }
            Observation observation = entry.getValue();
            sb.append(" [id=").append(entry.getKey())
                    .append(" 值=").append(observation.lastValue)
                    .append(" ×").append(observation.count.get())
                    .append(" 字段=").append(observation.fields)
                    .append(']');
        }
        return sb.toString();
    }

    /** 清空观测/日志去重状态（测试与退服时用）。 */
    public static void resetAll() {
        LOGGED.clear();
        OBSERVATIONS.clear();
    }

    /** 某个玩家是否有过中和动作（诊断用）。 */
    public static int neutralizedChannelCount() {
        return LOGGED.size();
    }

    /** 清空诊断状态（退服/测试用）。 */
    public static void reset(UUID id) {
        // 目前日志去重按通道 id 而非玩家，这里保留接口以便将来按玩家细分。
        if (id == null) {
            LOGGED.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> accessorOf(SynchedEntityData.DataItem<?> item) {
        try {
            return (EntityDataAccessor<Float>) item.getAccessor();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 通道 id 未知（白名单不完整）。 */
    static final int UNKNOWN_CHANNEL = Integer.MIN_VALUE;

    /**
     * 原版吸收量通道 id；解析失败返回 {@link #UNKNOWN_CHANNEL}。
     *
     * <p>解析失败时必须<b>整体跳过</b>：白名单不完整就意味着"把合法的吸收量通道当外部通道清零"，
     * 宁可不动，也不能误伤。包级 / assignValues 两处过滤也用它，保证三处白名单一致。
     */
    public static int absorptionChannelId() {
        try {
            return AbsorptionHolder.ID;
        } catch (Throwable throwable) {
            return UNKNOWN_CHANNEL;
        }
    }

    /**
     * 原版吸收量通道的 <b>accessor 对象</b>（诊断用：按身份比较，不按 id）。
     *
     * <p>为什么需要它：日志里的 {@code id=} 只能说明"某个槽位现在是这个值"，说明不了"这个槽位是不是
     * Player.DATA_PLAYER_ABSORPTION_ID 注册的那一项"。要排除"认错键"，必须能比较对象身份。
     */
    public static EntityDataAccessor<?> absorptionAccessor() {
        try {
            return AbsorptionHolder.ACCESSOR;
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 延迟解析：避免在没有玩家的环境里提前触碰 Player 的静态初始化。 */
    private static final class AbsorptionHolder {

        private static final EntityDataAccessor<?> ACCESSOR = resolveAccessor();

        private static final int ID = ACCESSOR == null ? UNKNOWN_CHANNEL : ACCESSOR.getId();

        private static EntityDataAccessor<?> resolveAccessor() {
            try {
                java.lang.reflect.Field field = ReflectionHelper.findFieldCompat(
                        Player.class, "f_36107_", "DATA_PLAYER_ABSORPTION_ID");
                if (field == null) {
                    return null;
                }
                field.setAccessible(true);
                Object accessor = field.get(null);
                if (accessor instanceof EntityDataAccessor<?> dataAccessor) {
                    return dataAccessor;
                }
            } catch (Throwable ignored) {
                // 解析失败：交给上层跳过
            }
            return null;
        }
    }
}
