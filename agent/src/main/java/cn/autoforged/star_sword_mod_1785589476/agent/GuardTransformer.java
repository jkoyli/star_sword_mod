package cn.autoforged.star_sword_mod_1785589476.agent;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proof-of-concept transformer.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code probe} - changes nothing, only reports what the agent sees (class, loader,
 *       whether the incoming bytes already contain our Mixin handlers, and which runtime
 *       name each target member has). Diagnostic mode; never the default.</li>
 *   <li>{@code guard} - injects one sentinel guard ({@code 42.0F}) into
 *       {@code LivingEntity.getHealth()F}; kept as a regression tool.</li>
 *   <li>{@code bridge} - <b>the default</b> and the real one: every injected snippet only calls
 *       {@code AgentHooks}; the decision logic lives in the mod (SwordGuard /
 *       PlayerDefense), not here.</li>
 * </ul>
 *
 * <p>Javassist's {@code insertBefore} prepends at the very start of the method body,
 * i.e. in front of every Mixin {@code @At("HEAD")} handler already baked into these
 * bytes. That is the whole point: Mixin priority cannot outrank a transformer that runs
 * after Mixin has finished.
 *
 * <p>The {@link CtClass} is built with {@link ClassPool#makeClass(java.io.InputStream)}
 * straight from the bytes handed to the transformer. Resolving it through
 * {@code ClassPool.get()} is wrong here: that path may answer from the class loader, and
 * ModLauncher's loader serves the *untransformed* (pre-Mixin) class file, which silently
 * drops every method Mixin added and makes retransformation fail with
 * "attempted to delete a method".
 *
 * <p>Every failure is contained: a transformer that throws would abort class loading of
 * the whole game, so all exceptions are logged and result in "no transform".
 */
public final class GuardTransformer implements ClassFileTransformer {

    /** Byte sequence Mixin emits when it merges our handlers into the target class. */
    private static final byte[] MIXIN_MARKER = "starSword$".getBytes(StandardCharsets.ISO_8859_1);

    /** Mod-side bridge the generated code calls into; 单一定义在 {@link Rules}。 */
    private static final String BRIDGE_CLASS = Rules.BRIDGE_CLASS;

    /**
     * Patch cache: {@code className -> (hash of the input bytes, patched bytes)}.
     *
     * <p>On every watchdog replay the JVM hands us the same initial define-time bytes, and
     * running Javassist over a 130 KB class costs ~100-200 ms. Rerunning it once per replay per
     * class is pure waste, so the result is memoised and reused as long as the input hash
     * matches. This is also what makes the "incremental" replay cheap in practice.
     */
    private static final Map<String, CachedPatch> PATCH_CACHE = new ConcurrentHashMap<>();

    /**
     * 单类降级状态：{@code 类名 -> 被剔除的规则 id}。
     *
     * <p><b>为什么按类而不是按表。</b>旧实现用全局缩表（把整张 {@code injections} 砍成
     * “只留 getHealth HEAD”），于是一次 {@code VerifyError} 会把 {@code SynchedEntityData}
     * 写闸门、{@code Connection} 发包过滤、{@code Entity.remove} 白名单一起丢掉，而且对之后
     * 所有类和所有 watchdog 轮永久生效。现在失败只剔除“这一条规则 × 这一个类”，其它类照旧。
     *
     * <p><b>为什么键是规则 id 而不是下标。</b>规则表会增删条目；id 是稳定标识，降级结论不会因为
     * 表里插入一条规则就张冠李戴。
     *
     * <p><b>为什么是静态的。</b>watchdog 检测到篡改后会重新注册一个新的
     * {@link GuardTransformer}；降级结论必须跨实例保留，否则每轮重放都会重犯同一个失败。
     */
    private static final Map<String, Set<String>> SUPPRESSED = new ConcurrentHashMap<>();

    /**
     * 已成功应用的规则：{@code 类名 -> {规则 id}}。
     *
     * <p>mod 侧靠它判断“某个入口当前是否真的由 agent 接管”（例如 {@code setHealth} 的入参归一），
     * 而不是靠“agent 已附加”这种粗粒度信号——后者在注入被单类降级剔除后仍然为真。
     */
    private static final Map<String, Set<String>> APPLIED = new ConcurrentHashMap<>();

    /**
     * "目标方法在这个类里不存在"的规则 id。
     *
     * <p>这是"声明了但装不上"的正经原因之一（子类没声明覆写时 {@code findMethod} 返回 null，直接跳过），
     * 必须与"被降级剔除"分开统计，否则对账里的差额永远说不清。
     */
    private static final Set<String> NO_METHOD = ConcurrentHashMap.newKeySet();

    private record CachedPatch(long inputHash, long rulesHash, byte[] patched) {
    }

    private static final String DAMAGE_SOURCE = Rules.DAMAGE_SOURCE;
    private static final String LIVING_ENTITY = Rules.LIVING_ENTITY;
    private static final String PLAYER = Rules.PLAYER;
    private static final String SERVER_PLAYER = Rules.SERVER_PLAYER;
    private static final String LOCAL_PLAYER = Rules.LOCAL_PLAYER;
    private static final String ENTITY = Rules.ENTITY;
    private static final String SYNCHED_ENTITY_DATA = Rules.SYNCHED_ENTITY_DATA;
    private static final String CONNECTION = Rules.CONNECTION;

    /** FNV-1a over the class bytes; only used to decide whether the cached patch still applies. */
    private static long hash(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public enum Mode {
        PROBE,
        GUARD,
        BRIDGE
    }

    /**
     * 规则表里的一条规则被"编译"后的形态：片段已经格式化好，位置已由 {@link Rules.Kind} 解析。
     *
     * @param id        稳定标识（日志、对账、单类剔除都用它）
     * @param owner     binary class name declaring the method
     * @param namedMethod Mojang name of the method (SRG is looked up in {@link MappingTable})
     * @param descriptor JVM descriptor; class names are not remapped, so it is stable
     * @param body       Javassist source inserted into the method
     * @param position   {@link Position#HEAD} = prepend; {@link Position#TAIL} = before every
     *                   return statement; {@link Position#BODY} = whole-body replacement
     */
    private record Injection(String id, String owner, String namedMethod, String descriptor,
                             String body, Position position, String fallback) {
    }

    /** Where a snippet is inserted relative to the original method body. */
    private enum Position {
        HEAD,
        /** 整段替换方法体（失败时退回 {@link Injection#fallback()} 的 HEAD 注入）。 */
        BODY
    }

    private final Mode mode;
    private final Set<String> targets;
    private final MappingTable mapping;
    private final List<Injection> injections;
    /** 规则表指纹，参与补丁缓存键（同一份类字节 + 不同规则表必须重新生成）。 */
    private final long rulesHash;

    public GuardTransformer(Mode mode, Set<String> targets, MappingTable mapping) {
        this(Rules.forMode(mode), mode, targets, mapping);
    }

    /** 测试/灰度用：直接给一张规则表，跳过 {@link Rules#forMode}。 */
    public GuardTransformer(List<Rules.Rule> rules, Mode mode, Set<String> targets, MappingTable mapping) {
        this.mode = mode;
        this.targets = new LinkedHashSet<>(targets);
        this.mapping = mapping;
        this.injections = compile(rules);
        this.rulesHash = hashRules(this.injections);
    }

    /** 规则表指纹：id / 目标 / 描述符 / 位置 / 片段全部折叠进去。 */
    private static long hashRules(List<Injection> injections) {
        long result = 0xcbf29ce484222325L;
        for (Injection injection : injections) {
            String signature = injection.id() + '|' + injection.owner() + '|'
                    + injection.namedMethod() + '|' + injection.descriptor() + '|'
                    + injection.position() + '|' + injection.body() + '|' + injection.fallback();
            for (byte b : signature.getBytes(StandardCharsets.UTF_8)) {
                result ^= (b & 0xff);
                result *= 0x100000001b3L;
            }
        }
        return result;
    }

    /**
     * Classes the agent needs to retransform for the requested mode. Exposed so the
     * agent entry point and the default configuration stay in sync with the table.
     */
    public static Set<String> defaultTargets() {
        return new LinkedHashSet<>(List.of(
                LIVING_ENTITY, PLAYER, SERVER_PLAYER, LOCAL_PLAYER,
                ENTITY, SYNCHED_ENTITY_DATA, CONNECTION));
    }

    /**
     * 把 {@link Rules} 的声明编译成可施加的注入：格式化片段、把 {@link Rules.Kind} 解析成插入位置。
     *
     * <p>规则表只描述"写什么"，这里只负责"怎么落"：{@code WRAP} 与 {@code OBSERVE} 都是 HEAD 插入，
     * 区别只在片段内容（前者改写 {@code $1}，后者只记数），生成器不需要为它们加分支。
     */
    private static List<Injection> compile(List<Rules.Rule> rules) {
        List<Injection> compiled = new ArrayList<>();
        for (Rules.Rule rule : rules) {
            String body = rule.body().formatted(BRIDGE_CLASS);
            String fallback = rule.fallback() == null ? null : rule.fallback().formatted(BRIDGE_CLASS);
            compiled.add(new Injection(rule.id(), rule.owner(), rule.method(), rule.descriptor(),
                    body, position(rule.kind()), fallback));
        }
        return compiled;
    }

    private static Position position(Rules.Kind kind) {
        return switch (kind) {
            case REPLACE -> Position.BODY;
            case HEAD, WRAP, OBSERVE -> Position.HEAD;
        };
    }

    // ===================== 单类降级（失败只影响一个类的一条规则） =====================

    /** 本类在规则表里声明了哪些规则 id（顺序即注入顺序）。 */
    public List<String> ruleIdsFor(String binaryName) {
        List<String> ids = new ArrayList<>();
        for (Injection injection : injections) {
            if (injection.owner().equals(binaryName)) {
                ids.add(injection.id());
            }
        }
        return ids;
    }

    /** 本类当前被剔除的规则 id（只读快照）。 */
    public Set<String> suppressedFor(String binaryName) {
        return Set.copyOf(SUPPRESSED.getOrDefault(binaryName, Set.of()));
    }

    /** 剔除本类所有 REPLACE 规则（整段替换最容易产出非法字节码）。 */
    public boolean suppressAllBodies(String binaryName) {
        return suppressWhere(binaryName, injection -> injection.position() == Position.BODY);
    }

    /** 只保留本类的 {@code getHealth} HEAD 规则（最小可用补丁）。 */
    public boolean keepOnlyGetHealthHead(String binaryName) {
        return suppressWhere(binaryName, injection -> injection.position() != Position.HEAD
                || !"getHealth".equals(injection.namedMethod()));
    }

    /** 剔除本类的某一条规则（逐条定位用）。 */
    public boolean suppress(String binaryName, String ruleId) {
        Set<String> set = SUPPRESSED.computeIfAbsent(binaryName, name -> ConcurrentHashMap.newKeySet());
        if (!set.add(ruleId)) {
            return false;
        }
        invalidate(binaryName);
        AgentLog.warn("suppressed rule " + ruleId + " on " + binaryName);
        return true;
    }

    /** 剔除本类全部规则：类保持原样，不再尝试改写。 */
    public boolean suppressAll(String binaryName) {
        return suppressWhere(binaryName, injection -> true);
    }

    /** 回滚到某个快照（逐条试探失败时用）。 */
    public void restore(String binaryName, Set<String> snapshot) {
        if (snapshot.isEmpty()) {
            SUPPRESSED.remove(binaryName);
        } else {
            SUPPRESSED.put(binaryName, ConcurrentHashMap.newKeySet());
            SUPPRESSED.get(binaryName).addAll(snapshot);
        }
        invalidate(binaryName);
    }

    private boolean suppressWhere(String binaryName, java.util.function.Predicate<Injection> pick) {
        Set<String> set = SUPPRESSED.computeIfAbsent(binaryName, name -> ConcurrentHashMap.newKeySet());
        boolean changed = false;
        for (Injection injection : injections) {
            if (injection.owner().equals(binaryName) && pick.test(injection)
                    && set.add(injection.id())) {
                changed = true;
            }
        }
        if (changed) {
            invalidate(binaryName);
            AgentLog.warn("suppressed " + set.size() + "/" + ruleIdsFor(binaryName).size()
                    + " rule(s) on " + binaryName + "; other classes are untouched");
        }
        return changed;
    }

    /** 落盘缓存要跟着降级状态走，否则会复用一个“还没剔除这一条”的旧补丁。 */
    private static void invalidate(String binaryName) {
        PATCH_CACHE.remove(binaryName);
    }

    /** 最近一轮里所有类实际应用成功的规则 id。 */
    public static Set<String> appliedKeys() {
        Set<String> all = new LinkedHashSet<>();
        for (Set<String> perClass : APPLIED.values()) {
            all.addAll(perClass);
        }
        return all;
    }

    /** 规则表里一共声明了多少条（运行条目数，含因类未声明方法而自然跳过的）。 */
    public int declaredCount() {
        return injections.size();
    }

    /** 被单类降级剔除的规则 id，用于"声明 / 实际"对账。 */
    public Set<String> suppressedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Set<String> perClass : SUPPRESSED.values()) {
            keys.addAll(perClass);
        }
        return keys;
    }

    /** 目标方法不存在的规则 id（子类没声明该覆写），与"被剔除"严格区分。 */
    public static Set<String> notApplicableKeys() {
        return new LinkedHashSet<>(NO_METHOD);
    }

    /** 某条规则当前是否真的有 agent 注入在跑。 */
    public static boolean hasApplied(String ruleId) {
        for (Set<String> perClass : APPLIED.values()) {
            if (perClass.contains(ruleId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        // The JVM hands over the *internal* name (a/b/C); targets are configured as
        // binary names (a.b.C), so normalize before comparing.
        if (className == null) {
            return null;
        }
        String binaryName = className.replace('/', '.');
        if (!targets.contains(binaryName)) {
            return null;
        }
        try {
            return transformTarget(module, loader, binaryName, classBeingRedefined, classfileBuffer);
        } catch (Throwable t) {
            AgentLog.warn("transform of " + binaryName + " failed, leaving class untouched", t);
            return null;
        }
    }

    private byte[] transformTarget(Module module, ClassLoader loader, String binaryName,
                                   Class<?> classBeingRedefined, byte[] bytes) throws Exception {
        boolean mixinMarker = contains(bytes, MIXIN_MARKER);
        AgentLog.info("transform " + binaryName
                + " | retransform=" + (classBeingRedefined != null)
                + " | loader=" + describe(loader)
                + " | module=" + (module != null ? module.getName() : "-")
                + " | bytes=" + bytes.length
                + " | own-mixin-handlers=" + mixinMarker);

        ClassPool pool = new ClassPool(false);
        if (loader != null) {
            // Only used to resolve super types while compiling the injected snippets.
            pool.insertClassPath(new LoaderClassPath(loader));
        }

        long inputHash = hash(bytes);
        if (mode != Mode.PROBE) {
            CachedPatch cached = PATCH_CACHE.get(binaryName);
            // 缓存键必须同时含"输入字节"和"规则表指纹"：同一份类字节配不同规则表（watchdog 重建的
            // 实例、灰度规则集、测试用自定义表）会产出不同补丁，只按类名+输入哈希会张冠李戴。
            if (cached != null && cached.inputHash() == inputHash && cached.rulesHash() == rulesHash) {
                AgentLog.info("  reusing cached patch for " + binaryName
                        + " (" + bytes.length + " bytes)");
                return cached.patched();
            }
        }

        CtClass ctClass = pool.makeClass(new ByteArrayInputStream(bytes));

        if (mode == Mode.PROBE) {
            reportMethods(ctClass, binaryName);
            ctClass.detach();
            return null;
        }

        if (mode == Mode.BRIDGE && !bridgeVisible(loader)) {
            // The generated code references a mod class; without it the first call would
            // blow up at runtime, so refuse to touch the class at all.
            AgentLog.warn("  bridge " + BRIDGE_CLASS + " not visible from " + describe(loader)
                    + ", skipping injection");
            ctClass.detach();
            return null;
        }

        int injected = 0;
        boolean skipBody = Boolean.getBoolean("star_sword.poc.skipBody");
        Set<String> appliedHere = new LinkedHashSet<>();
        Set<String> suppressed = SUPPRESSED.getOrDefault(binaryName, Set.of());
        for (Injection injection : injections) {
            if (!injection.owner().equals(binaryName)) {
                continue;
            }
            if (suppressed.contains(injection.id())) {
                AgentLog.info("  suppressed by per-class diagnosis: " + injection.id()
                        + " (" + injection.namedMethod() + injection.descriptor() + ") on " + binaryName);
                continue;
            }
            if (skipBody && injection.position() == Position.BODY) {
                AgentLog.info("  skipped BODY injection (star_sword.poc.skipBody) for "
                        + injection.namedMethod());
                continue;
            }
            CtMethod method = findMethod(ctClass, injection);
            if (method == null) {
                NO_METHOD.add(injection.id());
                AgentLog.info("  no " + injection.namedMethod() + injection.descriptor()
                        + " on " + binaryName + ", skipped");
                continue;
            }
            if (injection.position() == Position.BODY) {
                // 整段替换：javassist 重新计算方法体，不受原有注入点位置影响。
                // 不"另存原方法"——retransform 禁止增删方法，实测直接 VerifyError。
                try {
                    method.setBody(injection.body());
                } catch (Throwable replacementFailed) {
                    AgentLog.warn("  body replacement failed for " + method.getName()
                            + ", falling back to head guard: " + replacementFailed);
                    if (injection.fallback() == null) {
                        throw replacementFailed;
                    }
                    method.insertBefore(injection.fallback());
                }
            } else {
                method.insertBefore(injection.body());
            }
            injected++;
            NO_METHOD.remove(injection.id());
            appliedHere.add(injection.id());
            AgentLog.info("  injected into " + method.getName() + injection.descriptor()
                    + " at " + injection.position() + " (named=" + injection.namedMethod() + ")");
        }
        if (injected == 0) {
            APPLIED.remove(binaryName);
            ctClass.detach();
            return null;
        }

        byte[] patched = ctClass.toBytecode();
        ctClass.detach();
        PATCH_CACHE.put(binaryName, new CachedPatch(inputHash, rulesHash, patched));
        APPLIED.put(binaryName, appliedHere);

        if (Boolean.getBoolean("starsword.guard.dump")) {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "ss-agent-dump");
            Files.createDirectories(dir);
            String base = binaryName.replace('.', '_');
            Files.write(dir.resolve(base + ".before.class"), bytes);
            Files.write(dir.resolve(base + ".after.class"), patched);
            AgentLog.info("  dumped before/after to " + dir);
        }

        AgentLog.info("  applied " + injected + " injection(s) | bytes " + bytes.length + " -> " + patched.length);
        return patched;
    }

    /** Resolves a method by any of its candidate runtime names. */
    private CtMethod findMethod(CtClass ctClass, Injection injection) {
        for (String candidate : mapping.candidates(
                injection.owner().replace('.', '/'), injection.namedMethod(), injection.descriptor())) {
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                if (method.getName().equals(candidate)
                        && method.getSignature().equals(injection.descriptor())) {
                    return method;
                }
            }
        }
        return null;
    }

    private void reportMethods(CtClass ctClass, String binaryName) {
        AgentLog.info("  methods=" + ctClass.getDeclaredMethods().length
                + " | bridge-resolvable targets on " + binaryName + ":");
        for (Injection injection : injections) {
            if (!injection.owner().equals(binaryName)) {
                continue;
            }
            List<String> names = mapping.candidates(
                    injection.owner().replace('.', '/'), injection.namedMethod(), injection.descriptor());
            String found = names.stream()
                    .filter(candidate -> hasMethod(ctClass, candidate, injection.descriptor()))
                    .findFirst().orElse("<none>");
            AgentLog.info("    " + injection.namedMethod() + injection.descriptor()
                    + " candidates=" + names + " runtime=" + found);
        }
    }

    private static boolean hasMethod(CtClass ctClass, String name, String descriptor) {
        for (CtMethod method : ctClass.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getSignature().equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the bridge class can be resolved through the target's own class loader —
     * the same loader the injected {@code invokestatic} will resolve against at runtime.
     */
    private static boolean bridgeVisible(ClassLoader loader) {
        try {
            Class.forName(BRIDGE_CLASS, false, loader);
            return true;
        } catch (Throwable t) {
            AgentLog.warn("  bridge lookup failed: " + t);
            return false;
        }
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Not part of the transformer contract; kept for the bootstrap's logging. */
    static boolean supported(Instrumentation instrumentation) {
        return instrumentation.isRetransformClassesSupported();
    }
}
