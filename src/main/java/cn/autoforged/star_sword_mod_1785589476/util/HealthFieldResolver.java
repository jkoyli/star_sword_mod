package cn.autoforged.star_sword_mod_1785589476.util;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 血量字段解析器：给定一个 {@link LivingEntity}，找到它“真正被 {@code getHealth()} 读取”的那个
 * float/double 字段，并给出从实体到该字段的路径（{@link FieldPath}）。
 *
 * <p>解析分两步：
 * <ol>
 *   <li><b>字节码分析</b>：沿继承链向上找第一个真正声明 {@code getHealth()} 的类，读出该类字节码，
 *       收集 {@code getHealth()} 方法体内的 GETFIELD 指令序列。若最后一条 GETFIELD 的类型是
 *       F/D，说明方法直接返回某个 float/double 字段（可能经过 {@code obj.field} 这类嵌套路径），
 *       这一串 GETFIELD 就是字段路径。</li>
 *   <li><b>对象图扫描（兜底）</b>：字节码无法定位（动态生成类 / lambda / shaded 类）时，从实体出发
 *       做深度受限的 DFS，在每个对象里找值等于 {@code entity.getHealth()} 的 float/double 字段。</li>
 * </ol>
 *
 * <p>结果按 {@link Class} 缓存，但缓存命中后会先用 {@link FieldPath#isValid(LivingEntity)} 校验
 * （同一类的不同实例可能因阶段切换/复活换了血量来源），校验不过就重新解析。
 */
public final class HealthFieldResolver {

    /** {@code LivingEntity.getHealth ()F} 的运行时名候选：开发域 Mojmap / 生产域 SRG。 */
    private static final Set<String> GET_HEALTH_NAMES = Set.of("getHealth", "m_21223_");
    private static final String GET_HEALTH_DESC = "()F";

    /** 对象图递归深度上限：太深会扫到大量无关对象，太浅会漏掉 entity.combatProgress.progress 这类两层路径。 */
    private static final int MAX_DEPTH = 4;

    /** 按实体类缓存已解析出的字段路径（只缓存有效结果，不缓存失败）。 */
    private static final Map<Class<?>, FieldPath> CACHE = new ConcurrentHashMap<>();

    private HealthFieldResolver() {
    }

    /**
     * 解析实体“真实血量字段”路径；解析不出时返回 {@code null}（调用方应回退到同步数据键 / setHealth）。
     * 本方法绝不抛异常。
     */
    public static FieldPath resolve(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        Class<?> type = entity.getClass();
        FieldPath cached = CACHE.get(type);
        // 缓存不能无条件信任：先验证当前实例该字段的值是否仍等于 getHealth()。
        if (cached != null && cached.isValid(entity)) {
            return cached;
        }
        FieldPath resolved = compute(entity);
        if (resolved != null && resolved.isValid(entity)) {
            CACHE.put(type, resolved);
            return resolved;
        }
        return null;
    }

    private static FieldPath compute(LivingEntity entity) {
        String typeKey = "HealthFieldResolver." + entity.getClass().getName();
        Class<?> declarer = null;
        try {
            declarer = findGetHealthDeclarer(entity.getClass());
        } catch (Throwable ignored) {
        }
        if (declarer != null) {
            try {
                List<FieldRef> refs = readGetFieldSequence(declarer);
                if (refs != null) {
                    // 字节码已成功定位并解析：只认字节码结论。
                    // 若最后一条 GETFIELD 不是 F/D（如原版把血量存 SynchedEntityData），说明该方法
                    // 返回的不是“直接字段血量”，就此返回 null 交给同步数据/setHealth 兜底——继续做
                    // 对象图扫描只会把 absorptionAmount / walkDist 这类无关浮点误判成血量来源。
                    FieldPath bytecodePath = buildBytecodePath(declarer, refs);
                    if (bytecodePath == null) {
                        ModLog.logThrottled(typeKey + ".nonField", ModLog.TICK_INTERVAL, ModLog.PARSE,
                                "HealthFieldResolver[层1/字节码] getHealth 读的不是直接 F/D 字段（可能存同步数据），"
                                        + "放弃字段路径，交给同步数据/setHealth 兜底 | 类=" + entity.getClass().getName());
                    }
                    return bytecodePath;
                }
                ModLog.logThrottled(typeKey + ".noBytes", ModLog.TICK_INTERVAL, ModLog.PARSE,
                        "HealthFieldResolver[层1/字节码] 定位不到类字节码，退化到对象图扫描 | 类="
                                + entity.getClass().getName());
            } catch (Throwable t) {
                ModLog.logThrottled(typeKey + ".bytecodeThrow", ModLog.TICK_INTERVAL, ModLog.PARSE,
                        "HealthFieldResolver[层1/字节码] 分析抛异常，退化到对象图扫描 | 类="
                                + entity.getClass().getName() + " 异常=" + t);
            }
        } else {
            ModLog.logThrottled(typeKey + ".noDeclarer", ModLog.TICK_INTERVAL, ModLog.PARSE,
                    "HealthFieldResolver[层1/字节码] 找不到 getHealth 声明者，退化到对象图扫描 | 类="
                            + entity.getClass().getName());
        }
        // 字节码无法定位（ClassFileLocator 失败 / 找不到 getHealth 声明者）：退化到对象图递归扫描。
        try {
            FieldPath graphPath = fromObjectGraph(entity);
            if (graphPath == null) {
                ModLog.logThrottled(typeKey + ".graphMiss", ModLog.TICK_INTERVAL, ModLog.PARSE,
                        "HealthFieldResolver[层2/对象图] 未定位到血量字段，返回 null（保持原样） | 类="
                                + entity.getClass().getName());
            }
            return graphPath;
        } catch (Throwable t) {
            ModLog.logThrottled(typeKey + ".graphThrow", ModLog.TICK_INTERVAL, ModLog.PARSE,
                    "HealthFieldResolver[层2/对象图] 扫描抛异常，返回 null | 类="
                            + entity.getClass().getName() + " 异常=" + t);
            return null;
        }
    }

    // ================= 字节码分析 =================

    /** 沿继承链向上找第一个真正声明 {@code getHealth ()F} 的类（实体可能没重写，实际生效的是父类实现）。 */
    private static Class<?> findGetHealthDeclarer(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType() == float.class
                        && GET_HEALTH_NAMES.contains(method.getName())) {
                    return current;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 读出声明者类里 {@code getHealth()} 方法体的 GETFIELD 序列。
     *
     * @return GETFIELD 列表；类文件无法定位时返回 {@code null}（区别于“方法体里没有 GETFIELD”的空列表）。
     */
    private static List<FieldRef> readGetFieldSequence(Class<?> declarer) {
        byte[] bytes = locateClassBytes(declarer);
        if (bytes == null) {
            return null;
        }
        List<FieldRef> sequence = new ArrayList<>();
        boolean[] found = { false };
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (found[0] || !GET_HEALTH_NAMES.contains(name) || !GET_HEALTH_DESC.equals(descriptor)) {
                    return null;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.GETFIELD) {
                            sequence.add(new FieldRef(owner, name, descriptor));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return sequence;
    }

    /**
     * 定位类文件字节码：优先目标类自己的 ClassLoader，bootstrap 类走系统 ClassLoader，
     * 最后用 {@code Class.getResourceAsStream} 兜底。
     *
     * <p>原实现用 ByteBuddy 的 {@code ClassFileLocator}；移除 ByteBuddy 后改用 JDK 自带的
     * 资源加载，语义等价（都读当前已加载类的 class 字节码）。
     */
    private static byte[] locateClassBytes(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try {
            ClassLoader loader = type.getClassLoader();
            if (loader != null) {
                try (InputStream in = loader.getResourceAsStream(resource)) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                }
            } else {
                try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
                    if (in != null) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        try (InputStream in = type.getResourceAsStream("/" + resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    /**
     * 把 GETFIELD 序列翻译成 Field 链：最后一条必须是 F/D，否则放弃字节码路径（返回 null）。
     * 每一跳都在“上一跳字段的类型”上沿继承链找同名字段。
     */
    private static FieldPath buildBytecodePath(Class<?> declarer, List<FieldRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        FieldRef last = refs.get(refs.size() - 1);
        int sort = Type.getType(last.descriptor()).getSort();
        if (sort != Type.FLOAT && sort != Type.DOUBLE) {
            return null;
        }
        Field[] chain = new Field[refs.size()];
        Class<?> searchFrom = declarer;
        for (int i = 0; i < refs.size(); i++) {
            Field field = findFieldInHierarchy(searchFrom, refs.get(i).name());
            if (field == null) {
                return null;
            }
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                return null;
            }
            chain[i] = field;
            searchFrom = field.getType();
        }
        return new FieldPath(chain);
    }

    private static Field findFieldInHierarchy(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    // ================= 对象图递归扫描 =================

    private static FieldPath fromObjectGraph(LivingEntity entity) {
        float health = entity.getHealth();
        List<Field> path = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (searchForHealthField(entity, health, path, 0, visited)) {
            return new FieldPath(path.toArray(new Field[0]));
        }
        return null;
    }

    /**
     * DFS：先在同一对象里找值等于 health 的 float/double 字段（越浅越优先），再递归进入“可能是状态容器”
     * 的引用字段。只接受非原版声明的字段——原版类里的 absorptionAmount / 坐标等浮点与血量无关，
     * 拿它们做血量字段会造成误写。
     */
    private static boolean searchForHealthField(Object obj, float health, List<Field> path,
                                                int depth, Set<Object> visited) {
        if (obj == null || depth > MAX_DEPTH || !visited.add(obj)) {
            return false;
        }
        List<Field> fields = instanceFields(obj.getClass());
        for (Field field : fields) {
            if (isVanillaDeclared(field)) {
                continue;
            }
            Class<?> type = field.getType();
            try {
                if (type == float.class && field.getFloat(obj) == health) {
                    path.add(field);
                    return true;
                }
                if (type == double.class && (float) field.getDouble(obj) == health) {
                    path.add(field);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (depth >= MAX_DEPTH) {
            return false;
        }
        for (Field field : fields) {
            Class<?> declared = field.getType();
            if (declared.isPrimitive() || declared.isArray()) {
                continue;
            }
            Object nested;
            try {
                nested = field.get(obj);
            } catch (Throwable ignored) {
                continue;
            }
            // 按运行期真实类型判断能否进入：字段声明为 Object 时也不会误入 Level/实体等重对象。
            if (nested == null || !isStateContainer(nested.getClass())) {
                continue;
            }
            path.add(field);
            if (searchForHealthField(nested, health, path, depth + 1, visited)) {
                return true;
            }
            path.remove(path.size() - 1);
        }
        return false;
    }

    // ================= 公共递归规则 =================

    /**
     * 一个类型是否“可能是状态容器”、值得递归进入。
     *
     * <p>跳过：数组、集合、Map、{@link Entity}（跟随实体引用会走出当前实体）、
     * {@link Level} 等世界基础设施、以及 java/net.minecraft/com.mojang 等原版/平台类——
     * 遍历它们代价大且容易误伤。只进入模组自定义的普通对象。
     */
    public static boolean isStateContainer(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray()) {
            return false;
        }
        if (type == String.class || type == Class.class || type.isEnum()) {
            return false;
        }
        if (Entity.class.isAssignableFrom(type)) {
            return false;
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return false;
        }
        if (Level.class.isAssignableFrom(type)
                || Thread.class.isAssignableFrom(type)
                || ClassLoader.class.isAssignableFrom(type)) {
            return false;
        }
        String name = type.getName();
        return !name.startsWith("net.minecraft.")
                && !name.startsWith("java.")
                && !name.startsWith("javax.")
                && !name.startsWith("sun.")
                && !name.startsWith("com.mojang.")
                && !name.startsWith("it.unimi.")
                && !name.startsWith("org.apache.");
    }

    /** 收集一个类及其父类（到 Object 之前）声明的全部非静态、非 synthetic 字段，并尝试 setAccessible。 */
    public static List<Field> instanceFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                result.add(field);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static boolean isVanillaDeclared(Field field) {
        String name = field.getDeclaringClass().getName();
        return name.startsWith("net.minecraft.") || name.startsWith("java.") || name.startsWith("com.mojang.");
    }

    /** 一条 GETFIELD 指令：字段名 + 描述符（描述符用来判断终点类型 F/D）。 */
    private record FieldRef(String owner, String name, String descriptor) {
    }

    /**
     * 从实体到目标血量字段的路径。封装三件事：走到目标对象、校验、直接写字段（不经过任何方法）。
     */
    public static final class FieldPath {

        private final Field[] chain;

        FieldPath(Field[] chain) {
            this.chain = chain;
        }

        /** 沿中间字段链走到最终持有血量字段的对象；任一中间对象为 null 时返回 null（整条路径作废）。 */
        public Object resolveOwner(LivingEntity entity) {
            Object current = entity;
            for (int i = 0; i < chain.length - 1; i++) {
                if (current == null) {
                    return null;
                }
                try {
                    current = chain[i].get(current);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return current;
        }

        /** 读目标字段并与 {@code entity.getHealth()} 比对；相等才认为路径仍有效。 */
        public boolean isValid(LivingEntity entity) {
            if (entity == null || chain.length == 0) {
                return false;
            }
            Object owner = resolveOwner(entity);
            if (owner == null) {
                return false;
            }
            Field target = chain[chain.length - 1];
            try {
                Class<?> type = target.getType();
                if (type == float.class) {
                    return target.getFloat(owner) == entity.getHealth();
                }
                if (type == double.class) {
                    return (float) target.getDouble(owner) == entity.getHealth();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        /** 直接写目标字段（同时支持 float.class 与 double.class）。 */
        public boolean write(LivingEntity entity, float value) {
            if (entity == null || chain.length == 0) {
                return false;
            }
            Object owner = resolveOwner(entity);
            if (owner == null) {
                return false;
            }
            Field target = chain[chain.length - 1];
            try {
                Class<?> type = target.getType();
                if (type == float.class) {
                    target.setFloat(owner, value);
                    return true;
                }
                if (type == double.class) {
                    target.setDouble(owner, value);
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
