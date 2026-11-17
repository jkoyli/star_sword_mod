package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部同步通道的<b>趋势观测</b>（纯 JDK，可离线断言）。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code ForeignChannelGuard} 已经能枚举玩家身上所有浮点同步项并判定"该不该中和"，但它只保留
 * 「最后一次值 + 命中次数」。真正的判据是<b>走势</b>：那条通道是不是在<b>只增不减</b>，
 * 以及有没有<b>越过 1.0</b>——因为 {@code 20 × (1 − v)} 这类换算式在 {@code v > 1} 之后才变负，
 * 而"负血"正是客户端判死的起点。
 *
 * <p>所以本类只做三件事：
 * <ol>
 *   <li>按（实体, 通道 id）累计样本：首值 / 末值 / 峰值 / 增量总和 / 连续递增次数 / 观察次数；</li>
 *   <li>报告<b>首次越过阈值</b>的通道（默认 1.0），调用方据此打一条可解释的日志；</li>
 *   <li>提供一条按增量排序的摘要，供 {@code /starsword agent status} 展示。</li>
 * </ol>
 *
 * <h2>为什么附带 Pearson</h2>
 * <p>"哪条通道在和某件事一起变"用相关系数回答比肉眼看日志快。但要注意：<b>参考序列必须真的是
 * 独立信号</b>。把血量当参考是错的——血量被锁在 20 时它是常数，方差为 0，相关系数恒为
 * {@code NaN}（本类对零方差返回 NaN，不返回 0，避免被读成"无关")。
 *
 * <p><b>Never throws</b>；热路径只做一次 ConcurrentHashMap 查表与几个字段赋值。
 */
public final class ChannelTrend {

    /** 公式变负的临界点：{@code 20 × (1 − v)} 在 {@code v > 1} 后为负。 */
    public static final float NEGATIVE_FORMULA_THRESHOLD = 1.0F;

    /** 最多跟踪的通道数，防止异常场景把表撑爆。 */
    private static final int MAX_CHANNELS = 128;

    /** 键：{@code ownerUuid + "#" + channelId}。 */
    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();

    private ChannelTrend() {
    }

