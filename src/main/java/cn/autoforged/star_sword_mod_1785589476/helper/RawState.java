package cn.autoforged.star_sword_mod_1785589476.helper;

import cn.autoforged.star_sword_mod_1785589476.util.ReflectionHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 同步数据的“底层直读”工具：只碰字段，不调用任何 getter。
 *
 * <p>为什么重要：本模组与其它模组争夺的是同一个数据源，而 {@code getHealth()}、
 * {@code SynchedEntityData.get(...)}、{@code DataItem.getValue()} 全都是**可被外部 Mixin 改写的方法**。
 * 只要判定逻辑读的是方法，外部实现就能让它读到假值；读字段则无从拦截。
 *
 * <p>覆盖的字段（括号内为 SRG 名）：
 * <ul>
 *   <li>{@code SynchedEntityData.entity}（{@code f_135344_}）：创建该同步数据的实体</li>
 *   <li>{@code SynchedEntityData.itemsById}（{@code f_135345_}）：数据键 id → 数据项</li>
 *   <li>{@code SynchedEntityData$DataItem.value}（{@code f_135391_}）：数据项里的当前值</li>
 *   <li>{@code EntityDataAccessor.serializer}（{@code f_135011_}）：数据键的序列化器</li>
 * </ul>
 *
 * <p>所有方法都不抛异常：任何反射失败都退化成 {@code null}，由调用方按“无意见”处理。
 */
public final class RawState {

    private static final Field DATA_ENTITY =
            ReflectionHelper.findFieldCompat(SynchedEntityData.class, "f_135344_", "entity");
    private static final Field ENTITY_DATA =
            ReflectionHelper.findFieldCompat(Entity.class, "f_19804_", "entityData");
    private static final Field ITEMS_BY_ID =
            ReflectionHelper.itemsByIdField();
    private static final Field ITEM_VALUE =
            ReflectionHelper.findFieldCompat(SynchedEntityData.DataItem.class, "f_135391_", "value");
    private static final Field ACCESSOR_SERIALIZER =
            ReflectionHelper.findFieldCompat(EntityDataAccessor.class, "f_135011_", "serializer");

    private RawState() {
    }

