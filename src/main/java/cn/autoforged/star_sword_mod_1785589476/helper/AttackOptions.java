package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.Locale;

/**
 * 攻击侧开关集中地。
 *
 * <h2>约定（攻击侧改造的统一口径）</h2>
 * <ul>
 *   <li>每一条可裁决的攻击能力都必须在这里有一个开关，<b>不删代码、只加闸门</b>；</li>
 *   <li>默认值 = 该条裁决后的口径（裁决为"关"就默认 {@code false}，需要时再打开）；</li>
 *   <li>取值来源是系统属性，开发运行用 {@code build.gradle} 里对应的 {@code -PssXxx} 注入；</li>
 *   <li>运行期可改（{@code setXxx}），方便后续接到符咒/命令上；改完由 {@link #describe()} 输出当前全量口径。</li>
 * </ul>
 *
 * <p>纯 JDK 实现：只读系统属性 + 静态字段，不引用任何 Minecraft 类型，可脱离游戏离线断言。
 */
public final class AttackOptions {

    /**
     * A2：在 {@code executeFullAttack} 里追加"依次施加全部 30 种伤害类型"。
     *
     * <p><b>默认关闭</b>：裁决要求"只保留星辉伤害的 MAX 伤害"。打开后对"对某类伤害免疫"的
     * 模组生物能硬穿，代价是每次挥砍最多 30 次 {@code hurt()} 与约 30 行攻击日志，且会触发
     * 对方所有的受击生成逻辑（历史上曾因反击之符同样的原因堆出 9,100 个实体）。
     */
    public static final String PROP_ALL_DAMAGE_TYPES = "star_sword.attack.allDamageTypes";

    /**
     * A7：死亡锁（{@code lockTarget} + 每 tick 的 {@code enforceDeathLocks} / {@code enforceLockedTarget}）。
     *
     * <p><b>默认开启</b>（= 现状行为）。关掉后：不再把目标写进 {@code KILL_LOCK_*} / {@code KILLED_BY_STAR_SWORD}，
     * 每 tick 驱动直接返回，因此也不再有"被限伤/被复活 → 每 tick 反复强杀 50 次"的压制；
     * 代价是 {@code LivingEntityMixin} 里依赖 {@code KILLED_BY_STAR_SWORD} 的 hurt/setHealth 兜底随之失效，
     * 能不能杀死完全取决于单次攻击那一下。
     */
    public static final String PROP_DEATH_LOCK = "star_sword.attack.deathLock";

    /**
     * A11：右键时把 128 格内的掉落物吸进背包（{@code attractItemsInRadius}）。
     *
     * <p><b>默认开启</b>（= 现状行为）。关掉后右键只做 AoE 攻击，不再远程拾取；
     * shift+右键清场里的"顺带 discard 掉落物"是清场语义的一部分，不归本开关管。
     */
    public static final String PROP_ITEM_ATTRACTION = "star_sword.attack.itemAttraction";

    private static volatile boolean allDamageTypes =
            truthy(System.getProperty(PROP_ALL_DAMAGE_TYPES, "false"));
    private static volatile boolean deathLock =
            truthy(System.getProperty(PROP_DEATH_LOCK, "true"));
    private static volatile boolean itemAttraction =
            truthy(System.getProperty(PROP_ITEM_ATTRACTION, "true"));


    private AttackOptions() {
    }

    /** A2 开关：是否追加全部伤害类型轮打。 */
    public static boolean allDamageTypes() {
        return allDamageTypes;
    }

    public static void setAllDamageTypes(boolean enabled) {
        allDamageTypes = enabled;
        ModLog.log(ModLog.ATTACK, "AttackOptions.setAllDamageTypes 运行期切换 全部伤害类型轮打=" + enabled);
    }

    /** A7 开关：死亡锁与每 tick 死亡压制是否生效。 */
    public static boolean deathLock() {
        return deathLock;
    }

    public static void setDeathLock(boolean enabled) {
        deathLock = enabled;
        ModLog.log(ModLog.ATTACK, "AttackOptions.setDeathLock 运行期切换 死亡锁=" + enabled);
    }

    /** A11 开关：右键是否把 128 格内的掉落物吸进背包。 */
    public static boolean itemAttraction() {
        return itemAttraction;
    }

    public static void setItemAttraction(boolean enabled) {
        itemAttraction = enabled;
        ModLog.log(ModLog.ATTACK, "AttackOptions.setItemAttraction 运行期切换 远距离拾取=" + enabled);
    }

    /** 当前口径快照，供 {@code /starsword agent status} 一类状态输出使用。 */
    public static String describe() {
        return "全部伤害类型轮打=" + allDamageTypes
                + " 死亡锁=" + deathLock
                + " 远距离拾取=" + itemAttraction;
    }

    /**
     * 宽松的布尔解析：{@code 1/true/on/yes} 为真（大小写不敏感），其余为假。
     * 与 {@code ModForgeEvents} 里测试钩子的 {@code truthy} 同口径，避免两套写法。
     */
    static boolean truthy(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }
}
