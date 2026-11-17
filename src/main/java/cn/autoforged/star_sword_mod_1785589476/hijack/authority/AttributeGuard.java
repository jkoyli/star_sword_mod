package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import cn.autoforged.star_sword_mod_1785589476.helper.SwordGuard;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最大生命属性守卫：<b>记录基线、检测变化</b>，而不是每 tick 强行回滚。
 *
 * <h2>为什么是"检测"而不是"回滚"</h2>
 * <p>最大生命（{@code Attributes.MAX_HEALTH} 的 baseValue）是合法的、会被正常玩法改动的东西：
 * 装备、药水、其它模组的被动都可能调整它。每 tick 强制写回会和这些合法改动硬冲突。
 * 因此默认策略是：<b>记录首次观测到的基线，之后每 tick 比对，变化只告警</b>；
 * 需要回滚时由配置显式开启（{@code -Dstar_sword.attributeGuard=restore}）。
 *
 * <p>之所以仍然要盯它：一旦最大生命被改小，所有"按比例"换算出来的读数都会跟着失真，
 * 而这类失真通常表现为"看起来血量正常、实际判定按比例缩水"。
 *
 * <h2>配置</h2>
 * <ul>
 *   <li>{@code warn}（默认）：检测到变化只记一行日志（同一玩家同一数值只记一次）；</li>
 *   <li>{@code restore}：检测到变化立即写回基线值；</li>
 *   <li>{@code off}：完全不检查。</li>
 * </ul>
 *
 * <p><b>Never throws</b>；只对"受保护 且 有可用权威血量"的玩家生效。
 */
public final class AttributeGuard {

    /** 策略：{@code warn} / {@code restore} / {@code off}。 */
    private static final String MODE = System.getProperty("star_sword.attributeGuard", "warn").trim();

    /** 玩家 UUID → 首次观测到的最大生命。 */
    private static final Map<UUID, Double> BASELINE = new ConcurrentHashMap<>();

    /** 已经告警过的"玩家 + 数值"组合，避免刷屏。 */
    private static final Map<UUID, Double> LAST_REPORTED = new ConcurrentHashMap<>();

    private AttributeGuard() {
    }

    /** 当前策略（诊断用）。 */
    public static String mode() {
        return MODE;
    }

    /**
     * 每 tick 调用一次：记录基线并检测变化。
     *
     * @param player 目标玩家；未受保护或无权威血量时直接返回（不建立基线）
     */
    public static void check(Player player) {
        if (player == null || "off".equalsIgnoreCase(MODE)) {
            return;
        }
        try {
            UUID id = player.getUUID();
            if (!SwordGuard.isProtected(player) || !HealthAuthority.isAuthoritative(id)) {
                // 保护结束：丢弃基线，下次保护重新采样（避免把"上一个保护周期"的旧值当基线）
                BASELINE.remove(id);
                LAST_REPORTED.remove(id);
                return;
            }
            AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
            if (attribute == null) {
                return;
            }
            double current = attribute.getBaseValue();
            Double baseline = BASELINE.putIfAbsent(id, current);
            if (baseline == null) {
                return;
            }
            if (Math.abs(current - baseline) < 1.0E-6D) {
                return;
            }
            if ("restore".equalsIgnoreCase(MODE)) {
                attribute.setBaseValue(baseline);
                ModLog.log(ModLog.HEALTH, "最大生命被改动，已写回基线：" + baseline
                        + "（原值=" + current + "）player=" + player.getName().getString());
                LAST_REPORTED.remove(id);
                return;
            }
            Double reported = LAST_REPORTED.get(id);
            if (reported == null || Math.abs(reported - current) >= 1.0E-6D) {
                LAST_REPORTED.put(id, current);
                ModLog.log(ModLog.HEALTH, "最大生命发生变化：基线=" + baseline + " 当前=" + current
                        + "（策略=warn，未回滚；-Dstar_sword.attributeGuard=restore 可开启回滚）"
                        + " player=" + player.getName().getString());
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 清空某玩家的基线（退服/重生）。 */
    public static void forget(UUID id) {
        try {
            if (id != null) {
                BASELINE.remove(id);
                LAST_REPORTED.remove(id);
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 当前记录基线的玩家数（诊断用）。 */
    public static int trackedCount() {
        try {
            return BASELINE.size();
        } catch (Throwable throwable) {
            return 0;
        }
    }
}
