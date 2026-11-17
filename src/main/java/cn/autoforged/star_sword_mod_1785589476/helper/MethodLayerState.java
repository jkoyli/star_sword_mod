package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks;

/**
 * 方法层心跳：用两条互相独立的计数判断"我们的字节码注入还在不在"。
 *
 * <h2>要解决的问题</h2>
 * <p>客户端没有服务端那套"返回值与权威值矛盾"的读取口自检：{@code READ_TAMPERED} 的三个置位点
 * 有两个随 {@code getHealth} 方法体被整段替换而消失，剩下一个只在服务端 tick 里跑。于是
 * "客户端凭什么知道自己被覆盖"没有答案，{@code assignValues} 一类依赖该标记的处置会静默失效。
 *
 * <h2>做法</h2>
 * <p>两条心跳，互相独立：
 * <ul>
 *   <li><b>事件侧</b>：客户端 tick 每 tick 自增（走 Forge 事件，与我们的字节码无关，
 *       只要模组在跑就一定增长）；</li>
 *   <li><b>字节码侧</b>：{@link AgentHooks#hookCallCount()}，只在 agent 的
 *       {@code getHealth} 整段替换真的被调用时才增长。</li>
 * </ul>
 * 每个窗口（{@value #WINDOW_TICKS} tick）比一次：事件侧在涨、字节码侧不涨 → 注入已不在
 * （被覆盖或从未应用）；两个都涨 → 方法层在。判据不依赖任何血量数值，因此不会因为
 * "外部实现这次没把值改坏"而误判为正常。
 *
 * <p><b>为什么用 {@code getHealth} 的计数而不是 {@code isAlive} 之类更热的方法</b>：我们真正在意的是
 * "读数入口还在不在"。外部实现只替换 {@code getHealth} 时，{@code isAlive} 的注入仍会正常跑，
 * 拿它当心跳会得出"方法层在"的错误结论。
 */
public final class MethodLayerState {

    /** 采样窗口：100 tick ≈ 5 秒。 */
    public static final int WINDOW_TICKS = 100;

    private static volatile Boolean present;
    private static volatile String detail = "尚未采样";
    private static long lastHookCalls = -1L;
    private static int ticks;

    private MethodLayerState() {
    }

    /**
     * 客户端每 tick 调用（世界加载后；未加载世界时传 {@code false} 只推进窗口不判定）。
     *
     * @param inWorld 本地玩家当前是否在世界里
     */
    public static void tick(boolean inWorld) {
        if (++ticks < WINDOW_TICKS) {
            return;
        }
        ticks = 0;
        if (!inWorld) {
            detail = "本地玩家不在世界中，跳过本窗口";
            return;
        }
        long calls = AgentHooks.hookCallCount();
        if (lastHookCalls < 0L) {
            lastHookCalls = calls;
            detail = "基线建立（hookCalls=" + calls + "）";
            return;
        }
        long delta = calls - lastHookCalls;
        lastHookCalls = calls;
        boolean nowPresent = delta > 0L;
        if (present == null || present != nowPresent) {
            ModLog.log(ModLog.AGENT, "方法层心跳: " + (nowPresent ? "在" : "不在")
                    + "（" + WINDOW_TICKS + "t 内 getHealth 注入调用 +" + delta + "）");
        }
        present = nowPresent;
        detail = "getHealth 注入" + (nowPresent ? "在运行" : "未运行")
                + "（" + WINDOW_TICKS + "t 内 +" + delta + " 次调用）";
    }

    /** 是否已采样到结论。 */
    public static boolean sampled() {
        return present != null;
    }

    /** 方法层当前是否在（未采样时为 {@code false}）。 */
    public static boolean present() {
        return Boolean.TRUE.equals(present);
    }

    /** 一行摘要，供 {@code /starsword agent status} 展示。 */
    public static String describe() {
        Boolean value = present;
        if (value == null) {
            return "未采样（" + detail + "）";
        }
        return (value ? "在" : "不在") + " · " + detail;
    }
}
