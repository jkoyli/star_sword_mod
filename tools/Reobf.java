import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 离线 reobf：把 javac 以 named（parchment）映射编译出来的 mod 类，重映射到 Forge 生产运行时的
 * intermediate（SRG）命名空间。等价于 Gradle 的 reobfJar / legacyforge 的 reobf 任务。
 *
 * <p>为什么需要它：本机沙箱里 Gradle 跑不起来（要写 ~/.gradle 被拒），只能 javac + 手工打包；
 * 而手工打的包是 named 映射，装进生产客户端会在第一个 MC 成员引用处炸掉
 * （实测 `NoSuchFieldError: CREATIVE_MODE_TAB`）。
 *
 * <p>用法：
 * <pre>
 * java -cp asm.jar:asm-commons.jar:asm-tree.jar:. Reobf \
 *      build/moddev/artifacts/namedToIntermediate.tsrg &lt;in.jar&gt; &lt;out.jar&gt; &lt;verify-dir&gt;
 * </pre>
 * 第三阶段会把重映射后的每个 MC/Forge 引用逐个按继承链校验是否真实存在，输出无法解析的引用。
 */
public final class Reobf {

    private static final Map<String, String> CLASS_MAP = new HashMap<>();
    /** owner + '#' + fieldName -> newName */
    private static final Map<String, String> FIELD_MAP = new HashMap<>();
    /** owner + '#' + methodName + desc -> newName */
    private static final Map<String, String> METHOD_MAP = new HashMap<>();
    /** 兜底索引：fieldName -> 可能的新名集合；methodName+desc -> 可能的新名集合。 */
    private static final Map<String, java.util.Set<String>> FIELD_BY_NAME = new HashMap<>();
    private static final Map<String, java.util.Set<String>> METHOD_BY_NAME_DESC = new HashMap<>();

    /** named 侧的继承索引：用来把「引用点 owner」换算成「真正声明该成员的类」，再查映射。 */
    private static NamedIndex NAMED;

    private Reobf() {
    }

