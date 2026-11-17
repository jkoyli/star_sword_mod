import cn.autoforged.star_sword_mod_1785589476.agent.MappingTable;
import cn.autoforged.star_sword_mod_1785589476.agent.GuardTransformer;
import cn.autoforged.star_sword_mod_1785589476.agent.Rules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 产物级验证：<b>发布 jar 里的 agent 与 SRG reobf 是否对得上</b>。
 *
 * <h2>要防的失败</h2>
 * <p>这里有两个命名空间，任何一处错位都只在正式包里炸，而且炸得安静：
 * <ul>
 *   <li><b>mod 侧</b>：{@code reobfJar} 会把 mod 类里对 Minecraft 成员的引用改写成 SRG
 *       （{@code getHealth} → {@code m_21223_}）；</li>
 *   <li><b>agent 侧</b>：内嵌的 {@code agent/star_sword_agent.jar} 不参与 reobf，它靠
 *       {@link MappingTable} 自己写 SRG 名，再通过 javassist 片段调用 mod 侧的桥接类。</li>
 * </ul>
 * <p>所以「agent 的字符串名」必须与「mod 侧被 reobf 后剩下的名字」严格一致。历史上
 * {@code setHealth} 曾写成 {@code m_21150_}（真值 {@code m_21153_}），结果整条规则在生产环境
 * 静默变成「方法不存在」——单看对账分不清「类里真没有」还是「名字写错了」。
 *
 * <h2>检查项</h2>
 * <ol>
 *   <li>内嵌 agent jar 存在、manifest 带 {@code Agent-Class} / {@code Can-Retransform-Classes}；</li>
 *   <li>内嵌 agent jar <b>没有</b>任何结构级 Minecraft 引用（Class / Fieldref / Methodref）——
 *       一旦有，就说明它也需要 reobf，而现在没有；它的 MC 耦合只允许是字符串常量；</li>
 *   <li>规则片段里调用的每个桥接符号，都真实存在于<b>已 reobf 的</b> {@code AgentHooks} 里且是
 *       {@code public static}（两个命名空间对齐的直接证据）；</li>
 *   <li>agent 侧反射调用的桥接方法（{@code reportAttached} 等）同样存在；</li>
 *   <li>给 tsrg 时，逐条规则核对内置 SRG 表与真实映射一致（与 {@code MappingTableTest} 同源，
 *       但规则列表从 {@link Rules#bridge()} 推导，不会与真实规则表漂移）。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test -cp agent/build/classes/java/main tools/AgentBundleTest.java
 * java -cp "build/tools-test;agent/build/classes/java/main" AgentBundleTest \
 *      build/libs/star_sword_mod-&lt;version&gt;.jar [build/moddev/artifacts/namedToIntermediate.tsrg]
 * </pre>
 */
public final class AgentBundleTest {

    /** 内嵌路径，与 build.gradle 的 processResources 约定一致。 */
    private static final String NESTED_AGENT = "agent/star_sword_agent.jar";

    /** mod 侧的桥接类（被 reobf 的那一份）。 */
    private static final String BRIDGE_CLASS =
            "cn/autoforged/star_sword_mod_1785589476/agent/AgentHooks.class";

    /** agent 侧<b>反射</b>调用的桥接方法（见 {@code StarSwordAgent#bridgeCall} 与 watchdog）。 */
    private static final String[] REFLECTIVE_BRIDGE = {
            "reportAttached", "reportInjectionState", "reportTamper", "reportReplay", "hookCallCount"
    };

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: AgentBundleTest <mod.jar> [namedToIntermediate.tsrg]");
            System.exit(2);
        }
        Path modJar = Path.of(args[0]);
        Path tsrg = args.length > 1 ? Path.of(args[1]) : null;

        System.out.println("[1] 内嵌 agent jar / manifest");
        Map<String, byte[]> agentClasses = readNestedAgent(modJar);

        System.out.println("[2] agent jar 内是否混入结构级 Minecraft 引用");
        checkNoStructuralMcRefs(agentClasses);

        System.out.println("[3] 桥接符号：规则片段 → 已 reobf 的 AgentHooks");
        Map<String, List<MethodInfo>> bridge = readBridgeMethods(modJar);
        checkSnippetSymbols(bridge);
        checkReflectiveSymbols(bridge);

        System.out.println("[4] 规则表 → 内置 SRG 表覆盖");
        checkMappingCoverage(tsrg);

        System.out.println(failures == 0 ? "ALL PASS" : "FAILED (" + failures + " 项)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- [1]

    private static Map<String, byte[]> readNestedAgent(Path modJar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        byte[] jarBytes = null;
        try (ZipFile zip = new ZipFile(modJar.toFile())) {
            ZipEntry entry = zip.getEntry(NESTED_AGENT);
            check(entry != null, "mod jar 里存在 " + NESTED_AGENT);
            if (entry == null) {
                return Map.of();
            }
            jarBytes = readAll(zip.getInputStream(entry));
        }
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), readAll(zis));
                }
            }
        }
        check(!entries.isEmpty(), "内嵌 agent jar 可解析（条目=" + entries.size() + "）");

        byte[] manifest = entries.get("META-INF/MANIFEST.MF");
        check(manifest != null, "内嵌 agent jar 带 META-INF/MANIFEST.MF");
        if (manifest != null) {
            String text = new String(manifest, StandardCharsets.UTF_8);
            check(text.contains("Agent-Class:"), "manifest 声明 Agent-Class（运行期 attach 必需）");
            check(text.contains("Can-Retransform-Classes: true"),
                    "manifest 声明 Can-Retransform-Classes: true（retransform 必需）");
        }
        long classCount = entries.keySet().stream().filter(k -> k.startsWith("cn/")).count();
        check(classCount > 0, "内嵌 agent jar 含 agent 自身类（" + classCount + " 个）");
        return entries;
    }

    // ---------------------------------------------------------------- [2]

    /**
     * agent jar 目前<b>不该</b>需要 reobf：它不直接 link Minecraft 类型，只用字符串类名 +
     * Javassist 片段。这条断言的作用是把「不需要」变成「可验证」——一旦哪天有人 import 了 MC 类型，
     * 这里会立刻红，而不是等到正式包在 {@code NoSuchMethodError} 上炸。
     */
    private static void checkNoStructuralMcRefs(Map<String, byte[]> entries) {
        int scanned = 0;
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            scanned++;
            try {
                ClassFile cf = ClassFile.parse(entry.getValue());
                for (Ref ref : cf.mcRefs()) {
                    offenders.add(entry.getKey() + " -> " + ref);
                }
            } catch (IOException e) {
                offenders.add(entry.getKey() + " -> 无法解析: " + e);
            }
        }
        check(scanned > 0, "扫描了 agent 的类文件（" + scanned + " 个）");
        if (!offenders.isEmpty()) {
            offenders.stream().limit(10).forEach(o -> System.out.println("      ! " + o));
        }
        check(offenders.isEmpty(),
                "agent 类不含 Class/Fieldref/Methodref 级 Minecraft 引用（因此无需 reobf）");
    }

    // ---------------------------------------------------------------- [3]

    /** 取出「已 reobf 的」AgentHooks 方法表：名字 → 全部重载（含 public static 与描述符）。 */
    private static Map<String, List<MethodInfo>> readBridgeMethods(Path modJar) throws IOException {
        byte[] bytes;
        try (ZipFile zip = new ZipFile(modJar.toFile())) {
            ZipEntry entry = zip.getEntry(BRIDGE_CLASS);
            if (entry == null) {
                check(false, "mod jar 里存在 " + BRIDGE_CLASS);
                return Map.of();
            }
            bytes = readAll(zip.getInputStream(entry));
        }
        ClassFile cf = ClassFile.parse(bytes);
        check(cf.srgRefCount() > 0,
                "mod 侧 AgentHooks 已 reobf（含 " + cf.srgRefCount() + " 处 SRG 成员引用）");
        check(!cf.methods.isEmpty(), "解析出 AgentHooks 方法（" + cf.methods.size() + " 个）");
        Map<String, List<MethodInfo>> byName = new LinkedHashMap<>();
        for (MethodInfo m : cf.methods) {
            byName.computeIfAbsent(m.name, k -> new ArrayList<>()).add(m);
        }
        return byName;
    }

    /**
     * 规则片段里 {@code %1$s.<symbol>(...)} 的每个符号，都必须在桥接类里存在一个
     * <b>public static 且实参个数一致</b>的方法。
     *
     * <p>为什么要查实参个数：Javassist 按「方法名 + 实参个数」解析调用，签名改一位（例如
     * {@code shouldCancelRemove} 从两参变三参）不会报编译错，只会在运行期让整个类注入失败。
     */
    private static void checkSnippetSymbols(Map<String, List<MethodInfo>> bridge) {
        Map<String, Integer> symbols = new LinkedHashMap<>();
        for (Rules.Rule rule : Rules.forMode(GuardTransformer.Mode.BRIDGE)) {
            collectSymbols(rule.body(), rule.id(), symbols);
            collectSymbols(rule.fallback(), rule.id() + ".fallback", symbols);
        }
        check(!symbols.isEmpty(), "从规则片段里提取到桥接符号（" + symbols.size() + " 个）");
        for (Map.Entry<String, Integer> entry : symbols.entrySet()) {
            String symbol = entry.getKey();
            int arity = entry.getValue();
            List<MethodInfo> overloads = bridge.getOrDefault(symbol, List.of());
            boolean ok = overloads.stream()
                    .anyMatch(m -> m.isPublic && m.isStatic && paramCount(m.descriptor) == arity);
            String detail = ok ? ""
                    : (overloads.isEmpty() ? " —— 名字不存在"
                            : " —— 实参个数对不上：" + overloads);
            check(ok, "桥接符号 " + symbol + "(" + arity + " 参) 在已 reobf 的 AgentHooks 里 public static"
                    + detail);
        }
    }

    /**
     * 从片段里收集 {@code %1$s.符号(实参...)}：记下符号名与实参个数。
     *
     * <p>{@code $0}/{@code $1} 这些是 Javassist 的占位符，一起算进实参个数即可——桥接方法收到
     * 的正是它们，所以个数必须严格一致。
     */
    private static void collectSymbols(String snippet, String where, Map<String, Integer> out) {
        if (snippet == null) {
            return;
        }
        // 片段里对桥接类的调用统一写成 "%1$s.xxx(" ，替换后即 "<类名>.xxx("。
        String marker = "%1$s.";
        int at = snippet.indexOf(marker);
        while (at >= 0) {
            int start = at + marker.length();
            int end = start;
            while (end < snippet.length()
                    && Character.isJavaIdentifierPart(snippet.charAt(end))) {
                end++;
            }
            if (end > start && end < snippet.length() && snippet.charAt(end) == '(') {
                String name = snippet.substring(start, end);
                int close = matchingParen(snippet, end);
                int arity = countTopLevelArgs(snippet.substring(end + 1, close));
                Integer previous = out.get(name);
                if (previous == null || previous != arity) {
                    if (previous != null) {
                        System.out.println("      ! 同一桥接符号出现不同实参个数：" + name
                                + " 先=" + previous + " 后=" + arity + "（规则 " + where + "）");
                    }
                    out.put(name, arity);
                }
            }
            at = snippet.indexOf(marker, end);
        }
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return text.length() - 1;
    }

    /** 顶层逗号分隔的实参数；空串算 0 个。 */
    private static int countTopLevelArgs(String args) {
        String trimmed = args.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    /** JVM 方法描述符里的实参个数。 */
    private static int paramCount(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return -1;
        }
        int count = 0;
        int i = 1; // 跳过 '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int end = typeEnd(descriptor, i);
            if (end < 0) {
                return -1;
            }
            count++;
            i = end;
        }
        return count;
    }

    /** 返回该类型描述符结束后的下标。 */
    private static int typeEnd(String descriptor, int start) {
        int i = start;
        while (i < descriptor.length() && descriptor.charAt(i) == '[') {
            i++;
        }
        if (i >= descriptor.length()) {
            return -1;
        }
        char c = descriptor.charAt(i);
        if (c == 'L') {
            int semi = descriptor.indexOf(';', i);
            return semi < 0 ? -1 : semi + 1;
        }
        return i + 1; // 基本类型
    }

    private static void checkReflectiveSymbols(Map<String, List<MethodInfo>> bridge) {
        for (String symbol : REFLECTIVE_BRIDGE) {
            List<MethodInfo> overloads = bridge.getOrDefault(symbol, List.of());
            boolean ok = overloads.stream().anyMatch(m -> m.isPublic && m.isStatic);
            check(ok, "反射桥接方法 " + symbol + " (public static)");
        }
    }

    // ---------------------------------------------------------------- [4]

    /**
     * 规则表 → 内置 SRG 表：每条规则都要能解析出至少一个候选名；给 tsrg 时逐条比对。
     *
     * <p>规则列表直接从 {@link Rules#bridge()} 取，而不是照抄一份——照抄的那份会在加规则时静默过期，
     * 这正是 {@code MappingTableTest} 曾经的风险。
     */
    private static void checkMappingCoverage(Path tsrg) throws IOException {
        MappingTable table = MappingTable.fallback();
        Map<String, Map<String, String>> expected = tsrg == null ? null : parseTsrg(tsrg);
        System.out.println("      规则条数=" + Rules.forMode(GuardTransformer.Mode.BRIDGE).size()
                + (tsrg == null ? "（未提供 tsrg，只做非空校验）" : "，tsrg=" + tsrg.getFileName()));
        int compared = 0;
        for (Rules.Rule rule : Rules.forMode(GuardTransformer.Mode.BRIDGE)) {
            String owner = rule.owner().replace('.', '/');
            List<String> candidates = table.candidates(owner, rule.method(), rule.descriptor());
            check(!candidates.isEmpty(), "内置表覆盖 " + shortName(owner) + "." + rule.method()
                    + " " + rule.descriptor() + " -> " + candidates);
            if (expected == null) {
                continue;
            }
            String want = expected.getOrDefault(owner, Map.of())
                    .get(rule.method() + " " + rule.descriptor());
            if (want == null) {
                // 映射文件里没有该类的这条方法：说明是继承来的（如 Player.getHealth），跳过比对。
                continue;
            }
            compared++;
            check(want.equals(candidates.get(0)),
                    "内置表 " + shortName(owner) + "." + rule.method() + " 期望=" + want
                            + " 实际=" + candidates.get(0));
        }
        if (tsrg != null) {
            check(compared > 0, "与 tsrg 实际比对过的条目=" + compared);
        }
    }

    private static Map<String, Map<String, String>> parseTsrg(Path file) throws IOException {
        Map<String, Map<String, String>> table = new HashMap<>();
        String current = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) != '\t') {
                current = line.split(" ")[0];
                table.computeIfAbsent(current, k -> new HashMap<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3) {
                table.get(current).put(parts[0] + " " + parts[1], parts[2]);
            }
        }
        return table;
    }

    // ---------------------------------------------------------------- 基础工具

    private static String shortName(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 一条方法：名字 / 描述符 / 是否 public + static。 */
    private record MethodInfo(String name, String descriptor, boolean isPublic, boolean isStatic) {
    }

    /** 一处指向 Minecraft 的结构级引用（用于失败信息）。 */
    private record Ref(String kind, String target) {
        @Override
        public String toString() {
            return kind + " " + target;
        }
    }

    /**
     * 极简 class 解析：只做到方法表，够我们用（不引第三方依赖，和 tools/ 其它断言保持一致）。
     */
    private static final class ClassFile {

        private String[] utf8 = new String[0];
        /** 指向 Minecraft 的结构级引用（Class / Fieldref / Methodref / InterfaceMethodref）。 */
        private final List<Ref> mcRefs = new ArrayList<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        /** 常量池里出现的 SRG 成员名数量，用作「这个类确实被 reobf 过」的证据。 */
        private int srgRefCount;

        private ClassFile() {
        }

        static ClassFile parse(byte[] bytes) throws IOException {
            ClassFile cf = new ClassFile();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            cf.utf8 = new String[cpCount];
            // 先建索引表：常量池项大小随 tag 不同，且 long/double 占两个槽位。
            int[] tag = new int[cpCount];
            int[] a = new int[cpCount];
            int[] b = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int t = in.readUnsignedByte();
                tag[i] = t;
                switch (t) {
                    case 1 -> { // Utf8
                        int len = in.readUnsignedShort();
                        byte[] raw = new byte[len];
                        in.readFully(raw);
                        cf.utf8[i] = new String(raw, StandardCharsets.UTF_8);
                    }
                    case 3, 4 -> in.readInt();      // Integer / Float
                    case 5, 6 -> {                  // Long / Double 占两个槽位
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
                    case 15 -> {                    // MethodHandle
                        in.readUnsignedByte();
                        a[i] = in.readUnsignedShort();
                    }
                    case 9, 10, 11, 12, 17, 18 -> {
                        a[i] = in.readUnsignedShort();
                        b[i] = in.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant tag " + t + " at " + i);
                }
            }
            // 解析引用：Class 条目 + 成员引用（owner 来自 Class 条目）。
            for (int i = 1; i < cpCount; i++) {
                switch (tag[i]) {
                    case 7 -> {
                        String name = cf.utf8(a[i]);
                        if (name != null && name.startsWith("net/minecraft")) {
                            cf.mcRefs.add(new Ref("Class", name));
                        }
                    }
                    case 9, 10, 11 -> {
                        // a[i] = class_index（指向 Class 条目，不是 Utf8），需要再解一层。
                        String owner = null;
                        int ownerIndex = a[i];
                        if (ownerIndex > 0 && ownerIndex < cpCount && tag[ownerIndex] == 7) {
                            owner = cf.utf8(a[ownerIndex]);
                        }
                        String member = cf.utf8(b[i]);
                        String kind = tag[i] == 9 ? "Fieldref" : tag[i] == 10 ? "Methodref" : "InterfaceMethodref";
                        if (owner != null && owner.startsWith("net/minecraft")) {
                            cf.mcRefs.add(new Ref(kind, owner + "." + member));
                        } else if (member != null && isSrgName(member)) {
                            cf.srgRefCount++;
                        }
                    }
                    case 12 -> {
                        String member = cf.utf8(a[i]);
                        if (member != null && isSrgName(member)) {
                            cf.srgRefCount++;
                        }
                    }
                    default -> {
                    }
                }
            }
            in.readUnsignedShort(); // access_flags
            in.readUnsignedShort(); // this_class
            in.readUnsignedShort(); // super_class
            int interfaces = in.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) {
                in.readUnsignedShort();
            }
            cf.skipMembers(in, false); // fields
            cf.skipMembers(in, true);  // methods
            return cf;
        }

        private static boolean isSrgName(String name) {
            return name.startsWith("m_") || name.startsWith("f_");
        }

        private String utf8(int index) {
            return index > 0 && index < utf8.length ? utf8[index] : null;
        }

        /** 字段或方法表：字段只跳过，方法要记下来。 */
        private void skipMembers(DataInputStream in, boolean collectMethods) throws IOException {
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                int access = in.readUnsignedShort();
                String name = utf8(in.readUnsignedShort());
                String descriptor = utf8(in.readUnsignedShort());
                int attributes = in.readUnsignedShort();
                for (int j = 0; j < attributes; j++) {
                    in.readUnsignedShort();     // attribute_name_index
                    int length = in.readInt();
                    in.skipNBytes(length);
                }
                if (collectMethods) {
                    boolean isPublic = (access & 0x0001) != 0;
                    boolean isStatic = (access & 0x0008) != 0;
                    methods.add(new MethodInfo(name, descriptor, isPublic, isStatic));
                }
            }
        }

        List<Ref> mcRefs() {
            return mcRefs;
        }

        int srgRefCount() {
            return srgRefCount;
        }
    }

    private AgentBundleTest() {
    }
}
