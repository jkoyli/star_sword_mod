import cn.autoforged.star_sword_mod_1785589476.helper.RejectStats;

import java.util.List;

/**
 * 离线断言：状态锁拒绝写入的 per-channel 汇总。
 *
 * <p>为什么要有这条：硬拒在 {@code shouldBlockSynchedWrite} 的 HEAD 提前 return，把原来的
 * 「通道探测」日志遮蔽了——"是谁在写哪个通道"只能靠这行汇总。如果排序或折叠写错，日志看起来
 * 正常但会得出错误结论（例如把只有 1 次的通道排在 37 次前面，或者把 5 条通道说成 3 条）。
 *
 * <p>运行：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test ^
 *   src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RejectStats.java ^
 *   tools/RejectStatsTest.java
 * java -cp build/tools-test RejectStatsTest
 * </pre>
 */
public final class RejectStatsTest {

    private static int failed;

    public static void main(String[] args) {
        // 1) 空输入：不产生日志行（调用方据此跳过 ModLog，避免空行刷屏）。
        check("".equals(RejectStats.describe(List.of(), 3)), "空输入返回空串");
        check("".equals(RejectStats.describe(null, 3)), "null 输入返回空串");

        // 2) 单通道：格式与实测一致（UOM 那次 id=20 值 -69.0）。
        String single = RejectStats.describe(List.of(new RejectStats.Sample(20, 1, -69.0F)), 3);
        check("次数=1 通道数=1 top=id=20×1(值=-69.0)".equals(single), "单通道格式: " + single);

        // 3) 排序：次数降序，且与传入顺序无关。
        String asc = RejectStats.describe(
                List.of(new RejectStats.Sample(8, 2, 0.5F), new RejectStats.Sample(20, 37, -69.0F),
                        new RejectStats.Sample(14, 2, 20.0F)), 3);
        check("次数=41 通道数=3 top=id=20×37(值=-69.0), id=8×2(值=0.5), id=14×2(值=20.0)".equals(asc),
                "按次数降序排序: " + asc);

        // 4) 次数相同时按通道 id 升序：保证日志可重复（ConcurrentHashMap 的遍历顺序不保证）。
        String tie = RejectStats.describe(
                List.of(new RejectStats.Sample(41, 5, 1.0F), new RejectStats.Sample(20, 5, 2.0F),
                        new RejectStats.Sample(14, 5, 3.0F)), 3);
        check(tie.contains("id=14×5") && tie.contains("id=20×5") && tie.contains("id=41×5")
                        && tie.indexOf("id=14") < tie.indexOf("id=20")
                        && tie.indexOf("id=20") < tie.indexOf("id=41"),
                "同次数按 id 升序: " + tie);

        // 5) top-N 折叠：只列前 N 条，其余折叠成 …，但 通道数 仍是真实数量。
        String folded = RejectStats.describe(
                List.of(new RejectStats.Sample(10, 1, 1.0F), new RejectStats.Sample(11, 2, 2.0F),
                        new RejectStats.Sample(12, 3, 3.0F), new RejectStats.Sample(13, 4, 4.0F),
                        new RejectStats.Sample(14, 5, 5.0F)), 3);
        check(folded.startsWith("次数=15 通道数=5 top=id=14×5"), "折叠时总次数/通道数正确: " + folded);
        check(folded.contains("id=13×4") && folded.contains("id=12×3") && !folded.contains("id=11×2"),
                "只列前 3 条: " + folded);
        check(folded.endsWith(" …"), "其余折叠成省略号: " + folded);

        // 6) maxShown 非法值（0 / 负数）至少列 1 条，而不是输出空的 top=。
        String clamped = RejectStats.describe(List.of(new RejectStats.Sample(3, 1, 0.0F)), 0);
        check("次数=1 通道数=1 top=id=3×1(值=0.0)".equals(clamped), "maxShown=0 时至少列 1 条: " + clamped);

        // 7) total() 与 describe 的总次数一致。
        List<RejectStats.Sample> samples = List.of(new RejectStats.Sample(1, 2, 0.0F),
                new RejectStats.Sample(2, 3, 0.0F));
        check(RejectStats.total(samples) == 5, "total() = 5");
        check(RejectStats.total(List.of()) == 0, "total(空) = 0");

        System.out.println(failed == 0 ? "ALL PASS" : "FAILED (" + failed + " 项)");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failed++;
        }
    }

    private RejectStatsTest() {
    }
}