    /**
     * 实体的同步数据容器（直读 {@code Entity.entityData} 字段，不经过
     * {@code Entity.getEntityData()} 这个可被替换的方法）。
     */
    public static SynchedEntityData dataOf(Entity entity) {
        if (entity == null || ENTITY_DATA == null) {
            return null;
        }
        try {
            return (SynchedEntityData) ENTITY_DATA.get(entity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 实体的某个数据键里**当前真实存着的浮点值**；读不到或类型不符时返回 {@code NaN}。 */
    public static float rawFloatOf(Entity entity, EntityDataAccessor<Float> key) {
        try {
            SynchedEntityData data = dataOf(entity);
            if (data == null || key == null) {
                return Float.NaN;
            }
            Object value = rawValue(data, key);
            return value instanceof Float f ? f : Float.NaN;
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    /**
     * 直写 {@code DataItem.value} 字段——不经过 {@code SynchedEntityData.set(...)}，
     * 因此也绕开任何对该方法的拦截/改写。
     *
     * <p>用途：把一个已经确认有害的私有累加通道（例如外部累加量涨到 1 以上）归零，
     * 让按外部私有状态换算出的读数重新回到正常血量——这一步不依赖任何注入点是否还在。
     *
     * @return true 表示写入成功
     */
    public static boolean writeRawFloat(Entity entity, EntityDataAccessor<Float> key, float value) {
        if (key == null || ITEMS_BY_ID == null || ITEM_VALUE == null) {
            return false;
        }
        try {
            SynchedEntityData data = dataOf(entity);
            if (data == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<Integer, SynchedEntityData.DataItem<?>> items =
                    (Map<Integer, SynchedEntityData.DataItem<?>>) ITEMS_BY_ID.get(data);
            if (items == null) {
                return false;
            }
            SynchedEntityData.DataItem<?> item = items.get(key.getId());
            if (item == null) {
                return false;
            }
            ITEM_VALUE.set(item, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 创建该同步数据的实体（直读字段，不经过任何 getter）。 */
    public static Entity entityOf(SynchedEntityData data) {        if (data == null || DATA_ENTITY == null) {
            return null;
        }
        try {
            return (Entity) DATA_ENTITY.get(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 数据键对应的数据项里**当前真实存着的值**（直读字段，不经过 {@code DataItem.getValue()}）。 */
    public static Object rawValue(SynchedEntityData data, EntityDataAccessor<?> key) {
        if (data == null || key == null || ITEMS_BY_ID == null || ITEM_VALUE == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, SynchedEntityData.DataItem<?>> items =
                    (Map<Integer, SynchedEntityData.DataItem<?>>) ITEMS_BY_ID.get(data);
            if (items == null) {
                return null;
            }
            SynchedEntityData.DataItem<?> item = items.get(key.getId());
            return item == null ? null : ITEM_VALUE.get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 该实体的 {@code itemsById} 表（{@code Int2ObjectMap<DataItem<?>>} 以 {@link Map} 形态暴露）。
     *
     * <p>返回的是<b>活引用</b>：调用方 {@code put/remove} 会直接改到同步数据表上，
     * 这正是「把血量数据项换成代理项」需要的入口（见 {@code HealthDataItemInstaller}）。
     * 取不到时返回 {@code null}。
     */
    @SuppressWarnings("unchecked")
    public static Map<Integer, SynchedEntityData.DataItem<?>> itemsOf(Entity entity) {
        if (entity == null || ITEMS_BY_ID == null) {
            return null;
        }
        try {
            SynchedEntityData data = dataOf(entity);
            if (data == null) {
                return null;
            }
            return (Map<Integer, SynchedEntityData.DataItem<?>>) ITEMS_BY_ID.get(data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 缓存：类 -> 该类（含父类）的 float/double 实例字段。 */    private static final Map<Class<?>, java.util.List<Field>> FLOAT_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 浮点比较容差。 */
    private static final float EPS = 1.0E-4F;

    /**
     * 把对象身上“值等于 {@code expected} 的 float/double 字段”清零。
     *
     * <p>为什么需要：外部实现把累加值既存进同步数据项、又存在自己的字段里，每 tick 用字段把数据项
     * 写回来。只清数据项会在下一 tick 被覆盖（日志实测：清零后数值仍会一路回涨）。
     * 因此必须把背后的字段也清零。
     *
     * @return 实际清零的字段个数
     */
    public static int zeroMatchingFloatFields(Object owner, float expected) {
        if (owner == null) {
            return 0;
        }
        int cleared = 0;
        for (Field field : floatFieldsOf(owner.getClass())) {
            try {
                Class<?> type = field.getType();
                if (type == float.class) {
                    if (Math.abs(field.getFloat(owner) - expected) <= EPS) {
                        field.setFloat(owner, 0.0F);
                        cleared++;
                    }
                } else if (type == double.class) {
                    if (Math.abs(field.getDouble(owner) - expected) <= EPS) {
                        field.setDouble(owner, 0.0D);
                        cleared++;
                    }
                }
            } catch (Throwable ignored) {
                // 单个字段失败不影响其它字段
            }
        }
        return cleared;
    }

    /** 该类（含父类）所有可写的 float/double 实例字段；按类缓存。 */
    private static java.util.List<Field> floatFieldsOf(Class<?> type) {
        return FLOAT_FIELDS.computeIfAbsent(type, RawState::scanFloatFields);
    }

    /**
     * 只统计、不改写：这个对象身上有多少 float/double 字段的值等于 {@code expected}。
     *
     * <p>给"灰度观测"用：dry-run 时必须能回答"如果真中和，会清掉几个字段"，但不能真清。
     */
    public static int countMatchingFloatFields(Object owner, float expected) {
        if (owner == null) {
            return 0;
        }
        int matched = 0;
        for (Field field : floatFieldsOf(owner.getClass())) {
            try {
                Class<?> type = field.getType();
                if (type == float.class) {
                    if (Math.abs(field.getFloat(owner) - expected) <= EPS) {
                        matched++;
                    }
                } else if (type == double.class) {
                    if (Math.abs(field.getDouble(owner) - expected) <= EPS) {
                        matched++;
                    }
                }
            } catch (Throwable ignored) {
                // 单个字段失败不影响其它字段
            }
        }
        return matched;
    }

    private static java.util.List<Field> scanFloatFields(Class<?> type) {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType != float.class && fieldType != double.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable ignored) {
                    // 不可访问的字段直接跳过
                }
            }
        }
        return out;
    }

    /** 数据键的序列化器实例（用于“按 serializer 语义”判定，而不是认具体的 key）。 */
    public static Object serializerOf(EntityDataAccessor<?> key) {
        if (key == null || ACCESSOR_SERIALIZER == null) {
            return null;
        }
        try {
            return ACCESSOR_SERIALIZER.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 数据键的 serializer <b>注册名</b>（{@code FLOAT} / {@code INT} / …）。
     *
     * <p>为什么不打印 {@code getClass().getSimpleName()}：原版这些 serializer 都是匿名子类，打出来是
     * {@code EntityDataSerializer$1}，两者完全不同却长得一样——诊断等于没打。这里按 object identity
     * 比对已注册的常量来还原名字。
     */
    public static String serializerName(EntityDataAccessor<?> key) {
        try {
            Object serializer = serializerOf(key);
            if (serializer == null) {
                return "?";
            }
            if (serializer == EntityDataSerializers.FLOAT) {
                return "FLOAT";
            }
            if (serializer == EntityDataSerializers.INT) {
                return "INT";
            }
            if (serializer == EntityDataSerializers.BOOLEAN) {
                return "BOOLEAN";
            }
            if (serializer == EntityDataSerializers.BYTE) {
                return "BYTE";
            }

            if (serializer == EntityDataSerializers.STRING) {
                return "STRING";
            }
            if (serializer == EntityDataSerializers.COMPOUND_TAG) {
                return "COMPOUND_TAG";
            }
            if (serializer == EntityDataSerializers.OPTIONAL_COMPONENT) {
                return "OPTIONAL_COMPONENT";
            }
            if (serializer == EntityDataSerializers.OPTIONAL_BLOCK_POS) {
                return "OPTIONAL_BLOCK_POS";
            }
            String simple = serializer.getClass().getSimpleName();
            return simple.isEmpty() ? "unknown" : simple;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 反射字段是否全部解析成功（启动时打一行日志，便于发现生产映射漂移）。 */
    public static boolean available() {
        return DATA_ENTITY != null && ENTITY_DATA != null && ITEMS_BY_ID != null
                && ITEM_VALUE != null && ACCESSOR_SERIALIZER != null;
    }

    /** 解析失败时返回缺失的字段名，供启动日志使用。 */
    public static String describeMissing() {
        StringBuilder missing = new StringBuilder();
        if (DATA_ENTITY == null) {
            missing.append("entity ");
        }
        if (ENTITY_DATA == null) {
            missing.append("Entity.entityData ");
        }
        if (ITEMS_BY_ID == null) {
            missing.append("itemsById ");
        }
        if (ITEM_VALUE == null) {
            missing.append("DataItem.value ");
        }
        if (ACCESSOR_SERIALIZER == null) {
            missing.append("accessor.serializer ");

        }
        return missing.isEmpty() ? "<none>" : missing.toString().trim();
    }
}
