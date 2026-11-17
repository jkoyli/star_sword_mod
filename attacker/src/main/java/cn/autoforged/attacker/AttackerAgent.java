package cn.autoforged.attacker;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The hostile agent of the TEST HARNESS.
 *
 * <p>It is installed <em>after</em> our agent (which has already injected its
 * {@code getHealth} guard), registers its own retransform-capable transformer and
 * then keeps re-applying. During a retransformation the JVM hands the initial
 * class file bytes to the first registered transformer and chains each output
 * into the next, so our attacker — registered last — sees the guard-injected
 * bytes and replaces the whole method body. That is how an opposing mod would
 * "win" against us and exactly what our watchdog has to notice and revert.
 *
 * <p>Options (comma separated {@code key=value}):
 * <pre>
 *   target=a.b.C        default net.minecraft.world.entity.LivingEntity
 *   intercept=getHealth default getHealth
 *   descriptor=()F      default ()F
 *   value=1.0           default 1.0 (float literal returned by the rebuilt body)
 *   reapplySeconds=20   default 20 (0 disables the re-apply thread)
 *   mode=replace|nop    default replace
 * </pre>
 *
 * <p>{@code replace} rebuilds the method body to {@code return <value>F;};
 * {@code nop} returns the incoming classfile bytes unchanged — a harmless
 * redefinition that still replaces the currently installed class version (and
 * deoptimises its methods) without changing any method body.
 *
 * <p>Chain order matters for {@code nop}: because our guard agent registers its
 * transformer first, the bytes this transformer receives during a retransform
 * already contain the guard injection, so {@code nop} keeps that injection. Use
 * {@code replace} when the test must actually overwrite the guard.
 *
 * <p>Nothing here ever throws out of {@code transform}: a transformer that
 * throws aborts class loading of the whole game, which would make the harness
 * useless for observing the watchdog.
 */
public final class AttackerAgent {

    private static final String TAG = "[attacker] ";

    private static final String DEFAULT_TARGET = "net.minecraft.world.entity.LivingEntity";
    private static final String DEFAULT_INTERCEPT = "getHealth";
    private static final String DEFAULT_DESCRIPTOR = "()F";
    private static final float DEFAULT_VALUE = 1.0F;
    private static final int DEFAULT_REAPPLY_SECONDS = 20;

    private AttackerAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        init("premain", args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        init("agentmain", args, instrumentation);
    }

    private static synchronized void init(String source, String args, Instrumentation instrumentation) {
        Map<String, String> options = parse(args);
        String target = valueOrDefault(options, "target", DEFAULT_TARGET);
        String intercept = valueOrDefault(options, "intercept", DEFAULT_INTERCEPT);
        String descriptor = valueOrDefault(options, "descriptor", DEFAULT_DESCRIPTOR);
        float value = parseFloat(options.get("value"), DEFAULT_VALUE);
        int reapplySeconds = parseInt(options.get("reapplySeconds"), DEFAULT_REAPPLY_SECONDS);

        String requestedMode = valueOrDefault(options, "mode", "replace").toLowerCase(Locale.ROOT);
        boolean nop;
        if ("nop".equals(requestedMode)) {
            nop = true;
        } else if ("replace".equals(requestedMode)) {
            nop = false;
        } else {
            log("unknown mode=" + requestedMode + ", falling back to replace");
            nop = false;
        }

        log("loaded via " + source
                + " | target=" + target
                + " | intercept=" + intercept + descriptor
                + " | value=" + Float.toString(value) + "F"
                + " | mode=" + (nop ? "nop" : "replace")
                + " | reapplySeconds=" + reapplySeconds
                + " | retransformSupported=" + instrumentation.isRetransformClassesSupported());

        Patcher patcher = new Patcher(target, intercept, descriptor, value, nop);
        instrumentation.addTransformer(patcher, /* canRetransform = */ true);

        int applied = apply("initial", instrumentation, patcher);
        if (applied == 0) {
            // Not fatal: the transformer will fire when the class is defined later.
            log("target not loaded yet; the transformer will act on first definition of " + target);
        }

        if (reapplySeconds > 0) {
            startReapplyLoop(instrumentation, patcher, reapplySeconds);
        } else {
            log("re-apply thread disabled (reapplySeconds=" + reapplySeconds + ")");
        }
        log("ready");
    }

