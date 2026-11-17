import cn.autoforged.star_sword_mod_1785589476.helper.ReadSideGuard;

/**
 * 离线验证 {@link ReadSideGuard} 的判定（纯逻辑，不依赖 Minecraft，也不需要启动游戏）。
 *
 * <p>运行方式见 README「判定逻辑的离线验证」一节：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test \
 *   src/main/java/cn/autoforged/star_sword_mod_1785589476/helper/ReadSideGuard.java \
 *   tools/ReadSideGuardTest.java
 * java -cp build/tools-test ReadSideGuardTest
 * </pre>
 *
 * <p>覆盖本次对局暴露的两类问题：
 * <ol>
 *   <li>读取口被整体替换后返回负数 / NaN —— 必须被纠正回内部权威值；</li>
 *   <li>soulDamage 这类私有 Float 通道只增不减 —— 必须被行为模式识别出来。</li>
 * </ol>
 */
public final class ReadSideGuardTest {

    private static int failures;

    public static void main(String[] args) {
        // ---- 读取侧纠正 ----
        eq("负数报告被纠正", 20.0F, ReadSideGuard.correct(-3.2F, 20.0F));
        eq("NaN 报告被纠正", 20.0F, ReadSideGuard.correct(Float.NaN, 20.0F));
        eq("无限报告被纠正", 20.0F, ReadSideGuard.correct(Float.POSITIVE_INFINITY, 20.0F));
        eq("合法且一致原样返回", 20.0F, ReadSideGuard.correct(20.0F, 20.0F));
        eq("合法但不一致仍以权威值为准", 20.0F, ReadSideGuard.correct(5.0F, 20.0F));
        eq("无权威值（0）时不覆盖负数", -1.0F, ReadSideGuard.correct(-1.0F, 0.0F));
        eq("无权威值（NaN）时原样返回", -1.0F, ReadSideGuard.correct(-1.0F, Float.NaN));

        check("hasAuthority(20)", ReadSideGuard.hasAuthority(20.0F));
        check("!hasAuthority(0)", !ReadSideGuard.hasAuthority(0.0F));
        check("!hasAuthority(-5)", !ReadSideGuard.hasAuthority(-5.0F));
        check("!hasAuthority(NaN)", !ReadSideGuard.hasAuthority(Float.NaN));
        check("isIllegalReport(-0.5)", ReadSideGuard.isIllegalReport(-0.5F));
        check("!isIllegalReport(0)", !ReadSideGuard.isIllegalReport(0.0F));

        // ---- 无旁路记录时的原始数据项兜底（客户端路径）----
        eq("数据项可用时替换非法报告值", 20.0F, ReadSideGuard.preferRaw(-3.2F, 20.0F));
        eq("数据项可用时替换不一致的报告值", 20.0F, ReadSideGuard.preferRaw(12.0F, 20.0F));
        eq("报告值与数据项一致时原样返回", 20.0F, ReadSideGuard.preferRaw(20.0F, 20.0F));
        eq("数据项不可用（NaN）时不改", -3.2F, ReadSideGuard.preferRaw(-3.2F, Float.NaN));
        eq("数据项不可用（负数）时不改", 5.0F, ReadSideGuard.preferRaw(5.0F, -1.0F));
        eq("数据项为 0（确实死了）且报告为负时采用 0", 0.0F, ReadSideGuard.preferRaw(-2.0F, 0.0F));
        check("isUsableRaw(0)", ReadSideGuard.isUsableRaw(0.0F));
        check("!isUsableRaw(NaN)", !ReadSideGuard.isUsableRaw(Float.NaN));
        check("!isUsableRaw(-1)", !ReadSideGuard.isUsableRaw(-1.0F));

        // ---- 私有通道采样：只增不减 -> 可疑 ----
        ReadSideGuard.ChannelFreezer freezer = new ReadSideGuard.ChannelFreezer();
        Object soul = new Object();
        check("第1次写入不算可疑", !freezer.record(soul, 0.1F));
        check("第2次递增（streak=1）", !freezer.record(soul, 0.3F));
        check("第3次递增（streak=2）", !freezer.record(soul, 0.5F));
        check("第4次递增（streak=3）判为可疑", freezer.record(soul, 0.7F));
        eq("追平 streak", 3, freezer.increaseStreakOf(soul));

        // 回落清零：合法通道偶尔回落后重新计数
        ReadSideGuard.ChannelFreezer reset = new ReadSideGuard.ChannelFreezer();
        Object ch = new Object();
        reset.record(ch, 1.0F);
        reset.record(ch, 2.0F);   // +1
        reset.record(ch, 1.5F);   // 回落 -> 0
        reset.record(ch, 1.6F);   // +1
        check("回落不会被判可疑", !reset.record(ch, 1.7F));
        check("回落之后累计到 3 才判可疑", reset.record(ch, 1.8F));

        // 不同通道互不影响
        ReadSideGuard.ChannelFreezer multi = new ReadSideGuard.ChannelFreezer();
        Object a = new Object();
        Object b = new Object();
        multi.record(a, 1.0F);
        multi.record(b, 100.0F);
        multi.record(a, 2.0F);
        multi.record(b, 90.0F);   // b 回落
        multi.record(a, 3.0F);
        multi.record(a, 4.0F);
        check("通道 a 独立累计到可疑", multi.record(a, 5.0F));
        eq("通道 b 仍为 0", 0, multi.increaseStreakOf(b));

        // clear 后重新开始
        multi.clear();
        check("clear 后不再可疑", !multi.record(a, 6.0F));
        eq("clear 后 streak 归零", 0, multi.increaseStreakOf(a));

        // 模拟本次对局：soulDamage 只增不减，越过 1 后 getHealth 变负数
        ReadSideGuard.ChannelFreezer attack = new ReadSideGuard.ChannelFreezer();
        Object soulDamage = new Object();
        float[] beams = {0.2F, 0.45F, 0.7F, 1.05F};
        boolean frozenAt = false;
        float soulValue = 0.0F;
        for (float beam : beams) {
            soulValue += beam;
            if (attack.record(soulDamage, soulValue)) {
                frozenAt = true;
                break;
            }
        }
        check("攻击序列中触发冻结", frozenAt);
        float reported = Math.min(20.0F, 20.0F * (1.0F - soulValue));
        check("被篡改的读数为负", reported < 0.0F);
        eq("读取侧纠正回 20", 20.0F, ReadSideGuard.correct(reported, 20.0F));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void eq(String name, float expected, float actual) {
        boolean ok = Float.compare(expected, actual) == 0;
        report(name, ok, "expected=" + expected + " actual=" + actual);
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

    private ReadSideGuardTest() {
    }
}
