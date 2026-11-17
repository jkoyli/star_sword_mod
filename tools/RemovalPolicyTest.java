import cn.autoforged.star_sword_mod_1785589476.helper.RemovalPolicy;

/**
 * 离线验证 {@link RemovalPolicy}（纯逻辑，不依赖 Minecraft，也不需要启动游戏）。
 *
 * <p>运行方式见 README「判定逻辑的离线验证」：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test ^
 *   src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/RemovalPolicy.java ^
 *   tools/RemovalPolicyTest.java
 * java -cp build/tools-test RemovalPolicyTest
 * </pre>
 */
public final class RemovalPolicyTest {

    private static int failures;

    public static void main(String[] args) {
        boolean live = true;
        boolean stale = false;

        // ---- 当前会话玩家：只有两条理由放行，其余一律拒绝 ----
        deny("当前会话 + KILLED", RemovalPolicy.Reason.KILLED, live);
        deny("当前会话 + DISCARDED（直调 discard 的攻击面）", RemovalPolicy.Reason.DISCARDED, live);
        deny("当前会话 + UNLOADED_TO_CHUNK", RemovalPolicy.Reason.UNLOADED_TO_CHUNK, live);
        deny("当前会话 + 未知/未来新增理由", RemovalPolicy.Reason.UNKNOWN, live);

        allow("当前会话 + UNLOADED_WITH_PLAYER（退服/踢出）",
                RemovalPolicy.Reason.UNLOADED_WITH_PLAYER, live);
        allow("当前会话 + CHANGED_DIMENSION（传送门）",
                RemovalPolicy.Reason.CHANGED_DIMENSION, live);

        // ---- 旧实体（重生替换 / 重复登录清理 / 离线残留）：一律放行，否则留幽灵 ----
        allow("旧实体 + KILLED", RemovalPolicy.Reason.KILLED, stale);
        allow("旧实体 + DISCARDED（重生时清理旧实体）", RemovalPolicy.Reason.DISCARDED, stale);
        allow("旧实体 + 未知理由", RemovalPolicy.Reason.UNKNOWN, stale);
        allow("旧实体 + UNLOADED_TO_CHUNK", RemovalPolicy.Reason.UNLOADED_TO_CHUNK, stale);

        // ---- 没有理由 = 没在移除 ----
        check("reason=null 放行", RemovalPolicy.isAllowed(null, live));

        // ---- 枚举名映射：未来新增理由必须落到 UNKNOWN 并被拒绝 ----
        eq("reasonOf(\"KILLED\")", RemovalPolicy.Reason.KILLED, RemovalPolicy.reasonOf("KILLED"));
        eq("reasonOf(\"DISCARDED\")", RemovalPolicy.Reason.DISCARDED, RemovalPolicy.reasonOf("DISCARDED"));
        eq("reasonOf(\"CHANGED_DIMENSION\")", RemovalPolicy.Reason.CHANGED_DIMENSION,
                RemovalPolicy.reasonOf("CHANGED_DIMENSION"));
        eq("reasonOf(\"SOUL_ERASED\") 未来理由", RemovalPolicy.Reason.UNKNOWN,
                RemovalPolicy.reasonOf("SOUL_ERASED"));
        eq("reasonOf(null)", RemovalPolicy.Reason.UNKNOWN, RemovalPolicy.reasonOf(null));
        deny("字符串映射出的未知理由在当前会话被拒绝", RemovalPolicy.reasonOf("SOUL_ERASED"), live);

        // ---- 对照旧实现：旧黑名单只拦 KILLED/DISCARDED，下面这些以前会被放行 ----
        deny("对照旧黑名单：UNLOADED_TO_CHUNK 以前放行，现在拒绝",
                RemovalPolicy.Reason.UNLOADED_TO_CHUNK, live);
        deny("对照旧黑名单：未知/未来理由以前放行，现在拒绝",
                RemovalPolicy.Reason.UNKNOWN, live);
        System.out.println("旧黑名单（只拦 KILLED/DISCARDED）-> 新白名单（默认拒绝，仅两条放行）");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void allow(String name, RemovalPolicy.Reason reason, boolean live) {
        check(name + " 放行", RemovalPolicy.isAllowed(reason, live));
    }

    private static void deny(String name, RemovalPolicy.Reason reason, boolean live) {
        check(name + " 拒绝", !RemovalPolicy.isAllowed(reason, live));
    }

    private static void eq(String name, RemovalPolicy.Reason expected, RemovalPolicy.Reason actual) {
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

    private RemovalPolicyTest() {
    }
}
