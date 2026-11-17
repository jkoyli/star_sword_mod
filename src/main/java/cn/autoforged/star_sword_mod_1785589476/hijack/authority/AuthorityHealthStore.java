package cn.autoforged.star_sword_mod_1785589476.hijack.authority;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家血量的<b>服务端权威状态</b>（语义层）。移植自星-穹工程的 {@code HealthOverrideManager}。
 *
 * <p>对外只有一组静态方法，内部把值交给 {@link OffHeapFloatStore}（堆外 + XOR）。默认值策略与母本
 * 有意不同，如实标注：
 * <ul>
 *   <li>母本的不变式是「表里只存正值，非正值 = 解锁」；本工程的 {@code PlayerDefense} 已有完整的
 *       0 / 负数判定（{@code real >= 0} 才作数），因此这里<b>如实存储</b>有限值，只在写入
 *       {@code NaN}/{@code 无穷} 时按移除处理——避免把「0 血」这种语义在存储层悄悄变成「无记录」，
 *       让上层从「读到 0」变成「读到 null」。</li>
 *   <li>{@link #asMap()} 提供 {@link Map} 适配，使 {@code PlayerDefense.REAL_HEALTH} 的 37 处调用
 *       不需要重写，而存储已经换成权威存储——这正是「单一出口」的落地点。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：允许服务端 tick 线程与网络 / 渲染线程并发读写，内部无锁 O(1)。
 *
 * <p><b>异常契约</b>：所有方法 {@code try/catch(Throwable)} 兜底，<b>Never throws</b>。
 */
public final class AuthorityHealthStore {

    private AuthorityHealthStore() {
    }

    /** 写入权威血量；{@code NaN}/无穷 等价于移除。 */
    public static void set(UUID id, float value) {
        try {
            if (id == null) {
                return;
            }
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                OffHeapFloatStore.remove(id);
                return;
            }
            OffHeapFloatStore.put(id, value);
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 读取权威血量；没有记录时返回 {@code null}。 */
    public static Float get(UUID id) {
        try {
            return OffHeapFloatStore.get(id);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 是否存在权威血量。 */
    public static boolean has(UUID id) {
        try {
            return OffHeapFloatStore.containsKey(id);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 移除权威血量。 */
    public static void remove(UUID id) {
        try {
            OffHeapFloatStore.remove(id);
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 记录条数。 */
    public static int size() {
        try {
            return OffHeapFloatStore.size();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    /** 清空（退服 / 测试用）。 */
    public static void clear() {
        try {
            OffHeapFloatStore.clear();
        } catch (Throwable ignored) {
            // Never throws
        }
    }

    /** 一行诊断（启动横幅与状态命令用）。 */
    public static String describe() {
        try {
            return OffHeapFloatStore.describe();
        } catch (Throwable throwable) {
            return "direct=false unavailable";
        }
    }

    /**
     * {@link Map} 适配：把旧的 {@code Map<UUID, Float>} 用法原样接到权威存储上。
     *
     * <p>只实现读取 / 写入 / 删除 / 存在性 / 清空 / 大小 / 遍历快照这些真实被用到的语义；
     * 写操作返回<b>旧值</b>以符合 {@link Map#put} 契约。
     */
    public static Map<UUID, Float> asMap() {
        return MapHolder.INSTANCE;
    }

    private static final class MapHolder {

        private static final Map<UUID, Float> INSTANCE = new BackingMap();

        private MapHolder() {
        }
    }

    private static final class BackingMap extends AbstractMap<UUID, Float> {

        @Override
        public Float get(Object key) {
            return key instanceof UUID id ? AuthorityHealthStore.get(id) : null;
        }

        @Override
        public Float put(UUID key, Float value) {
            Float old = get(key);
            AuthorityHealthStore.set(key, value == null ? Float.NaN : value);
            return old;
        }

        @Override
        public Float remove(Object key) {
            if (!(key instanceof UUID id)) {
                return null;
            }
            Float old = AuthorityHealthStore.get(id);
            AuthorityHealthStore.remove(id);
            return old;
        }

        @Override
        public boolean containsKey(Object key) {
            return key instanceof UUID id && AuthorityHealthStore.has(id);
        }

        @Override
        public int size() {
            return AuthorityHealthStore.size();
        }

        @Override
        public void clear() {
            AuthorityHealthStore.clear();
        }

        @Override
        public Set<Map.Entry<UUID, Float>> entrySet() {
            return new EntrySet();
        }
    }

    private static final class EntrySet extends AbstractSet<Map.Entry<UUID, Float>> {

        @Override
        public Iterator<Map.Entry<UUID, Float>> iterator() {
            return new EntryIterator(OffHeapFloatStore.keys().iterator());
        }

        @Override
        public int size() {
            return AuthorityHealthStore.size();
        }
    }

    private static final class EntryIterator implements Iterator<Map.Entry<UUID, Float>> {

        private final Iterator<UUID> keys;

        private EntryIterator(Iterator<UUID> keys) {
            this.keys = keys;
        }

        @Override
        public boolean hasNext() {
            return keys.hasNext();
        }

        @Override
        public Map.Entry<UUID, Float> next() {
            UUID id = keys.next();
            return new AbstractMap.SimpleImmutableEntry<>(id, AuthorityHealthStore.get(id));
        }
    }
}
