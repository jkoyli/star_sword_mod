package cn.autoforged.star_sword_mod_1785589476.helper;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 读取侧自检的<b>纯逻辑</b>：不依赖 Minecraft 类型，便于离线验证。
 *
 * <p>动机（对局记录）：外部实现会在类加载期用「外部的运行期重定义器 + 外部的类变换器」把
 * {@code getHealth()} 等方法体整体替换成按外部私有状态换算出的读数，
 * 完全不看本模组的 {@code isProtected}。写入侧五层防御全部赢了也没用——所有消费者读的是被替换后的
 * 返回值。因此读取侧必须自检，且判定只能依赖“报告值与内部权威值矛盾”这一事实，不能依赖
 * 注入点是否还在、优先级高低、或外部实现究竟是谁。
 *
 * <p>本类只做纯计算，状态（谁被篡改、哪些通道被冻结）由 {@code PlayerDefense} 持有。
 */
public final class ReadSideGuard {

    /** 报告值是否明显非法：NaN / 无限 / 负数。 */
    public static boolean isIllegalReport(float reported) {
        return Float.isNaN(reported) || Float.isInfinite(reported) || reported < 0.0F;
    }

    /** 内部权威值能否用于覆盖：必须是有限正数（0 表示我们自己也知道他死了，不该覆盖）。 */
    public static boolean hasAuthority(float authoritative) {
        return !Float.isNaN(authoritative) && !Float.isInfinite(authoritative) && authoritative > 0.0F;
    }

    /**
     * 纠正读取值。
     *
     * @param reported      读取口原本要返回的值
     * @param authoritative 内部权威血量
     * @return 有权威值时一律返回权威值；无权威值时原样返回
     */
    public static float correct(float reported, float authoritative) {
        if (!hasAuthority(authoritative)) {
            return reported;
        }
        if (isIllegalReport(reported) || reported != authoritative) {
            return authoritative;
        }
        return reported;
    }

    /**
     * 一个私有 Float 同步通道的行为采样器：“只增不减”即可疑累加器。
     *
     * <p>不认 key 名字：外部实现把外部累加量放在自己的 {@code EntityDataAccessor<Float>} 里，
     * 与原版血量键无关，只认 key 的拦截必然漏掉它。这里只观察行为：
     * <ul>
     *   <li>连续 {@link #increasesNeeded()} 次写入比上次大 → 判为累加器；</li>
     *   <li>任何一次回落都会把连续计数清零，合法通道（吸收量等）因此不会被长期误判；</li>
     *   <li>是否真的冻结由调用方结合“读取侧是否已被证明篡改”决定。</li>
     * </ul>
     *
     * <p>线程安全：每个玩家一个实例，只在服务端主线程 / 该玩家的同步线程里使用。
     */
    public static final class ChannelFreezer {

        /** 通道 -> 采样状态（按对象身份区分 accessor 实例）。 */
        private final Map<Object, Probe> probes = new IdentityHashMap<>();

        private final int increasesNeeded;

        public ChannelFreezer() {
            this(3);
        }

        public ChannelFreezer(int increasesNeeded) {
            this.increasesNeeded = Math.max(1, increasesNeeded);
        }

        public int increasesNeeded() {
            return increasesNeeded;
        }

        /**
         * 记录一次写入。
         *
         * @return true 表示该通道已连续只增不减，属于可疑累加器（调用方据上下文决定是否拒绝写入）
         */
        public boolean record(Object channel, float value) {
            if (channel == null) {
                return false;
            }
            Probe probe = probes.get(channel);
            if (probe == null) {
                probe = new Probe(value);
                probes.put(channel, probe);
                return false;
            }
            boolean increasing = value > probe.lastValue;
            probe.lastValue = value;
            probe.increaseStreak = increasing ? probe.increaseStreak + 1 : 0;
            return probe.increaseStreak >= increasesNeeded;
        }

        /** 该通道最近一次被观察到连续递增了多少次（诊断用）。 */
        public int increaseStreakOf(Object channel) {
            Probe probe = probes.get(channel);
            return probe == null ? 0 : probe.increaseStreak;
        }

        /** 清空所有通道采样（玩家退役 / 读取口恢复时调用）。 */
        public void clear() {
            probes.clear();
        }

        private static final class Probe {
            float lastValue;
            int increaseStreak;

            Probe(float lastValue) {
                this.lastValue = lastValue;
            }
        }
    }

    /**
     * 原始数据项的值能否直接采用：必须是有限的非负数（0 表示确实死了，合法）。
     */
    public static boolean isUsableRaw(float raw) {
        return !Float.isNaN(raw) && !Float.isInfinite(raw) && raw >= 0.0F;
    }

    /**
     * 没有权威旁路记录时（典型是客户端）的纠正：以原版血量数据项为准。
     *
     * <p>客户端的数据项由服务端同步而来（服务端每 tick 把权威值写回 DATA_HEALTH_ID），
     * 天然就是权威值；被替换的只是 {@code getHealth()} 这个方法体。所以这里直接读数据项，
     * 不依赖 {@code REAL_HEALTH}（客户端那个 Map 可能为空或尚未收到同步包）。
     */
    public static float preferRaw(float reported, float raw) {
        if (!isUsableRaw(raw)) {
            return reported;
        }
        if (isIllegalReport(reported) || reported != raw) {
            return raw;
        }
        return reported;
    }

    private ReadSideGuard() {
    }
}
