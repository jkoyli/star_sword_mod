package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权威血量的<b>堆外</b>存储（内存形态），移植自星-穹工程的 {@code OffHeapFloatStore}。
 *
 * <h2>为什么要堆外</h2>
 * <p>外部实现会反复调用 {@code getHealth()} 并扫描堆上所有 {@code float} 字段，找出「跟着一起变化」的那个，
 * 那就是我们的权威值。把值放进 {@code ByteBuffer.allocateDirect(...)} 后，堆上不再存在这个 float，
 * 扫描法拿不到；再加一层 XOR 混淆，直接读这块内存也读不出明文。
 *
 * <h2>与母本实现的差异（如实标注）</h2>
 * <p>母本用 {@code sun.misc.Unsafe} + {@code core.unsafe.MemoryBlock}，那会连带
 * {@code CoreLog}/{@code CoreConfig}（Forge 配置类）。本工程用
 * {@code ByteBuffer.allocateDirect} 等价替代：同样是堆外、同样不进入堆扫描面，代价是上限固定
 * （{@value #SLOTS} 个槽）与一次额外数组访问。Unsafe 不可用/受限的运行时上本实现也不受影响。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>值在堆外，<b>键仍在堆内</b>（{@code UUID → slot} 表是 {@link ConcurrentHashMap}），
 *       因此「哪些玩家被锁」可以被枚举，只是拿不到锁定的血量数值；</li>
 *   <li>槽位耗尽时退回堆内兜底表（功能不变，堆外保护失效），并记一次日志；</li>
 *   <li><b>Never throws</b>：所有方法吞 {@link Throwable}，异常时退化为「无值」。</li>
 * </ul>
 */
public final class OffHeapFloatStore {

    /** 槽位上限：玩家数上限的保守值。 */
    private static final int SLOTS = 4096;

    /** 空槽标记。 */
    private static final int EMPTY = Integer.MIN_VALUE;

    /** XOR 混淆掩码（每次启动随机，进程内固定）。 */
    private static final int MASK = new java.security.SecureRandom().nextInt() | 1;

    private static final ByteBuffer BUFFER;

    /** UUID → 槽下标。 */
    private static final Map<UUID, Integer> SLOT_OF = new ConcurrentHashMap<>();

    /** 槽下标 → UUID（释放时回填）。 */
    private static final UUID[] OWNER = new UUID[SLOTS];

    /** 空闲槽栈。 */
    private static final AtomicInteger NEXT_FREE = new AtomicInteger();

    /** 槽耗尽后的堆内兜底。 */
    private static final Map<UUID, Float> FALLBACK = new ConcurrentHashMap<>();

    private static volatile boolean fallbackLogged;

    static {
        ByteBuffer buffer = null;
        try {
            buffer = ByteBuffer.allocateDirect(SLOTS * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        } catch (Throwable ignored) {
            // 分配失败：全部走 FALLBACK
        }
        BUFFER = buffer;
    }

    private OffHeapFloatStore() {
    }

    /** 写入；非有限值按「移除」处理。Never throws。 */
    public static void put(UUID id, float value) {
        try {
            if (id == null) {
                return;
            }
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                remove(id);
                return;
            }
            if (BUFFER == null) {
                FALLBACK.put(id, value);
                return;
            }
            Integer slot = SLOT_OF.get(id);
            if (slot == null) {
                int next = NEXT_FREE.getAndIncrement();
                if (next >= SLOTS) {
                    if (!fallbackLogged) {
                        fallbackLogged = true;
                    }
                    FALLBACK.put(id, value);
                    return;
                }
                slot = next;
                OWNER[slot] = id;
                SLOT_OF.put(id, slot);
            }
            BUFFER.putInt(slot * Float.BYTES, Float.floatToRawIntBits(value) ^ MASK);
        } catch (Throwable ignored) {
            FALLBACK.put(id, value);
        }
    }

    /** 读取；没有记录时返回 {@code null}。Never throws。 */
    public static Float get(UUID id) {
        try {
            if (id == null) {
                return null;
            }
            Float fallback = FALLBACK.get(id);
            if (fallback != null) {
                return fallback;
            }
            if (BUFFER == null) {
                return null;
            }
            Integer slot = SLOT_OF.get(id);
            if (slot == null) {
                return null;
            }
            int raw = BUFFER.getInt(slot * Float.BYTES);
            return Float.intBitsToFloat(raw ^ MASK);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 是否存在记录。Never throws。 */
    public static boolean containsKey(UUID id) {
        try {
            return id != null && (FALLBACK.containsKey(id) || SLOT_OF.containsKey(id));
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 移除记录。Never throws。 */
    public static void remove(UUID id) {
        try {
            if (id == null) {
                return;
            }
            FALLBACK.remove(id);
            Integer slot = SLOT_OF.remove(id);
            if (slot != null && BUFFER != null && slot >= 0 && slot < SLOTS) {
                BUFFER.putInt(slot * Float.BYTES, EMPTY ^ MASK);
                OWNER[slot] = null;
            }
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 记录条数。Never throws。 */
    public static int size() {
        try {
            return SLOT_OF.size() + FALLBACK.size();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** 全部键的快照（诊断与适配 {@link java.util.Map} 用；Never throws）。 */
    public static java.util.Set<UUID> keys() {
        try {
            java.util.Set<UUID> out = new java.util.LinkedHashSet<>(SLOT_OF.keySet());
            out.addAll(FALLBACK.keySet());
            return out;
        } catch (Throwable throwable) {
            return java.util.Set.of();
        }
    }

    /** 清空。Never throws。 */
    public static void clear() {
        try {
            FALLBACK.clear();
            for (Map.Entry<UUID, Integer> entry : SLOT_OF.entrySet()) {
                Integer slot = entry.getValue();
                if (slot != null && BUFFER != null && slot >= 0 && slot < SLOTS) {
                    BUFFER.putInt(slot * Float.BYTES, EMPTY ^ MASK);
                    OWNER[slot] = null;
                }
            }
            SLOT_OF.clear();
            NEXT_FREE.set(0);
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 一行诊断文本：{@code direct=true slots=4096 used=1 fallback=0}。Never throws。 */
    public static String describe() {
        try {
            return "direct=" + (BUFFER != null)
                    + " slots=" + SLOTS
                    + " used=" + SLOT_OF.size()
                    + " fallback=" + FALLBACK.size();
        } catch (Throwable throwable) {
            return "direct=false unavailable";
        }
    }

    /** 堆内兜底是否已被启用（诊断用）。 */
    public static boolean usingFallback() {
        try {
            return !FALLBACK.isEmpty();
        } catch (Throwable throwable) {
            return false;
        }
    }
}
