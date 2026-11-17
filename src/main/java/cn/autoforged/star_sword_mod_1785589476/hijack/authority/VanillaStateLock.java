package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import cn.autoforged.star_sword_mod_1785589476.hijack.PlayerDefense;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

/**
 * 原版状态值硬锁：受保护玩家的<b>血量 / 护甲 / 饱食度 / 饱和度</b>每 tick 钉在原版满值上。
 *
 * <h2>为什么改成"锁"而不是"兼容"</h2>
 * <p>此前的策略是"尽量不碰别人的东西"：未知浮点通道只观测（{@link ForeignChannelGuard} 默认
 * observe），护甲与饱食度靠原版自己算。代价是任意一个会写这些字段的实现（外部模组、对手的
 * transformer）都能把状态改坏，而我们只在读侧纠正，表现为"看起来满血、实际判定已死"。
 *
 * <p>现在的取舍很明确：<b>受保护期间不承认任何外部状态语义</b>，原版这四个值一律锁死；写侧由
 * {@code PlayerDefense.shouldBlockSynchedWrite} 的硬拒模式拦截（除本模组自己的写回外，任何 Float
 * 同步写入都不落地）。这样"状态"这件事只剩一个来源，读侧再纠正才有意义。
 *
 * <h2>锁什么、锁成多少</h2>
 * <ul>
 *   <li><b>血量</b>：{@value #LOCK_HEALTH}（原版满血）。先把 {@code Attributes.MAX_HEALTH} 的
 *       baseValue 锁到同一值（否则被改小后 {@code setHealth} 会被夹住而反复抖动），再写入权威存储
 *       并走 {@code setHealth}，与每 tick 的写回路径共用同一份权威值；</li>
 *   <li><b>护甲</b>：{@code Attributes.ARMOR} 的<b>属性上限</b>（原版 30.0，从
 *       {@link RangedAttribute#getMaxValue()} 读，不写死数字）；</li>
 *   <li><b>饱食度</b>：{@value #LOCK_FOOD}；<b>饱和度</b>：{@value #LOCK_SATURATION}。</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <ul>
 *   <li>{@code -Dstar_sword.stateLock=off}：整体关闭（恢复原版行为）；</li>
 *   <li>{@code -Dstar_sword.stateLock.rejectWrites=false}：只锁值，不启用写侧硬拒；</li>
 * </ul>
 *
 * <p><b>Never throws</b>：任何一项失败都不影响其它项；只在受保护时动作。
 */
public final class VanillaStateLock {

    /** 血量锁定值（原版满血）。 */
    public static final float LOCK_HEALTH = 20.0F;

    /** 饱食度锁定值。 */
    public static final int LOCK_FOOD = 20;

    /** 饱和度锁定值（与原版满饱食度时的上限一致）。 */
    public static final float LOCK_SATURATION = 20.0F;

    /** 护甲兜底目标：{@code Attributes.ARMOR} 解析失败时使用（原版上限 30）。 */
    private static final double ARMOR_FALLBACK = 30.0D;

    private static final boolean ENABLED = parseBoolean("star_sword.stateLock", true);

    /** 状态锁作用域：默认只在客户端，服务端行为保持原样。 */
    public enum Scope {
        /** 关闭。 */
        OFF,
        /** 只锁客户端（默认）：服务端血量仍由既有权威存储/写闸门负责，护甲与食物不动。 */
        CLIENT,
        /** 两端都锁。 */
        BOTH
    }

    private static final Scope SCOPE = resolveScope();

    private static final boolean REJECT_WRITES = parseBoolean("star_sword.stateLock.rejectWrites", true);

    private VanillaStateLock() {
    }

    private static Scope resolveScope() {
        String raw = System.getProperty("star_sword.stateLock.scope", "client");
        String value = raw == null ? "" : raw.trim();
        if ("off".equalsIgnoreCase(value) || "none".equalsIgnoreCase(value)) {
            return Scope.OFF;
        }
        if ("both".equalsIgnoreCase(value) || "all".equalsIgnoreCase(value)) {
            return Scope.BOTH;
        }
        return Scope.CLIENT;
    }

    /** 状态锁是否启用（任一侧）。 */
    public static boolean enabled() {
        return ENABLED && SCOPE != Scope.OFF;
    }

    /** 当前作用域。 */
    public static Scope scope() {
        return ENABLED ? SCOPE : Scope.OFF;
    }

    /** 该侧是否要锁值。 */
    public static boolean appliesOn(boolean clientSide) {
        if (!enabled()) {
            return false;
        }
        return clientSide ? SCOPE != Scope.OFF : SCOPE == Scope.BOTH;
    }

    /**
     * 写侧是否硬拒：启用后除本模组自己的写回（{@code SYNCED_WRITE_BYPASS}）外，
     * 受保护玩家身上任何 Float 同步写入都不落地。
     *
     * <p>与锁值的作用域<b>刻意分开</b>：写侧拦截本来就是"防守"，两端都要；锁值会改可见状态，
     * 默认只放在客户端。
     */
    public static boolean rejectStateWrites() {
        return ENABLED && SCOPE != Scope.OFF && REJECT_WRITES;
    }

    /** 护甲目标值 = 原版属性上限（解析失败时用 30）。 */
    public static double armorTarget() {
        try {
            if (Attributes.ARMOR instanceof RangedAttribute ranged) {
                return ranged.getMaxValue();
            }
        } catch (Throwable ignored) {
            // 属性表未初始化：退回兜底值
        }
        return ARMOR_FALLBACK;
    }

