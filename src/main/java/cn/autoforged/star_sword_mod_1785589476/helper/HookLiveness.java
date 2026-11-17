package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 方法层<b>逐钩子存活率</b>观测（纯 JDK，可离线断言）。
 *
 * <h2>为什么不是"字节码哈希巡检"</h2>
 * <p>看起来更直接的做法是定期把类的字节码抓出来和基线比对，但这条路在 JVM 上<b>原理性无效</b>：
 * {@code Instrumentation.retransformClasses} / JVMTI {@code RetransformClasses} 交给 transformer 的
 * 永远是<b>类加载时的 initial 字节</b>，别人的 {@code redefineClasses} 不会被反映进去。也就是说
 * 「抓当前字节再哈希」永远等于基线，得到的是假绿灯（星-穹的 {@code MethodIntegrityGuard} 正是
 * 这个形态：登记表还恒空）。
 *
 * <p>唯一可靠、且不需要 native 的信号是<b>行为</b>：我们的注入如果还在，对应方法每 tick 都会被调用、
 * 计数就会涨；一旦被整体替换，计数立刻停。因此这里按钩子分别计数，回答「<b>哪一个</b>注入没了、
 * 什么时候没的」，而 {@code AgentStatus} 已有的全局计数只能回答「有没有东西没了」。
 *
 * <h2>判读</h2>
 * <ul>
 *   <li>{@link #WATCHED} 里的钩子是「只要世界在跑就必然被调用」的那几个；</li>
 *   <li>某个受监控钩子<b>先涨过、随后整个窗口一次不涨</b> → 记为静默（{@link #silentHooks()}）；</li>
 *   <li>从未涨过（{@code everFired=false}）不算静默——注入可能压根没装上，由 {@code MixinProbe}
 *       与 agent 的注入日志负责那一段，这里不重复告警。</li>
 * </ul>
 *
 * <p>热路径开销：一次 {@link ConcurrentHashMap} 查表 + 一次 {@link LongAdder} 自增 + 一次普通 long 写，
 * 方法体里不做任何字符串拼接，也不打日志。
 */
public final class HookLiveness {

    /** 「世界在跑就必然被调用」的钩子；只有这些参与静默判定。 */
    private static final Set<String> WATCHED = new LinkedHashSet<>(List.of(
            "getHealth", "isDeadOrDying", "isAlive"));

    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();

    private HookLiveness() {
    }

    /** 一次钩子入口记账。Never throws。 */
    public static void mark(String hook) {
        try {
            Slot slot = SLOTS.computeIfAbsent(hook, k -> new Slot());
            slot.total.increment();
            slot.lastCallAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
            // 观测失败绝不影响钩子本身
        }
    }

    /**
     * 推进一个观测窗口：把每个槽的累计值折算成「本窗口增量」，并维护静默标记。
     *
     * @return 本窗口内新进入静默的（受监控）钩子名
     */
    public static List<String> tickWindow() {
        List<String> newlySilent = new ArrayList<>();
        try {
            for (Map.Entry<String, Slot> entry : SLOTS.entrySet()) {
                Slot slot = entry.getValue();
                long total = slot.total.sum();
                long delta = total - slot.lastWindowTotal;
                slot.lastWindowTotal = total;
                slot.lastWindowDelta = delta;
                if (delta > 0) {
                    slot.everFired = true;
                    slot.silentWindows = 0;
                } else if (slot.everFired && WATCHED.contains(entry.getKey())) {
                    slot.silentWindows++;
                    if (slot.silentWindows == 1) {
                        newlySilent.add(entry.getKey());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        return newlySilent;
    }

    /** 当前处于静默的受监控钩子（窗口增量为 0 且曾经涨过）。 */
    public static List<String> silentHooks() {
        List<String> silent = new ArrayList<>();
        try {
            for (Map.Entry<String, Slot> entry : SLOTS.entrySet()) {
                Slot slot = entry.getValue();
                if (slot.everFired && slot.lastWindowDelta == 0 && WATCHED.contains(entry.getKey())) {
                    silent.add(entry.getKey());
                }
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        return silent;
    }

    /** 一条快照：{@code hook total=… delta=… everFired=… silentWindows=…}。 */
    public record Snapshot(String hook, long total, long windowDelta, boolean everFired, int silentWindows) {
    }

    /** 全部钩子的快照（诊断/状态命令用）。 */
    public static List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>();
        try {
            for (Map.Entry<String, Slot> entry : SLOTS.entrySet()) {
                Slot slot = entry.getValue();
                out.add(new Snapshot(entry.getKey(), slot.total.sum(), slot.lastWindowDelta,
                        slot.everFired, slot.silentWindows));
            }
        } catch (Throwable ignored) {
            // Never throws
        }
        out.sort((a, b) -> a.hook().compareTo(b.hook()));
        return out;
    }

    /** 一行汇总：{@code getHealth=+123 isDeadOrDying=+456 …（静默：isAlive）}。 */
    public static String describe() {
        try {
            List<Snapshot> all = snapshot();
            if (all.isEmpty()) {
                return "逐钩子计数：尚无调用（agent 未附加？）";
            }
            StringBuilder sb = new StringBuilder("逐钩子计数：");
            for (Snapshot snapshot : all) {
                sb.append(snapshot.hook()).append("=+").append(snapshot.windowDelta())
                        .append("(共").append(snapshot.total()).append(") ");
            }
            List<String> silent = silentHooks();
            if (!silent.isEmpty()) {
                sb.append("｜疑似失真：").append(String.join("/", silent));
            }
            return sb.toString().trim();
        } catch (Throwable throwable) {
            return "逐钩子计数：不可读";
        }
    }

    /** 清空（停服/测试用）。 */
    public static void reset() {
        try {
            SLOTS.clear();
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    private static final class Slot {
        final LongAdder total = new LongAdder();
        long lastWindowTotal;
        long lastWindowDelta;
        long lastCallAt;
        boolean everFired;
        int silentWindows;
    }
}
