package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 状态锁拒绝写入的<b>纯聚合/格式化</b>逻辑。
 *
 * <p>为什么单独拎出来：这段逻辑（按通道计数 → 排序 → top-N → 折叠）是"看日志能不能定位到人"的唯一依靠，
 * 但它本身不依赖 Minecraft。抽成纯函数后可以离线断言（{@code tools/RejectStatsTest.java}），
 * 不必靠"跑一局刚好有人写通道"来验证。
 *
 * <p>调用方（{@code PlayerDefense.flushRejectedWrites}）只负责把窗口内的计数快照成 {@link Sample} 列表；
 * 排序与折叠都在这里做，且只在日志节拍里执行，不在写入热路径上。
 */
public final class RejectStats {

    /** 一条通道的窗口统计：通道 id、被拒次数、最近一次被拒的值。 */
    public record Sample(int channel, int count, float value) {
    }

    private RejectStats() {
    }

    /**
     * 生成一行汇总：{@code 次数=总 通道数=N top=id=20×37(值=-69.0), id=14×2(值=20.0) …}。
     *
     * <p>排序规则：次数降序；次数相同时按通道 id 升序（避免 {@code ConcurrentHashMap} 的遍历顺序让日志
     * 每次都不一样，回归测试也就没法断言）。
     *
     * @param samples  窗口内的通道统计；空/null 返回空串
     * @param maxShown 最多列出几条通道（至少 1）；其余折叠成 {@code …}
     */
    public static String describe(List<Sample> samples, int maxShown) {
        if (samples == null || samples.isEmpty()) {
            return "";
        }
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingInt(Sample::count).reversed()
                .thenComparingInt(Sample::channel));
        int total = 0;
        for (Sample sample : sorted) {
            total += sample.count();
        }
        int limit = Math.max(1, maxShown);
        StringBuilder sb = new StringBuilder("次数=").append(total)
                .append(" 通道数=").append(sorted.size())
                .append(" top=");
        int shown = 0;
        for (Sample sample : sorted) {
            if (shown >= limit) {
                sb.append(" …");
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            sb.append("id=").append(sample.channel()).append('×').append(sample.count())
                    .append("(值=").append(sample.value()).append(')');
            shown++;
        }
        return sb.toString();
    }

    /** 窗口内被拒总次数（诊断用，便于调用方决定要不要记日志）。 */
    public static int total(List<Sample> samples) {
        int total = 0;
        if (samples != null) {
            for (Sample sample : samples) {
                total += sample.count();
            }
        }
        return total;
    }
}