    /**
     * One application round: retransform every already-loaded class named
     * {@code target}, which re-runs the transformer chain (our guard, then us).
     */
    private static int apply(String reason, Instrumentation instrumentation, Patcher patcher) {
        int applied = 0;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!patcher.target.equals(loaded.getName())) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loaded)) {
                log("class " + loaded.getName() + " is not modifiable, skipped");
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
                applied++;
                log("applied to " + loaded.getName());
            } catch (Throwable t) {
                log("retransform FAILED for " + loaded.getName() + " (" + reason + "): " + t);
            }
        }
        return applied;
    }

    /**
     * Keeps the hijack alive: every round re-applies on top of whatever the other
     * agent restored in the meantime, so a passing watchdog test proves the
     * watchdog can out-race a persistent attacker.
     */
    private static void startReapplyLoop(Instrumentation instrumentation, Patcher patcher, int intervalSeconds) {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                int applied = apply("reapply", instrumentation, patcher);
                if (applied == 0) {
                    log("re-apply round found no loaded class named " + patcher.target);
                }
            }
        }, "attacker-reapply");
        thread.setDaemon(true);
        thread.start();
        log("re-apply thread started (interval=" + intervalSeconds + "s)");
    }

    private static void log(String message) {
        // Always stdout, always tagged: the harness parses this from the game console.
        System.out.println(TAG + message);
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    private static String valueOrDefault(Map<String, String> options, String key, String fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(value.trim());
            if (!Float.isFinite(parsed)) {
                log("value=" + value + " is not finite, using " + fallback);
                return fallback;
            }
            return parsed;
        } catch (Throwable t) {
            log("value=" + value + " is not a float, using " + fallback);
            return fallback;
        }
    }

    private static Map<String, String> parse(String args) {
        Map<String, String> options = new LinkedHashMap<>();
        if (args == null || args.isBlank()) {
            return options;
        }
        for (String entry : args.split(",")) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                continue;
            }
            options.put(entry.substring(0, split).trim(), entry.substring(split + 1).trim());
        }
        return options;
    }

    // ------------------------------------------------------------------
    // Transformer
    // ------------------------------------------------------------------

    /**
     * Rewrites the configured method of the configured class. Only that one class
     * is touched; everything else returns {@code null} (no change).
     */
    private static final class Patcher implements ClassFileTransformer {

        /** Binary name, e.g. {@code net.minecraft.world.entity.LivingEntity}. */
        private final String target;
        private final String intercept;
        private final String descriptor;
        private final float value;
        private final boolean nop;

        /** Body detail is reported once per class; each later round logs only "applied to". */
        private volatile boolean detailLogged;

        private Patcher(String target, String intercept, String descriptor, float value, boolean nop) {
            this.target = target;
            this.intercept = intercept;
            this.descriptor = descriptor;
            this.value = value;
            this.nop = nop;
        }

        @Override
        public byte[] transform(Module module, ClassLoader loader, String className,
                                Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            // The JVM hands over the *internal* name (a/b/C); options use binary
            // names (a.b.C), so normalise before comparing.
            if (className == null || classfileBuffer == null) {
                return null;
            }
            String binaryName = className.replace('/', '.');
            if (!binaryName.equals(target)) {
                return null;
            }
            try {
                if (nop) {
                    // Deliberate no-op rewrite: returning the bytes untouched still
                    // consumes this retransformation round (a fresh class version),
                    // which is all the harness needs to observe a redefinition.
                    return classfileBuffer;
                }
                byte[] patched = replaceBody(loader, binaryName, classfileBuffer);
                return patched; // may be null when nothing was replaced
            } catch (Throwable t) {
                log("transform of " + binaryName + " failed, leaving class untouched: " + t);
                return null;
            }
        }

        private byte[] replaceBody(ClassLoader loader, String binaryName, byte[] bytes) throws Exception {
            ClassPool pool = new ClassPool(false);
            if (loader != null) {
                // Only used to resolve super types while compiling the rebuilt body.
                pool.insertClassPath(new LoaderClassPath(loader));
            }
            // Build from the bytes handed to us, NOT from ClassPool.get(name):
            // in a ModLauncher environment the loader answers that lookup with the
            // untransformed (pre-Mixin, pre-guard) class file, which silently drops
            // the methods we are looking at and makes retransformation fail with
            // "attempted to delete a method".
            CtClass ctClass = pool.makeClass(new ByteArrayInputStream(bytes));
            try {
                CtMethod method = findMethod(ctClass, intercept, descriptor);
                if (method == null) {
                    log(binaryName + " has no method " + intercept + descriptor
                            + " (declared methods: " + ctClass.getDeclaredMethods().length + ")");
                    log("hint: wrong intercept/descriptor (SRG vs Mojang name), or the "
                            + "method is inherited from a superclass");
                    return null;
                }
                String body = "{ return " + Float.toString(value) + "F; }";
                method.setBody(body);
                byte[] patched = ctClass.toBytecode();
                if (!detailLogged) {
                    detailLogged = true;
                    log("rebuilt " + binaryName + "." + intercept + descriptor
                            + " | body=" + body + " | bytes " + bytes.length + " -> " + patched.length);
                }
                return patched;
            } finally {
                ctClass.detach();
            }
        }

        private static CtMethod findMethod(CtClass ctClass, String name, String descriptor) {
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getSignature().equals(descriptor)) {
                    return method;
                }
            }
            return null;
        }
    }
}
