package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 对账开销统计：给热路径上的几处拦截点做计数与耗时采样。
 *
 * <p>动机：写侧闸门、观察者广播、发包过滤都会在每 tick 被调用成百上千次；没有基线就无法判断
 * “这套防御是否值得”，也无法在出问题时区分“功能错了”还是“性能拖垮了服务器”。这里只记录
 * <b>窗口内</b>的调用数/命中数/平均与最大耗时，每 {@link #WINDOW_TICKS} tick 输出一行并清零。
 *
 * <p>开销：每次采样两次 {@code System.nanoTime()}（约几十纳秒）。开关跟随
 * {@link ModLog#STATS_ENABLED}（开发环境默认开、发布版默认关），可用
 * {@code -Dstar_sword.stats=false} 完全关闭。
 *
 * <p>输出示例（分类“统计”，仅开发环境可见）：
 * <pre>
 * [统计] window=200t getHealth calls=330000 hits=0 avg=61ns max=4200ns | writeGate ...
 * </pre>
 */
// [DEFENSE-MARK] 诊断：对账开销统计（写侧闸门/发包过滤/观察者广播/getHealth）。
public final class ReconcileStats {

    /** 统计窗口（tick）。200 tick ≈ 10 秒。 */
    public static final int WINDOW_TICKS = 200;

    /**
     * 跟随 {@link ModLog#STATS_ENABLED}：<b>开发环境默认打开，发布版默认关闭</b>。
     *
     * <p>采样本身（两次 {@code nanoTime} + 计数原子操作）在 getHealth 这种每窗口 33 万次的
     * 热路径上约占 55–76ns/次，因此绝不带进发布包；确需在生产环境排查时用
     * {@code -Dstar_sword.stats=true} 临时打开，{@code -Dstar_sword.stats=false} 强制关闭。
     */
    private static final boolean ENABLED = ModLog.STATS_ENABLED;

    /** 写侧闸门：{@code SynchedEntityData.set}。 */
    public static final Counter WRITE_GATE = new Counter();

    /** 读侧闸门：{@code LivingEntity.getHealth}。 */
    public static final Counter GET_HEALTH = new Counter();

    /** 发包侧：{@code Connection.send}。 */
    public static final Counter OUTGOING = new Counter();

    /** 观察者广播：{@code ChunkMap.broadcast}。 */
    public static final Counter OBSERVER = new Counter();

    private static final List<NamedCounter> ALL = List.of(
            new NamedCounter("getHealth", GET_HEALTH),
            new NamedCounter("writeGate", WRITE_GATE),
            new NamedCounter("outgoing", OUTGOING),
            new NamedCounter("observer", OBSERVER));

    private static final AtomicLong LAST_DUMP_TICK = new AtomicLong(Long.MIN_VALUE);

    private ReconcileStats() {
    }

    private record NamedCounter(String name, Counter counter) {
    }

    /** 计数器：窗口内累计，dump 后清零。 */
    public static final class Counter {
        private final LongAdder calls = new LongAdder();
        private final LongAdder hits = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        /** 开始采样；统计关闭时返回 0，{@link #end} 会直接忽略。 */
        public long start() {
            return ENABLED ? System.nanoTime() : 0L;
        }

        /** 结束采样。{@code start == 0} 表示未采样，直接返回。 */
        public void end(long start, boolean hit) {
            if (!ENABLED || start == 0L) {
                return;
            }
            long elapsed = System.nanoTime() - start;
            calls.increment();
            if (hit) {
                hits.increment();
            }
            totalNanos.add(elapsed);
            maxNanos.accumulateAndGet(elapsed, Math::max);
        }

        private String describeAndReset() {
            long c = calls.sumThenReset();
            long h = hits.sumThenReset();
            long total = totalNanos.sumThenReset();
            long max = maxNanos.getAndSet(0L);
            long avg = c == 0 ? 0 : total / c;
            return nameOf(this) + " calls=" + c + " hits=" + h + " avg=" + avg + "ns max=" + max + "ns";
        }

        private String nameOf(Counter counter) {
            for (NamedCounter named : ALL) {
                if (named.counter() == counter) {
                    return named.name();
                }
            }
            return "unknown";
        }
    }

    /** 每个玩家 tick 调用一次；到窗口边界时输出一行汇总。 */
    public static void tick(int tickCount) {
        if (!ENABLED) {
            return;
        }
        long last = LAST_DUMP_TICK.get();
        if (last != Long.MIN_VALUE && tickCount - last < WINDOW_TICKS) {
            return;
        }
        if (last != Long.MIN_VALUE && tickCount < last) {
            // 世界重载导致 tickCount 回退，重置窗口
            LAST_DUMP_TICK.set(tickCount);
            return;
        }
        LAST_DUMP_TICK.set(tickCount);
        dump();
    }

    /** 立即输出并清零（也便于测试里手动触发）。 */
    public static void dump() {
        if (!ENABLED) {
            return;
        }
        StringBuilder line = new StringBuilder("window=").append(WINDOW_TICKS).append('t');
        for (NamedCounter named : ALL) {
            line.append(" | ").append(named.counter().describeAndReset());
        }
        ModLog.log(ModLog.STATS, line.toString());
    }

    public static boolean enabled() {
        return ENABLED;
    }
}