    /**
     * 每 tick / 登录时调用：把四项状态钉回满值。
     *
     * <p><b>只作用于星辉剑绑定的那个玩家实体</b>：门控是 {@link SwordGuard#isProtected}，
     * 其语义就是"该 UUID 与剑绑定（剑在背包，或离开不超过 20 秒宽限）"。别的玩家、别的实体一律不碰。
     *
     * <p><b>作用域</b>由 {@code star_sword.stateLock.scope} 决定，默认 {@code client}：只改客户端那份，
     * 不改变服务端任何行为（服务端的血量本来就由既有权威存储 + 写闸门负责）。
     *
     * @return 本次是否有任何一项被改写
     */
    public static boolean apply(Player player) {
        if (player == null || !enabled()) {
            return false;
        }
        boolean clientSide;
        try {
            clientSide = player.level() != null && player.level().isClientSide;
        } catch (Throwable ignored) {
            return false; // 构造早期：level 还没赋值
        }
        return applyOn(player, clientSide);
    }

    /**
     * 指定端调用（客户端 tick 用 {@code true}，服务端 tick 用 {@code false}），避免
     * {@code level().isClientSide} 在两侧都为真的边界情况（例如客户端上的非本地实体）。
     */
    public static boolean applyOn(Player player, boolean clientSide) {
        if (player == null || !appliesOn(clientSide)) {
            return false;
        }
        if (!isBoundOwner(player)) {
            return false;
        }
        boolean changed = false;
        changed |= clampMaxHealth(player);
        changed |= clampHealth(player);
        changed |= clampArmor(player);
        changed |= clampFood(player);
        if (changed) {
            ModLog.healthThrottled("VanillaStateLock.apply:" + player.getUUID(), ModLog.TICK_INTERVAL,
                    "状态锁[层=" + (clientSide ? "客户端" : "服务端") + " 每tick] 已把状态钉回原版满值",
                    player, describeState(player));
        }
        return changed;
    }

    /** 该玩家是否是与星辉剑绑定的那个实体（即"主人"，含 20 秒宽限）。 */
    private static boolean isBoundOwner(Player player) {
        try {
            return SwordGuard.isProtected(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 最大生命属性锁到 {@value #LOCK_HEALTH}。
     *
     * <p>必须一起锁：{@code LivingEntity.setHealth} 会把值夹到 {@code getMaxHealth()}，最大生命被改小
     * （装备 / 药水 / 外部模组）时，"血量 20"根本落不下去，而每 tick 又会重新请求 20，表现为反复抖动。
     */
    private static boolean clampMaxHealth(Player player) {
        try {
            AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth == null) {
                return false;
            }
            if (Math.abs(maxHealth.getBaseValue() - LOCK_HEALTH) < 1.0E-6D) {
                return false;
            }
            maxHealth.setBaseValue(LOCK_HEALTH);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 血量：权威存储 + setHealth，与每 tick 写回路径共用同一份值。 */
    private static boolean clampHealth(Player player) {
        try {
            if (Math.abs(player.getHealth() - LOCK_HEALTH) < 1.0E-4F) {
                return false;
            }
            PlayerDefense.lockHealth(player, LOCK_HEALTH);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 护甲：只写 baseValue（装备/附魔是 modifier，不受影响）。 */
    private static boolean clampArmor(Player player) {
        try {
            AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
            if (armor == null) {
                return false;
            }
            double target = armorTarget();
            if (Math.abs(armor.getBaseValue() - target) < 1.0E-6D) {
                return false;
            }
            armor.setBaseValue(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 饱食度 + 饱和度。 */
    private static boolean clampFood(Player player) {
        try {
            FoodData food = player.getFoodData();
            boolean changed = false;
            if (food.getFoodLevel() != LOCK_FOOD) {
                food.setFoodLevel(LOCK_FOOD);
                changed = true;
            }
            if (Math.abs(food.getSaturationLevel() - LOCK_SATURATION) >= 1.0E-4F) {
                food.setSaturation(LOCK_SATURATION);
                changed = true;
            }
            return changed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 当前四项状态的一行摘要（日志用）。 */
    public static String describeState(Player player) {
        try {
            return "血量=" + player.getHealth() + " 护甲=" + armorBase(player)
                    + " 饱食=" + player.getFoodData().getFoodLevel()
                    + " 饱和=" + player.getFoodData().getSaturationLevel();
        } catch (Throwable throwable) {
            return "状态读取失败=" + throwable;
        }
    }

    private static String armorBase(Player player) {
        try {
            AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
            return armor == null ? "无属性" : String.valueOf(armor.getBaseValue());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** {@code /starsword agent status} 用的一行。 */
    public static String describe() {
        if (!enabled()) {
            return "off（原版状态，不锁）";
        }
        return "作用域=" + (SCOPE == Scope.BOTH ? "两端" : "仅客户端")
                + " 血量/最大生命=" + LOCK_HEALTH + " 护甲=" + armorTarget() + " 饱食=" + LOCK_FOOD
                + " 饱和=" + LOCK_SATURATION
                + " 写侧=" + (REJECT_WRITES ? "拒绝全部 Float 写入（两端）" : "只锁值");
    }

    private static boolean parseBoolean(String property, boolean fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String value = raw.trim();
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        return fallback;
    }
}
