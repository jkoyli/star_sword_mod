package cn.autoforged.star_sword_mod_1785589476.helper;

/**
 * 权威血量「读取语义」策略（纯 JDK，可离线验证）。
 *
 * <h2>要解决的问题：算式写进来的负数不是"血量值"</h2>
 * <p>外部实现可以把「算式的结果」直接塞进血量数据项（或替换 {@code getHealth()} 让它返回算式值）。
 * 当算式是 {@code 20 × (1 − 累加值)} 这类形式时，结果会是负数。此时如果按普通数值处理——
 * 包括把它「净化成 0」——都会落入同一个陷阱：<b>0 在死亡语义里就等于"死"</b>
 * （原版 {@code isDeadOrDying()} 就是 {@code getHealth() <= 0}）。于是"负血"被消毒成"零血"，
 * 客户端照样判死、弹死亡界面、锁住移动。
 *
 * <p>所以这里把判定拆成两层语义：
 * <ul>
 *   <li><b>合法读数</b>：有限且 {@code >= 0} —— 照常使用；</li>
 *   <li><b>非法读数</b>（负数 / NaN / 无穷）：<b>不当作血量</b>，回退到「最近一次合法值」；
 *       没有合法值可用时退回 {@code maxHealth}；两者都没有才给 0。
 *       真实死亡是「读数正好是 0」，不会被这条规则改写——0 是合法值。</li>
 * </ul>
 *
 * <p>权威锁定值优先级最高：存在锁定值时直接返回锁定值，不参与上面的回退。
 *
 * <p><b>Never throws</b>。
 */
public final class HealthReadPolicy {

    private HealthReadPolicy() {
    }

    /** 该读数是否可能是"血量的真实取值"：有限且非负。{@code 0} 合法（就是死了）。 */
    public static boolean isPlausible(float raw) {
        return !Float.isNaN(raw) && !Float.isInfinite(raw) && raw >= 0.0F;
    }

    /**
     * 读取裁决。
     *
     * @param raw      数据项里的原始值（可能来自算式：负数 / NaN / 无穷）
     * @param locked   权威锁定值；{@code null} 表示没有锁定
     * @param lastGood 该实体最近一次观察到的合法正值；没有时为 {@code null}
     * @param maxHealth 血量上限；不可用时传 {@code NaN}
     * @return 对外应当呈现的血量
     */
    public static float resolve(float raw, Float locked, Float lastGood, float maxHealth) {
        try {
            if (locked != null && !Float.isNaN(locked) && !Float.isInfinite(locked) && locked >= 0.0F) {
                return locked;
            }
            if (isPlausible(raw)) {
                return raw;
            }
            // 非法读数：回退到最近一次合法值，其次上限，最后才是 0。
            if (lastGood != null && isPlausible(lastGood) && lastGood > 0.0F) {
                return lastGood;
            }
            if (isPlausible(maxHealth) && maxHealth > 0.0F) {
                return maxHealth;
            }
            return 0.0F;
        } catch (Throwable throwable) {
            return raw;
        }
    }

    /**
     * 是否应当把这次读数记成「最近一次合法值」。
     *
     * <p>只记正数：0 表示"确实死了"，不该被当成健康基线，否则下一次非法读数会把尸体"救活"。
     */
    public static boolean shouldRecordLastGood(float raw) {
        return isPlausible(raw) && raw > 0.0F;
    }
}
