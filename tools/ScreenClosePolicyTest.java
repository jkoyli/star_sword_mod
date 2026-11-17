import cn.autoforged.star_sword_mod_1785589476.helper.ScreenClosePolicy;

/**
 * 离线验证 {@link ScreenClosePolicy.Deferral}（纯逻辑，不依赖 Minecraft，也不需要启动游戏）。
 *
 * <p>运行方式见 README「判定逻辑的离线验证」：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test \
 *   src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ScreenClosePolicy.java \
 *   tools/ScreenClosePolicyTest.java
 * java -cp build/tools-test ScreenClosePolicyTest
 * </pre>
 */
public final class ScreenClosePolicyTest {

    private static int failures;

    public static void main(String[] args) {
        // ---- 没有请求时永远不关 ----
        ScreenClosePolicy.Deferral idle = new ScreenClosePolicy.Deferral(3);
        check("未请求时不关屏", !idle.tick(true) && !idle.tick(false));
        check("未请求时 isPending=false", !idle.isPending());

        // ---- 死亡态：先等，到上限强制关 ----
        ScreenClosePolicy.Deferral dying = new ScreenClosePolicy.Deferral(3);
        dying.request();
        check("第1 tick 仍在死亡态 -> 继续等", !dying.tick(true));
        check("第2 tick 仍在死亡态 -> 继续等", !dying.tick(true));
        check("第3 tick 到达上限 -> 强制关", dying.tick(true));
        check("强制关后挂起已清除", !dying.isPending());
        check("清除后不再重复关", !dying.tick(true));

        // ---- 死亡态提前结束：下一 tick 立刻关 ----
        ScreenClosePolicy.Deferral recovered = new ScreenClosePolicy.Deferral(40);
        recovered.request();
        check("仍在死亡态时继续等", !recovered.tick(true));
        check("死亡态复位后立刻关", recovered.tick(false));

        // ---- 挂起期间重复请求不得重置计时（否则强制关永远到不了） ----
        ScreenClosePolicy.Deferral spam = new ScreenClosePolicy.Deferral(5);
        spam.request();
        for (int i = 0; i < 4; i++) {
            check("重复请求不重置计时 i=" + i, !spam.tick(true));
            spam.request();   // 模拟每 tick 的 screen recovery 再次请求
        }
        check("第5 tick 仍然强制关（计时未被重置）", spam.tick(true));

        // ---- 默认上限 = 40 tick（2 秒） ----
        eq("默认上限", 40, ScreenClosePolicy.MAX_DEFER_TICKS);
        ScreenClosePolicy.Deferral dflt = new ScreenClosePolicy.Deferral();
        dflt.request();
        int ticks = 0;
        while (!dflt.tick(true) && ticks < 1000) {
            ticks++;
        }
        eq("默认上限下实际等待 tick 数", 39, ticks);
        check("第 40 tick 关屏", !dflt.isPending());

        // ---- 非法构造参数兜底为 1 ----
        ScreenClosePolicy.Deferral tiny = new ScreenClosePolicy.Deferral(0);
        tiny.request();
        check("maxDeferTicks<=0 时按 1 处理", tiny.tick(true));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void eq(String name, int expected, int actual) {
        report(name, expected == actual, "expected=" + expected + " actual=" + actual);
    }

    private static void check(String name, boolean ok) {
        report(name, ok, null);
    }

    private static void report(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
                + (detail != null && !ok ? "  [" + detail + "]" : ""));
    }

    private ScreenClosePolicyTest() {
    }
}