    /**
     * 记一次观察。
     *
     * @param owner     实体 UUID（或任何稳定标识）
     * @param channelId 同步通道 id
     * @param value     当前值
     * @return true 表示本次观察<b>刚刚</b>越过 {@link #NEGATIVE_FORMULA_THRESHOLD}（首次越线）
     */
    public static boolean observe(Object owner, int channelId, float value) {
        try {
            if (owner == null || Float.isNaN(value) || Float.isInfinite(value)) {
                return false;
            }
            String key = owner + "#" + channelId;
            Slot slot = SLOTS.get(key);
            if (slot == null) {
                if (SLOTS.size() >= MAX_CHANNELS) {
                    return false;
                }
                slot = new Slot(channelId, value);
                SLOTS.put(key, slot);
                return false;
            }
            return slot.record(value);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 一条通道的只读快照。 */
    public record Entry(String owner, int channelId, float first, float last, float peak,
                        float totalDelta, int samples, int increaseStreak, boolean crossedThreshold) {
        /** 平均每次观察的增量（样本不足时为 0）。 */
        public float averageDelta() {
            return samples <= 1 ? 0.0F : totalDelta / (samples - 1);
        }
    }

    /** 全部通道快照，按 |增量总和| 从大到小。 */
    public static List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>();
        try {
            for (Map.Entry<String, Slot> entry : SLOTS.entrySet()) {
                Slot slot = entry.getValue();
                out.add(new Entry(entry.getKey(), slot.channelId, slot.first, slot.last, slot.peak,
                        slot.last - slot.first, slot.samples, slot.increaseStreak, slot.crossedThreshold));
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        out.sort((a, b) -> Float.compare(Math.abs(b.totalDelta()), Math.abs(a.totalDelta())));
        return out;
    }

    /** 已被判定为「只增不减」的通道（连续递增 ≥ 3 次）。 */
    public static List<Entry> accumulators() {
        List<Entry> out = new ArrayList<>();
        try {
            for (Entry entry : snapshot()) {
                if (entry.increaseStreak() >= 3) {
                    out.add(entry);
                }
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        return out;
    }

    /** 一行摘要（最多列 5 条，按增量排序）。 */
    public static String describe() {
        try {
            List<Entry> all = snapshot();
            if (all.isEmpty()) {
                return "外部通道趋势：尚无样本";
            }
            StringBuilder sb = new StringBuilder("外部通道趋势：");
            int shown = 0;
            for (Entry entry : all) {
                if (shown++ >= 5) {
                    break;
                }
                if (shown > 1) {
                    sb.append("；");
                }
                sb.append(channelOf(entry)).append(' ')
                        .append(String.format(java.util.Locale.ROOT, "%.3f→%.3f", entry.first(), entry.last()))
                        .append(" 递增x").append(entry.increaseStreak());
            }
            if (all.size() > 5) {
                sb.append("；…共").append(all.size()).append("条");
            }
            return sb.toString();
        } catch (Throwable throwable) {
            return "外部通道趋势：不可读";
        }
    }

    /** 供日志用的一行明细：{@code 通道=8 0.030→0.250 递增x5 越线=true}。 */
    public static String detail(Entry entry) {
        if (entry == null) {
            return "<null>";
        }
        return "通道=" + entry.channelId()
                + String.format(java.util.Locale.ROOT, " %.3f→%.3f", entry.first(), entry.last())
                + " 峰值=" + String.format(java.util.Locale.ROOT, "%.3f", entry.peak())
                + " 递增x" + entry.increaseStreak()
                + " 样本=" + entry.samples()
                + " 越线=" + entry.crossedThreshold();
    }

    /**
     * Pearson 相关系数。样本不足或任一序列方差为 0 时返回 {@link Double#NaN}。
     *
     * <p>刻意不返回 0：0 表示"确实无关"，而"没法算"与"无关"是两件事——锁血时血量是常数，
     * 这里必须得到 NaN，否则会把"参考线选错了"误读成"没有相关性"。
     */
    public static double pearson(double[] x, double[] y) {
        try {
            if (x == null || y == null || x.length != y.length || x.length < 2) {
                return Double.NaN;
            }
            int n = x.length;
            double sumX = 0.0D;
            double sumY = 0.0D;
            for (int i = 0; i < n; i++) {
                sumX += x[i];
                sumY += y[i];
            }
            double meanX = sumX / n;
            double meanY = sumY / n;
            double sxx = 0.0D;
            double syy = 0.0D;
            double sxy = 0.0D;
            for (int i = 0; i < n; i++) {
                double dx = x[i] - meanX;
                double dy = y[i] - meanY;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
            if (sxx <= 1.0E-12D || syy <= 1.0E-12D) {
                return Double.NaN;
            }
            return sxy / Math.sqrt(sxx * syy);
        } catch (Throwable throwable) {
            return Double.NaN;
        }
    }

    /** 清空（退服 / 停机 / 测试）。 */
    public static void reset() {
        try {
            SLOTS.clear();
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 只清某个实体（退服时用）。 */
    public static void resetOwner(Object owner) {
        try {
            if (owner == null) {
                return;
            }
            String prefix = owner + "#";
            SLOTS.keySet().removeIf(key -> key.startsWith(prefix));
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    private static String channelOf(Entry entry) {
        int hash = entry.owner().indexOf('#');
        String who = hash < 0 ? entry.owner() : entry.owner().substring(hash + 1);
        return "通道=" + who;
    }

    private static final class Slot {
        private final int channelId;
        private final float first;
        private float last;
        private float peak;
        private int samples;
        private int increaseStreak;
        private boolean crossedThreshold;

        private Slot(int channelId, float first) {
            this.channelId = channelId;
            this.first = first;
            this.last = first;
            this.peak = first;
            this.samples = 1;
        }

        /** @return true 表示本次观察首次越过阈值 */
        private boolean record(float value) {
            boolean crossed = !crossedThreshold && value >= NEGATIVE_FORMULA_THRESHOLD;
            if (crossed) {
                crossedThreshold = true;
            }
            if (value > last) {
                increaseStreak++;
            } else if (value < last) {
                increaseStreak = 0;
            }
            last = value;
            if (value > peak) {
                peak = value;
            }
            samples++;
            return crossed;
        }
    }

    /** 只读的键值视图（供诊断拼接；不参与判定）。 */
    public static Map<String, String> keySummary() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Entry entry : snapshot()) {
                out.put(entry.owner(), detail(entry));
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        return out;
    }
}