    public static void main(String[] args) throws Exception {
        Path tsrg = Paths.get(args[0]);
        Path inJar = Paths.get(args[1]);
        Path outJar = Paths.get(args[2]);
        boolean verify = args.length > 3;

        long t0 = System.currentTimeMillis();
        parseTsrg(tsrg);
        NAMED = new NamedIndex();
        for (int i = 4; i < args.length; i++) {
            NAMED.addJar(Paths.get(args[i]));
        }
        System.out.println("[reobf] mappings: classes=" + CLASS_MAP.size()
                + " fields=" + FIELD_MAP.size() + " methods=" + METHOD_MAP.size()
                + " | namedIndex classes=" + NAMED.size()
                + " took=" + (System.currentTimeMillis() - t0) + "ms");

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String v = CLASS_MAP.get(internalName);
                return v == null ? internalName : v;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String declaring = NAMED.declaringClassOfField(owner, name);
                if (declaring != null) {
                    String hit = FIELD_MAP.get(declaring + '#' + name);
                    if (hit != null) {
                        return hit;
                    }
                }
                String hit = FIELD_MAP.get(owner + '#' + name);
                if (hit != null) {
                    return hit;
                }
                if (!isGameNamespace(owner)) {
                    return name;
                }
                return pickUnique(FIELD_BY_NAME.get(name), name);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String declaring = NAMED.declaringClassOfMethod(owner, name, descriptor);
                if (declaring != null) {
                    String hit = METHOD_MAP.get(declaring + '#' + name + descriptor);
                    if (hit != null) {
                        return hit;
                    }
                }
                String hit = METHOD_MAP.get(owner + '#' + name + descriptor);
                if (hit != null) {
                    return hit;
                }
                if (!isGameNamespace(owner)) {
                    return name;
                }
                return pickUnique(METHOD_BY_NAME_DESC.get(name + descriptor), name);
            }
        };

        int remapped = 0;
        try (ZipFile zin = new ZipFile(inJar.toFile());
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(outJar))) {
            List<? extends ZipEntry> entries = zin.stream().toList();
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data;
                try (InputStream is = zin.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    data = remapClass(data, remapper);
                    remapped++;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
        System.out.println("[reobf] wrote " + outJar + " classes=" + remapped);

        if (verify) {
            new Verifier(Paths.get(args[3])).run(outJar);
        }
    }

    private static byte[] remapClass(byte[] data, Remapper remapper) {
        ClassReader reader = new ClassReader(data);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper),
                ClassReader.EXPAND_FRAMES);
        return fixLambdas(writer.toByteArray());
    }

    /**
     * 修正 lambda 的 SAM 方法名（第二遍）。
     *
     * <p><b>为什么需要单独一遍</b>：{@code displayItems((params, output) -> …)} 编译成
     * {@code invokedynamic}，其"实现的是哪个接口方法"是以 <b>字符串常量</b> 的形式放在
     * {@code LambdaMetafactory} 的 bootstrap 参数里的（indy 名字）。ASM 的 {@code ClassRemapper}
     * 只重映射类型/描述符，<b>不会</b>改这个字符串，于是运行期出现：
     * <pre>
     * AbstractMethodError: … does not define or inherit an implementation of the resolved
     * method 'abstract void m_257865_(…ItemDisplayParameters, …Output)'
     * of interface …CreativeModeTab$DisplayItemsGenerator
     * </pre>
     * 即接口方法已叫 {@code m_257865_}，而 lambda 还在实现 {@code accept}。
     *
     * <p>这里按 {@code LambdaMetafactory} 的约定还原：indy 的<b>返回类型</b>就是函数式接口，
     * {@code args[0]} 是 SAM 的方法类型；用这两者去映射表里查出接口方法在 SRG 下的名字并改写。
     * 只处理 {@code java/lang/invoke/LambdaMetafactory}，JDK 接口的 lambda 因查不到映射而保持原样。
     */
    private static byte[] fixLambdas(byte[] data) {
        ClassReader reader = new ClassReader(data);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor mv =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                            org.objectweb.asm.Handle bsm, Object... args) {
                        String newName = indyName;
                        if (bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                                && args.length > 0 && args[0] instanceof org.objectweb.asm.Type sam) {
                            org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(indyDesc);
                            if (ret.getSort() == org.objectweb.asm.Type.OBJECT) {
                                String itf = ret.getInternalName();
                                String hit = METHOD_MAP.get(itf + '#' + indyName
                                        + sam.getDescriptor());
                                if (hit == null && isGameNamespace(itf)) {
                                    hit = pickUnique(METHOD_BY_NAME_DESC.get(
                                            indyName + sam.getDescriptor()), null);
                                }
                                if (hit != null) {
                                    newName = hit;
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(newName, indyDesc, bsm, args);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    // ===================== named 侧继承索引 =====================

    /**
     * 从 named（parchment）版的 MC / Forge jar 里建继承索引。
     *
     * <p>为什么必须有它：字节码里的 {@code MethodInsn.owner} 是<b>引用点</b>的类型，不一定是声明该成员的类
     * （例如 {@code LocalPlayer.getHealth()} 实际声明在 {@code LivingEntity}）。映射表是按声明类组织的，
     * 不先沿继承链找到声明类就会漏映射或误用同名映射。
     */
    private static final class NamedIndex {

        private final Map<String, Info> infos = new HashMap<>();
        private final Map<String, String> fieldDecl = new HashMap<>();
        private final Map<String, String> methodDecl = new HashMap<>();

        int size() {
            return infos.size();
        }

        void addJar(Path jar) throws IOException {
            if (!Files.isRegularFile(jar)) {
                System.out.println("[reobf][warn] named jar 不存在: " + jar);
                return;
            }
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                for (ZipEntry e : new ArrayList<>(zf.stream().toList())) {
                    if (!e.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] data;
                    try (InputStream is = zf.getInputStream(e)) {
                        data = is.readAllBytes();
                    }
                    index(data);
                }
            }
        }

        private void index(byte[] data) {
            ClassReader reader = new ClassReader(data);
            Info info = new Info(reader.getSuperName(), reader.getInterfaces());
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.FieldVisitor visitField(int access, String name,
                        String descriptor, String signature, Object value) {
                    info.fields.add(name);
                    return null;
                }

                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    info.methods.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            infos.put(reader.getClassName(), info);
        }

        String declaringClassOfField(String owner, String name) {
            String key = "F" + owner + '#' + name;
            if (fieldDecl.containsKey(key)) {
                return fieldDecl.get(key);
            }
            String result = walk(owner, name, null, false, 0);
            fieldDecl.put(key, result);
            return result;
        }

        String declaringClassOfMethod(String owner, String name, String descriptor) {
            String key = "M" + owner + '#' + name + descriptor;
            if (methodDecl.containsKey(key)) {
                return methodDecl.get(key);
            }
            String result = walk(owner, name, descriptor, true, 0);
            methodDecl.put(key, result);
            return result;
        }

        private String walk(String owner, String name, String descriptor, boolean method, int depth) {
            if (owner == null || depth > 12) {
                return null;
            }
            Info info = infos.get(owner);
            if (info == null) {
                return null;
            }
            if (method ? info.methods.contains(name + descriptor) : info.fields.contains(name)) {
                return owner;
            }
            for (String itf : info.interfaces) {
                String hit = walk(itf, name, descriptor, method, depth + 1);
                if (hit != null) {
                    return hit;
                }
            }
            return walk(info.superName, name, descriptor, method, depth + 1);
        }

        private static final class Info {
            final String superName;
            final String[] interfaces;
            final java.util.Set<String> fields = new java.util.HashSet<>();
            final java.util.Set<String> methods = new java.util.HashSet<>();

            Info(String superName, String[] interfaces) {
                this.superName = superName;
                this.interfaces = interfaces == null ? new String[0] : interfaces;
            }
        }
    }

    // ===================== TSRG 解析 =====================

    private static void parseTsrg(Path tsrg) throws IOException {
        try (Stream<String> lines = Files.lines(tsrg, StandardCharsets.UTF_8)) {
            String currentClass = null;
            for (String raw : (Iterable<String>) lines::iterator) {
                if (raw.isEmpty()) {
                    continue;
                }
                if (raw.charAt(0) != '\t') {
                    // 顶层：named intermediate
                    String[] parts = raw.trim().split("\\s+");
                    if (parts.length >= 2) {
                        currentClass = parts[0];
                        CLASS_MAP.put(parts[0], parts[1]);
                    }
                    continue;
                }
                if (currentClass == null) {
                    continue;
                }
                String[] parts = raw.trim().split("\\s+");
                if (parts.length == 2) {
                    // 字段：name newName
                    FIELD_MAP.put(currentClass + '#' + parts[0], parts[1]);
                    noteUnique(FIELD_BY_NAME, parts[0], parts[1]);
                } else if (parts.length == 3) {
                    // 方法：name desc newName
                    METHOD_MAP.put(currentClass + '#' + parts[0] + parts[1], parts[2]);
                    noteUnique(METHOD_BY_NAME_DESC, parts[0] + parts[1], parts[2]);
                }
            }
        }
        
    }

        private static void noteUnique(Map<String, java.util.Set<String>> map, String key, String value) {
        map.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(value);
    }

    /**
     * owner 是否属于会被 SRG 混淆的命名空间。
     *
     * <p><b>这是本工具最容易出错的一处</b>：{@code keySet()Ljava/util/Set;} 这种签名在 MC 里也有同名成员，
     * 若对 {@code java.util.Map} 这类 JDK 类型也走"唯一同名"兜底，就会把 {@code Map.keySet()} 改成
     * {@code Map.m_6579_()}，运行期直接 {@code NoSuchMethodError}（2.2.11 就是这么炸的）。
     * 因此兜底只允许落在 MC / Forge 自己的类型上。
     */
    private static boolean isGameNamespace(String owner) {
        if (owner == null) {
            return false;
        }
        if (CLASS_MAP.containsKey(owner)) {
            return true;
        }
        return owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge/")
                || owner.startsWith("cpw/mods/") || owner.startsWith("com/mojang/blaze3d/");
    }
    /** 候选新名唯一（或全部相同）时才采用，否则保持原样，交给校验阶段报出来。 */
    private static String pickUnique(java.util.Set<String> candidates, String fallback) {
        if (candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (String c : candidates) {
            distinct.add(c);
            if (distinct.size() > 1) {
                return fallback;
            }
        }
        return candidates.iterator().next();
    }

    // ===================== 校验：重映射后的每个引用都要能按继承链解析到 =====================

    private static final class Verifier {

        private final Map<String, ClassInfo> index = new HashMap<>();
        private final List<Path> jars = new ArrayList<>();

        Verifier(Path dirOrJar) throws IOException {
            jars.add(dirOrJar);
        }

        void run(Path modJar) throws IOException {
            // 索引运行时实际可见的 MC/Forge 类：SRG 版 joined + forge。
            Path mcpRepo = Paths.get(System.getProperty("srg.mc", ""));
            if (Files.exists(mcpRepo)) {
                jars.add(mcpRepo);
            }
            for (Path jar : jars) {
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                try (ZipFile zf = new ZipFile(jar.toFile())) {
                    for (ZipEntry e : new ArrayList<>(zf.stream().toList())) {
                        if (!e.getName().endsWith(".class")) {
                            continue;
                        }
                        try (InputStream is = zf.getInputStream(e)) {
                            indexClass(is.readAllBytes());
                        }
                    }
                }
            }
            System.out.println("[verify] indexed classes=" + index.size());

            int unresolved = 0;
            try (ZipFile zf = new ZipFile(modJar.toFile())) {
                for (ZipEntry e : new ArrayList<>(zf.stream().toList())) {
                    if (!e.getName().endsWith(".class")) {
                        continue;
                    }
                    byte[] data;
                    try (InputStream is = zf.getInputStream(e)) {
                        data = is.readAllBytes();
                    }
                    ClassReader reader = new ClassReader(data);
                    reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                                String descriptor, String signature, String[] exceptions) {
                            return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String fName,
                                        String fDesc) {
                                    check(e.getName(), owner, fName, fDesc, false);
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String mName,
                                        String mDesc, boolean itf) {
                                    if (mName.startsWith("<")) {
                                        return;
                                    }
                                    check(e.getName(), owner, mName, mDesc, true);
                                }

                                @Override
                                public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                                        org.objectweb.asm.Handle bsm, Object... args) {
                                    // lambda 的 SAM 方法名是字符串常量：映射漏改会在运行期 AbstractMethodError。
                                    if (!bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                                            || args.length == 0
                                            || !(args[0] instanceof org.objectweb.asm.Type sam)) {
                                        return;
                                    }
                                    org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(indyDesc);
                                    if (ret.getSort() != org.objectweb.asm.Type.OBJECT) {
                                        return;
                                    }
                                    String itf = ret.getInternalName();
                                    if (!itf.startsWith("net/minecraft/")) {
                                        return;
                                    }
                                    if (!resolve(itf, indyName, sam.getDescriptor(), true, 0)) {
                                        System.out.println("[verify][BAD_LAMBDA] " + e.getName() + " -> "
                                                + itf + '.' + indyName + sam.getDescriptor());
                                    }
                                }

                                private void check(String where, String owner, String member,
                                        String desc, boolean method) {
                                    if (!needsCheck(owner)) {
                                        // 库/JDK 类型不该出现 SRG 名：出现了就是重映射越界（2.2.11 的事故点）。
                                        if (member.matches("[fm]_\\d+_")) {
                                            System.out.println("[verify][BAD_REMAP] " + where
                                                    + " -> " + owner + '.' + member);
                                        }
                                        return;
                                    }
                                    if (resolve(owner, member, desc, method, 0)) {
                                        return;
                                    }
                                    System.out.println("[verify][UNRESOLVED] " + where + " -> "
                                            + owner + '.' + member + (method ? desc : " " + desc));
                                }
                            };
                        }
                    }, 0);
                }
            }
            System.out.println("[verify] done, unresolved lines above (if any)");
        }

        private static boolean needsCheck(String owner) {
            return owner.startsWith("net/minecraft") || owner.startsWith("net/minecraftforge")
                    || owner.startsWith("com/mojang") || owner.startsWith("cpw/mods");
        }

        private boolean resolve(String owner, String name, String desc, boolean method, int depth) {
            if (depth > 8) {
                return false;
            }
            ClassInfo info = index.get(owner);
            if (info == null) {
                return false;
            }
            if (method) {
                if (info.methods.contains(name + desc)) {
                    return true;
                }
            } else if (info.fields.contains(name + ' ' + desc)) {
                return true;
            }
            for (String itf : info.interfaces) {
                if (resolve(itf, name, desc, method, depth + 1)) {
                    return true;
                }
            }
            return info.superName != null && resolve(info.superName, name, desc, method, depth + 1);
        }

        private void indexClass(byte[] data) {
            ClassReader reader = new ClassReader(data);
            String name = reader.getClassName();
            String superName = reader.getSuperName();
            String[] interfaces = reader.getInterfaces();
            ClassInfo info = new ClassInfo(superName, interfaces);
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.FieldVisitor visitField(int access, String fName,
                        String descriptor, String signature, Object value) {
                    info.fields.add(fName + ' ' + descriptor);
                    return null;
                }

                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String mName,
                        String descriptor, String signature, String[] exceptions) {
                    info.methods.add(mName + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            index.put(name, info);
        }

        private static final class ClassInfo {
            final String superName;
            final String[] interfaces;
            final java.util.Set<String> fields = new java.util.HashSet<>();
            final java.util.Set<String> methods = new java.util.HashSet<>();

            ClassInfo(String superName, String[] interfaces) {
                this.superName = superName;
                this.interfaces = interfaces == null ? new String[0] : interfaces;
            }
        }
    }
}
