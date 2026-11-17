package cn.autoforged.star_sword_mod_1785589476.helper;

/**
 * 权威血量「写入语义」策略（纯 JDK，可离线验证）。
 *
 * <p>玩家被锁在某个体检血值上时，其它模组/原版的写入有两种处理口径，这里把选择权交出去：
 *
 * <ul>
 *   <li><b>默认（宽松）</b>：只拦「减血」与非法值，<b>允许</b>合法的抬高（治疗、重生回满、
 *       登录时的一次 {@code setHealth(20)}）。理由：本模组的目标是「打不死」，不是「不许回血」；
 *       把治疗一并冻掉会误伤原版与其它治疗类模组。</li>
 *   <li><b>严格（{@code -Dstar_sword.health.lockStrict=true}）</b>：只要存在权威值，任何写入都被
 *       替换为该权威值（并夹到 {@code [0, maxHealth]}），治疗也无法突破——用于「锁血期间不想让任何
 *       人改动血量」的场景。</li>
 * </ul>
 *
 * <p>两种口径都做上限夹取：超过 {@code maxHealth} 的值一律收到上限。这不改变"可否治疗"的语义，
 * 只是防止外部把血量写成 {@code 1e9} 这类荒谬值（那会直接撑坏血条与伤害计算）。
 *
 * <p>为什么单独成一个纯函数类：{@code PlayerDefense.filterHealthWrite} 与
 * {@code HealthAuthority.renderWrite} 是两条必须一致的入口，而它们都依赖 MC 类、无法离线断言；
 * 把判定抽出来后，两处只负责取值，口径只在 {@link #resolve} 一处。
 *
 * <p><b>Never throws</b>：全部 try/catch，异常时返回入参（放行）。
 */
public final class HealthWritePolicy {

    /** 系统属性：严格锁开关。未设置时为 {@code false}（默认允许治疗）。 */
    public static final String PROP_STRICT = "star_sword.health.lockStrict";

    /**
     * 当前是否严格锁。
     *
     * <p>原先是 {@code static final}（启动时读一次、进程内不变），只能靠 {@code -D} 切换。
     * 现在改成 {@code volatile}：启动值仍取 {@code -D}，之后可以由机制面板/命令在运行期切换，
     * <b>默认值不变</b>，不改变任何既有判定的结果。
     */
    private static volatile boolean strict = truthy(System.getProperty(PROP_STRICT, "false"));

    private HealthWritePolicy() {
    }

    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    /** 是否严格锁（供状态输出与调用方判断）。 */
    public static boolean isStrict() {
        return strict;
    }

    /** 运行期切换写入语义；面板与 {@code -D} 共用这个入口。 */
    public static void setStrict(boolean value) {
        strict = value;
    }

    /** 一行诊断：{@code strict=false（允许治疗越过锁定值）}。 */
    public static String describe() {
        return strict ? "strict=true（锁定值压过一切写入）" : "strict=false（允许合法抬高/治疗）";
    }

    /**
     * 写入裁决：给 {@code incoming} 定一个最终落盘值。
     *
     * @param incoming  请求写入的值（可能非法）
     * @param locked    权威锁定值；{@code null} 表示该玩家没有锁定
     * @param maxHealth 血量上限；非有限或 {@code <= 0} 时不做上限夹取
     * @return 实际写入的值
     */
    public static float resolve(float incoming, Float locked, float maxHealth) {
        return resolve(incoming, locked, Float.NaN, maxHealth);
    }

    /**
     * 写入裁决（带「当前值」）：非法写入不再被写成 0，而是<b>保持原值</b>。
     *
     * <p>为什么需要 {@code current}：外部实现可能把算式结果（负数 / NaN）直接写进血量项。
     * 若把它净化成 0，就等于把玩家写成"已死"——与读取侧的陷阱是同一个。正确语义是
     * <b>非法写入不生效</b>：值停在 {@code current}，让读取侧按 {@link HealthReadPolicy} 决定对外呈现。
     *
     * @param incoming  请求写入的值
     * @param locked    权威锁定值；{@code null} 表示没有锁定
     * @param current   当前存储值；非法或不可用时按 0 处理
     * @param maxHealth 血量上限
     * @return 实际写入的值
     */
    public static float resolve(float incoming, Float locked, float current, float maxHealth) {
        try {
            boolean illegalIncoming = Float.isNaN(incoming) || Float.isInfinite(incoming) || incoming < 0.0F;
            if (locked == null || Float.isNaN(locked) || Float.isInfinite(locked)) {
                if (illegalIncoming) {
                    // 非法写入：不落 0（那等于写成死亡），保持当前值。
                    return HealthReadPolicy.isPlausible(current) ? current : 0.0F;
                }
                return clampUpper(incoming, maxHealth);
            }
            if (strict) {
                // 严格：忽略入参，锁定值说了算。
                return clampUpper(sanitize(locked), maxHealth);
            }
            if (illegalIncoming || incoming < locked) {
                return clampUpper(sanitize(locked), maxHealth);
            }
            return clampUpper(incoming, maxHealth);
        } catch (Throwable throwable) {
            return incoming;
        }
    }

    /**
     * 上限夹取：{@code maxHealth} 不可用时原样返回。
     *
     * <p>刻意<b>不</b>做下限处理——下限（负数）由 {@link #sanitize} 负责，两者分开是为了让
     * 「0 血算死」这一原版语义不被上限逻辑顺手改掉。
     */
    public static float clampUpper(float value, float maxHealth) {
        try {
            if (Float.isNaN(maxHealth) || Float.isInfinite(maxHealth) || maxHealth <= 0.0F) {
                return value;
            }
            return Math.min(value, maxHealth);
        } catch (Throwable throwable) {
            return value;
        }
    }

    /** 非法值净化：{@code NaN}/无穷/负数 → 0（与原版"0 血即死"一致）。 */
    public static float sanitize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0.0F) {
            return 0.0F;
        }
        return value;
    }
}
