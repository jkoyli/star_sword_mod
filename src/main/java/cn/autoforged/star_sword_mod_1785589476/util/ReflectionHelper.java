package cn.autoforged.star_sword_mod_1785589476.util;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionHelper {

    /**
     * SRG 名：运行时（生产）字段名。开发环境走 ObfuscationReflectionHelper 的 srg→mcp 映射，
     * 生产环境该映射不存在则直接取 SRG 名——两条路径都由这个常量覆盖。
     */
    private static final String SRG_DATA_HEALTH_ID = "f_20961_";

    /**
     * {@code SynchedEntityData.itemsById} 的 SRG 名候选。不同映射源给的名字不一致：
     * 本仓库映射文件 {@code createMappings_*_officialToSrg.tsrg} 记载 {@code f_135345_}，
     * 而需求方给出的是 {@code f_135342_}。为避免写死单一名字导致生产反射静默失败，
     * 这里把两个候选都尝试一遍，并**强制校验字段类型为 Map**（{@code f_135342_}
     * 在某些映射下其实是 LOGGER 字段，类型校验会直接排除它）。
     */
    private static final String[] SRG_ITEMS_BY_ID_CANDIDATES = { "f_135342_", "f_135345_" };

    private static EntityDataAccessor<Float> CACHED_HEALTH_ACCESSOR;
    private static Field CACHED_ITEMS_BY_ID_FIELD;

    /** {@code LivingEntity.dropAllDeathLoot(DamageSource)} 的 SRG 名（protected，反射调用）。 */
    private static final String SRG_DROP_ALL_DEATH_LOOT = "m_6668_";

    private static volatile Method DROP_ALL_DEATH_LOOT_METHOD;
    private static volatile boolean DROP_ALL_DEATH_LOOT_RESOLVED;

    /** 按实体类缓存“该实体实际把血量存进哪个 SynchedEntityData 数据键”（可自定义）。 */
    private static final Map<Class<?>, EntityDataAccessor<Float>> HEALTH_KEY_CACHE = new ConcurrentHashMap<>();

    /**
     * 血量键解析时<b>实际命中的字段名</b>（{@code f_20961_} 或 {@code DATA_HEALTH_ID}）。
     *
     * <p>诊断用：生产环境是 SRG 名、开发环境是 named 名，把命中的那个打出来，才能确认"我们取到的是哪一份"。
     * 解析失败时为 {@code "unresolved"}。
     */
    private static volatile String HEALTH_FIELD_NAME = "unresolved";

    static {
        try {
            Field field = findFieldCompat(LivingEntity.class, SRG_DATA_HEALTH_ID, "DATA_HEALTH_ID");
            if (field == null) {
                throw new NoSuchFieldException("DATA_HEALTH_ID");
            }
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Float> accessor = (EntityDataAccessor<Float>) field.get(null);
            CACHED_HEALTH_ACCESSOR = accessor;
            HEALTH_FIELD_NAME = field.getName();
        } catch (Exception e) {
            CACHED_HEALTH_ACCESSOR = null;
        }
        if (CACHED_HEALTH_ACCESSOR == null) {
            ModLog.attack("ReflectionHelper 初始化：LivingEntity.DATA_HEALTH_ID 解析失败，"
                    + "所有依赖原版血量数据键的层都会失效", null, null);
        }
        CACHED_ITEMS_BY_ID_FIELD = resolveItemsByIdField();
        if (CACHED_ITEMS_BY_ID_FIELD == null) {
            ModLog.attack("ReflectionHelper 初始化：SynchedEntityData.itemsById 解析失败，"
                    + "同步数据扫描/自定义血量键探测都会失效", null, null);
        }
    }

    /** 依次尝试候选 SRG 名，只接受类型确实是 Map 的字段；都失败时退回字面 Mojmap 名。 */
    private static Field resolveItemsByIdField() {
        for (String srgName : SRG_ITEMS_BY_ID_CANDIDATES) {
            Field field = findFieldCompat(SynchedEntityData.class, srgName, "itemsById");
            if (field != null && Map.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        Field fallback = findFieldCompat(SynchedEntityData.class, "itemsById", "itemsById");
        return fallback != null && Map.class.isAssignableFrom(fallback.getType()) ? fallback : null;
    }

    /**
     * 取字段：优先 SRG 名（生产运行时字段名；开发运行时由 ObfuscationReflectionHelper 映射回
     * Mojmap），再退回字面 SRG/Mojmap 名。Mojmap 名直接反射在生产环境必然失败（且会静默），
     * 因此绝不再只依赖 Mojmap 名。
     */
    public static Field findFieldCompat(Class<?> clazz, String srgName, String mojmapName) {
        try {
            return ObfuscationReflectionHelper.findField(clazz, srgName);
        } catch (Throwable ignored) {
        }
        try {
            Field field = clazz.getDeclaredField(srgName);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        try {
            Field field = clazz.getDeclaredField(mojmapName);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 缓存的 {@code SynchedEntityData} 数据项表字段（SRG 名候选见 {@link #SRG_ITEMS_BY_ID_CANDIDATES}）。 */
    public static Field itemsByIdField() {
        return CACHED_ITEMS_BY_ID_FIELD;
    }

    /** 返回缓存的 {@code LivingEntity.DATA_HEALTH_ID} 数据键（可能为 null，调用方需判空）。 */
    public static EntityDataAccessor<Float> healthDataAccessor() {
        return CACHED_HEALTH_ACCESSOR;
    }

    /** 血量键解析时命中的字段名（{@code f_20961_} / {@code DATA_HEALTH_ID} / {@code unresolved}）。 */
    public static String healthFieldName() {
        return HEALTH_FIELD_NAME;
    }

    /**
     * 返回<b>该实体实际使用的</b>血量数据键。
     *
     * <p>直接写父类 {@code DATA_HEALTH_ID} 只对沿用原版血量存储的实体有效：不少模组会把血量
     * 存进自己 {@code SynchedEntityData.defineId(...)} 的自定义 Float 数据键并覆写
     * {@code getHealth()/setHealth()}。此时写父类键等于写进一个没人读的位置，击杀会失效。
     *
     * <p>判定方法：实体当前 {@code getHealth()} 读出来的值，必然等于它真正读取的那个数据键的
     * 当前值；在全部数据项里找值与之相等的 Float 键即为实际血量键。多个候选时优先排除父类
     * {@code DATA_HEALTH_ID}（它的值与 getHealth() 不一致时说明用的是自定义键）。结果按类缓存，
     * 每类只探测一次。
     */
    public static EntityDataAccessor<Float> healthDataAccessor(LivingEntity entity) {
        if (entity == null) {
            return CACHED_HEALTH_ACCESSOR;
        }
        Class<?> type = entity.getClass();
        EntityDataAccessor<Float> cached = HEALTH_KEY_CACHE.get(type);
        if (cached != null) {
            return cached;
        }
        EntityDataAccessor<Float> detected = detectHealthAccessor(entity);
        if (detected != null) {
            HEALTH_KEY_CACHE.put(type, detected);
        }
        return detected;
    }

    private static EntityDataAccessor<Float> detectHealthAccessor(LivingEntity entity) {
        try {
            float current = entity.getHealth();
            List<EntityDataAccessor<Float>> matches = new ArrayList<>();
            for (SynchedEntityData.DataItem<?> item : dataItems(entity.getEntityData())) {
                if (item.getValue() instanceof Float f && f == current) {
                    @SuppressWarnings("unchecked")
                    EntityDataAccessor<Float> accessor = (EntityDataAccessor<Float>) item.getAccessor();
                    matches.add(accessor);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                List<EntityDataAccessor<Float>> custom = new ArrayList<>(matches);
                custom.removeIf(a -> a == CACHED_HEALTH_ACCESSOR);
                return custom.isEmpty() ? matches.get(0) : custom.get(0);
            }
        } catch (Exception e) {
            ModLog.attack("healthDataAccessor 探测异常，回退到原版数据键", null, entity, "异常=" + e);
        }
        // 没有任何 Float 数据项与 getHealth() 相等：无法定位自定义血量键，回退父类键（可能写不到血量）。
        ModLog.attackThrottled("ReflectionHelper.healthDataAccessor.fallback:" + entity.getClass().getName(),
                ModLog.TICK_INTERVAL,
                "healthDataAccessor 探测不到等值 Float 数据键，回退原版键（若该实体血量存自定义键则写入无效）",
                null, entity, "");
        return CACHED_HEALTH_ACCESSOR;
    }

    /** 读取 {@code SynchedEntityData} 内部全部数据项（失败返回空表，绝不让反射异常上抛）。 */
    public static List<SynchedEntityData.DataItem<?>> dataItems(SynchedEntityData data) {
        if (data == null || CACHED_ITEMS_BY_ID_FIELD == null) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<Integer, SynchedEntityData.DataItem<?>> itemsById =
                    (Map<Integer, SynchedEntityData.DataItem<?>>) CACHED_ITEMS_BY_ID_FIELD.get(data);
            if (itemsById == null || itemsById.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(itemsById.values());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 三段式直写血量，从最精确到最通用，任一段成功即返回，且绝不抛异常
     * （调用方 KillHelper / LivingEntityMixin 都假设它不会抛）：
     * <ol>
     *   <li><b>字段路径</b>：解析 {@code getHealth()} 真正读取的 float/double 字段并直写，
     *       绕开所有被重写的方法与同步数据键；</li>
     *   <li><b>同步数据键</b>：按实体实际存储位置解析出的 {@link EntityDataAccessor} 写入，
     *       对血量存在 SynchedEntityData 自定义键的实体仍然有效；</li>
     *   <li><b>setHealth</b>：最后兜底，可能被其它模组覆写的限伤实现拦截，但至少不会什么都不做。</li>
     * </ol>
     */
    public static boolean setHealthDirectly(LivingEntity target, float value) {
        if (target == null) {
            ModLog.attack("setHealthDirectly 被拒：target 为 null", null, null, "value=" + value);
            return false;
        }
        // 第 1 段：字段路径。
        try {
            HealthFieldResolver.FieldPath path = HealthFieldResolver.resolve(target);
            if (path != null && path.write(target, value)) {
                ModLog.attack("setHealthDirectly[层1/字段路径] 成功直写", null, target, "value=" + value);
                return true;
            }
            ModLog.attack("setHealthDirectly[层1/字段路径] 未命中（无字段路径），回退层2", null, target,
                    "value=" + value + " path=" + (path == null ? "null" : "write返回false"));
        } catch (Throwable t) {
            ModLog.attack("setHealthDirectly[层1/字段路径] 抛异常，回退层2", null, target,
                    "value=" + value + " 异常=" + t);
        }
        // 第 2 段：同步数据键。
        try {
            EntityDataAccessor<Float> accessor = healthDataAccessor(target);
            if (accessor != null) {
                target.getEntityData().set(accessor, value);
                ModLog.attack("setHealthDirectly[层2/同步数据键] 成功直写", null, target, "value=" + value);
                return true;
            }
            ModLog.attack("setHealthDirectly[层2/同步数据键] 无可用数据键，回退层3", null, target,
                    "value=" + value);
        } catch (Throwable t) {
            ModLog.attack("setHealthDirectly[层2/同步数据键] 抛异常，回退层3", null, target,
                    "value=" + value + " 异常=" + t);
        }
        // 第 3 段：setHealth 兜底。
        try {
            target.setHealth(value);
            ModLog.attack("setHealthDirectly[层3/setHealth兜底] 成功", null, target, "value=" + value);
            return true;
        } catch (Throwable t) {
            ModLog.attack("setHealthDirectly[层3/setHealth兜底] 失败（三层全部失效）", null, target,
                    "value=" + value + " 异常=" + t);
        }
        return false;
    }

    public static boolean setMaxHealthDirectly(LivingEntity target, double value) {
        try {
            var attr = target.getAttribute(Attributes.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(value);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static boolean scanAndZeroSynchedData(LivingEntity target) {
        try {
            SynchedEntityData data = target.getEntityData();
            Field itemsByIdField = CACHED_ITEMS_BY_ID_FIELD;
            if (itemsByIdField == null) {
                return false;
            }
            @SuppressWarnings("unchecked")
            var itemsById = (Map<Integer, SynchedEntityData.DataItem<?>>) itemsByIdField.get(data);

            if (itemsById == null) return false;

            boolean modified = false;
            for (SynchedEntityData.DataItem<?> item : new ArrayList<>(itemsById.values())) {
                Object val = item.getValue();
                if (val instanceof Float floatVal) {
                    if (floatVal > 0.0F && floatVal <= 2048.0F) {
                        try {
                            @SuppressWarnings("unchecked")
                            SynchedEntityData.DataItem<Float> floatItem = (SynchedEntityData.DataItem<Float>) item;
                            floatItem.setValue(0.0F);
                            modified = true;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return modified;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 字段扫描递归深度上限，与 {@link HealthFieldResolver} 的对象图扫描一致。 */
    private static final int SCAN_MAX_DEPTH = 4;

    /** 名字命中即无条件清零的关键字（明显与复活/计时相关）。 */
    private static final Set<String> ZERO_KEYWORDS = Set.of("revive", "life", "respawn", "regen", "time");

    /**
     * 递归归零“复活/计时”类状态字段（在“不惜代价弄死目标”的路径里使用，误伤可接受）。
     *
     * <p>先按实体实际使用的血量数据键归零，再递归扫描实体自身与嵌套状态容器：
     * 除了原来的普通字段，还会进入 {@code entity.combatProgress.progress} 这种两层嵌套路径。
     * 递归规则与 {@link HealthFieldResolver} 的对象图扫描一致（非静态、非集合/Map/数组、非 Entity、
     * 深度受限），避免跟随实体引用或世界对象走出目标。
     */
    public static boolean scanAndZeroFields(LivingEntity target) {
        boolean modified = false;
        // 大部分模组的血量存在 SynchedEntityData（可能是自定义数据键）而不是普通 Java 字段：
        // 先按该实体实际使用的血量数据键归零，再做普通字段扫描，避免“只扫字段永远碰不到血量”。
        modified |= setHealthDirectly(target, 0.0F);
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        modified |= zeroFieldsRecursive(target, 0, visited);
        return modified;
    }

    private static boolean zeroFieldsRecursive(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > SCAN_MAX_DEPTH || !visited.add(obj)) {
            return false;
        }
        boolean modified = false;
        for (Field field : HealthFieldResolver.instanceFields(obj.getClass())) {
            try {
                Class<?> type = field.getType();
                String name = field.getName().toLowerCase(Locale.ROOT);
                boolean isKeyword = ZERO_KEYWORDS.stream().anyMatch(name::contains);

                if (type == float.class) {
                    float val = field.getFloat(obj);
                    if ((isKeyword && val > 0.0F) || (val > 0.0F && val <= 2048.0F)) {
                        field.setFloat(obj, 0.0F);
                        modified = true;
                    }
                } else if (type == double.class) {
                    double val = field.getDouble(obj);
                    if ((isKeyword && val > 0.0) || (val > 0.0 && val <= 2048.0)) {
                        field.setDouble(obj, 0.0);
                        modified = true;
                    }
                } else if (type == int.class) {
                    int val = field.getInt(obj);
                    if ((isKeyword && val > 0) || (val > 0 && val <= 2048)) {
                        field.setInt(obj, 0);
                        modified = true;
                    }
                } else if (type == boolean.class) {
                    // 布尔型“复活/重生”标记直接改回 false，对应“将 setRevive(true) 篡改为 false”
                    if (isKeyword && field.getBoolean(obj)) {
                        field.setBoolean(obj, false);
                        modified = true;
                    }
                } else if (depth < SCAN_MAX_DEPTH && !type.isPrimitive() && !type.isArray()) {
                    Object nested = field.get(obj);
                    // 按运行期真实类型判断能否进入，避免字段声明为 Object 时误入 Level/实体等重对象。
                    if (nested != null && HealthFieldResolver.isStateContainer(nested.getClass())) {
                        modified |= zeroFieldsRecursive(nested, depth + 1, visited);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return modified;
    }

    /**
     * 反射调用原版 {@code LivingEntity.dropAllDeathLoot(DamageSource)}（protected）。
     *
     * <p>这是唯一同时覆盖 loot table 与 {@code dropCustomDeathLoot} 的入口，模组生物覆写
     * 的自定义掉落也走这里（{@code dropAllDeathLoot} 内部会虚调用到子类实现）。
     * 优先 SRG 名（生产运行时字段名），再退回 Mojmap 名（开发运行时）。
     *
     * @return 是否成功调用
     */
    public static boolean invokeDropAllDeathLoot(LivingEntity entity, DamageSource source) {
        Method method = dropAllDeathLootMethod();
        if (method == null) {
            ModLog.attack("invokeDropAllDeathLoot 反射方法解析失败（m_6668_/dropAllDeathLoot 都找不到），"
                    + "主动掉落失效", null, entity);
            return false;
        }
        try {
            method.invoke(entity, source);
            return true;
        } catch (Throwable t) {
            ModLog.attack("invokeDropAllDeathLoot 调用抛出异常，主动掉落失效", null, entity, "异常=" + t);
            return false;
        }
    }

    private static Method dropAllDeathLootMethod() {
        if (DROP_ALL_DEATH_LOOT_RESOLVED) {
            return DROP_ALL_DEATH_LOOT_METHOD;
        }
        synchronized (ReflectionHelper.class) {
            if (!DROP_ALL_DEATH_LOOT_RESOLVED) {
                DROP_ALL_DEATH_LOOT_METHOD = findMethodCompat(
                        LivingEntity.class, SRG_DROP_ALL_DEATH_LOOT, "dropAllDeathLoot", DamageSource.class);
                DROP_ALL_DEATH_LOOT_RESOLVED = true;
            }
        }
        return DROP_ALL_DEATH_LOOT_METHOD;
    }

    /** 与 {@link #findFieldCompat} 同理：先 SRG 名再 Mojmap 名，避免生产环境反射静默失败。 */
    public static Method findMethodCompat(Class<?> clazz, String srgName, String mojmapName,
                                          Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(srgName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
        }
        try {
            Method method = clazz.getDeclaredMethod(mojmapName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
        }
        return null;
    }

}
