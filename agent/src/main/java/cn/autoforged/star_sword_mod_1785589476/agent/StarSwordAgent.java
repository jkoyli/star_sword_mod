package cn.autoforged.star_sword_mod_1785589476.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent entry point, reachable both ways:
 * <ul>
 *   <li>{@code premain} – {@code -javaagent:star_sword_agent.jar=...} (standalone tests)</li>
 *   <li>{@code agentmain} – {@code VirtualMachine.loadAgent} from the helper process
 *       (how the mod installs it at runtime)</li>
 * </ul>
 *
 * <p>Options (comma separated, {@code key=value}):
 * <pre>
 *   mode=bridge|probe|guard   default bridge (the real method-layer guards)
 *   targets=a.b.C,d.e.F       default net.minecraft.world.entity.LivingEntity
 *   mapping=C:/path/x.tsrg    offline named->SRG table; falls back to built-ins
 * </pre>
 */
public final class StarSwordAgent {

    private StarSwordAgent() {
    }

    /** Bridge class the generated code calls; also polled for the tamper signal. */
    private static final String BRIDGE_CLASS = "cn.autoforged.star_sword_mod_1785589476.agent.AgentHooks";

    /** Upper bound for a single watchdog round; the remainder of the round is deferred. */
    private static final long ROUND_BUDGET_NANOS = 3_000_000_000L;

    /** Backoff cap for the quiet period. */
    private static final int MAX_INTERVAL_SECONDS = 120;

    public static void premain(String args, Instrumentation instrumentation) {
        init("premain", args, instrumentation);
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        init("agentmain", args, instrumentation);
    }

    /** 默认模式：{@code bridge}（真正注入防御）。只有显式 {@code mode=probe} 才退化成只读诊断。 */
    static final String DEFAULT_MODE = "bridge";

    private static synchronized void init(String source, String args, Instrumentation instrumentation) {
        Map<String, String> options = parse(args);
        GuardTransformer.Mode mode;
        try {
            mode = GuardTransformer.Mode.valueOf(
                    options.getOrDefault("mode", DEFAULT_MODE).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 写错模式名时也走 bridge：默认必须是“防”，而不是静默不防。
            AgentLog.warn("unknown mode '" + options.get("mode") + "', falling back to " + DEFAULT_MODE);
            mode = GuardTransformer.Mode.BRIDGE;
        }
        Set<String> targets = new LinkedHashSet<>();
        String requestedTargets = options.get("targets");
        if (requestedTargets == null || requestedTargets.isBlank()) {
            targets.addAll(GuardTransformer.defaultTargets());
        } else {
            for (String target : requestedTargets.split(",")) {
                String trimmed = target.trim();
                if (!trimmed.isEmpty()) {
                    targets.add(trimmed);
                }
            }
        }

        AgentLog.info("loaded via " + source + " | mode=" + mode + " | targets=" + targets
                // Kept as a log line on purpose: HotSpot has supported retransformation
                // since Java 6, so this is diagnostics, not a meaningful POC gate.
                + " | retransformSupported=" + instrumentation.isRetransformClassesSupported()
                + " | redefineSupported=" + instrumentation.isRedefineClassesSupported());

        MappingTable mapping;
        String mappingPath = options.get("mapping");
        String mappingSource;
        if (mappingPath != null && !mappingPath.isBlank()) {
            mapping = MappingTable.load(new File(mappingPath));
            mappingSource = "file(" + mappingPath + ")";
        } else {
            mapping = MappingTable.fallback();
            mappingSource = "builtin-fallback";
        }
        // 这一行必须显式打出来：生产环境没有 tsrg，走的是内置表；内置表一旦与真实 SRG 漂移，
        // 规则会在对账里表现为"方法不存在"，只看对账分不清"类里真没有"还是"名字写错了"。
        AgentLog.infoAlways("mapping=" + mappingSource + " classes=" + mapping.size());

        GuardTransformer transformer = new GuardTransformer(mode, targets, mapping);
        instrumentation.addTransformer(transformer, true);

        int retransformed = 0;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!targets.contains(loaded.getName())) {
                continue;
            }
            if (!instrumentation.isModifiableClass(loaded)) {
                AgentLog.warn("class " + loaded.getName() + " is not modifiable, skipped");
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
                retransformed++;
                AgentLog.info("retransformed already-loaded " + loaded.getName()
                        + " (loader=" + loaded.getClassLoader() + ")");
            } catch (Throwable t) {
                AgentLog.warn("retransform failed for " + loaded.getName(), t);
                // 单类降级：失败只剔除这一个类的若干条注入，其它类与 watchdog 后续轮次不受影响。
                if (isolateFailure(loaded, instrumentation, transformer)) {
                    retransformed++;
                }
            }
        }
        if (retransformed == 0) {
            AgentLog.info("no target class loaded yet; the transformer will act when it is defined");
        }
        AgentLog.info("ready (retransformed=" + retransformed + ")");
        // 通知 mod 侧：方法层已就绪，避免 AgentStatus 在未附加时误报降级。
        bridgeCall(instrumentation, targets, "reportAttached", new Class<?>[0], new Object[0]);
        reportAppliedInjections(instrumentation, targets, transformer);
        // 方法层就绪后再打一行对账横幅，`/starsword agent status` 展示的是同一份数据。

        int watchdogSeconds = parseInt(options.getOrDefault("watchdog", "15"), 15);
        if (watchdogSeconds > 0) {
            startWatchdog(instrumentation, transformer, targets, watchdogSeconds, mode, mapping);
        } else {
            AgentLog.info("watchdog disabled");
        }
    }

    /**
     * 单类降级：一个类失败不影响其它类，也不改写整张注入表。
     *
     * <p>顺序由细到粗——先逐条剔除定位（找到那一条坏的，其余全部保住），再退到“去掉 BODY”，
     * 再退到“只留 {@code getHealth} HEAD”，最后整体放弃、让这个类保持原样。粗粒度放在后面：
     * 它们一次性丢掉整个类的多数注入，只有在逐条定位也救不回来时才值得付这个代价。
     *
     * @return 该类的降级重试是否最终成功（成功即“这个类仍被改写”）
     */
    private static boolean isolateFailure(Class<?> loaded, Instrumentation instrumentation,
                                          GuardTransformer transformer) {
        String name = loaded.getName();
        int configured = transformer.ruleIdsFor(name).size();
        if (configured == 0) {
            AgentLog.warn("  no rules configured for " + name + ", nothing to degrade");
            return false;
        }

        // 1) 逐条试探：把问题定位到单条规则，其余保住（对应旧实现“一条坏注入拖垮整类”）。
        for (String ruleId : transformer.ruleIdsFor(name)) {
            if (transformer.suppressedFor(name).contains(ruleId)) {
                continue;
            }
            Set<String> snapshot = new HashSet<>(transformer.suppressedFor(name));
            if (transformer.suppress(name, ruleId) && retransform(loaded, instrumentation)) {
                AgentLog.infoAlways("degraded " + name + ": dropped rule " + ruleId
                        + ", remaining=" + remaining(transformer, name) + "/" + configured);
                return true;
            }
            transformer.restore(name, snapshot);
        }

        // 2) 粗粒度兜底：BODY 最容易在带分支 / try-catch 的方法上产出非法字节码。
        if (transformer.suppressAllBodies(name) && retransform(loaded, instrumentation)) {
            AgentLog.infoAlways("degraded " + name + ": dropped BODY injections, remaining="
                    + remaining(transformer, name) + "/" + configured);
            return true;
        }
        // 3) 最小可用补丁。
        if (transformer.keepOnlyGetHealthHead(name) && retransform(loaded, instrumentation)) {
            AgentLog.infoAlways("degraded " + name + ": kept getHealth HEAD only, remaining="
                    + remaining(transformer, name) + "/" + configured);
            return true;
        }
        // 4) 放弃：类保持原样，比装上一半更安全。
        transformer.suppressAll(name);
        AgentLog.warn("every injection failed on " + name
                + "; the class is left untouched and will not be retried");
        return false;
    }

    private static int remaining(GuardTransformer transformer, String name) {
        return transformer.ruleIdsFor(name).size() - transformer.suppressedFor(name).size();
    }

    /** 只重新变换这一个类；失败不抛，交由调用方继续降级。 */
    private static boolean retransform(Class<?> loaded, Instrumentation instrumentation) {
        try {
            instrumentation.retransformClasses(loaded);
            return true;
        } catch (Throwable t) {
            AgentLog.warn("  retry failed for " + loaded.getName() + ": " + t);
            return false;
        }
    }

    /**
     * 把“哪些入口真的被 agent 接管”同步给 mod 侧。
     *
     * <p>Mixin 侧的兜底（例如 {@code setHealth} 入参归一）靠它让路：只有当 agent 的注入确实
     * 生效时才跳过自己，否则两次过滤会让写入计数翻倍。
     */
    private static void reportAppliedInjections(Instrumentation instrumentation, Set<String> targets,
                                                GuardTransformer transformer) {
        Set<String> applied = GuardTransformer.appliedKeys();
        Set<String> suppressed = transformer.suppressedKeys();
        Set<String> notApplicable = GuardTransformer.notApplicableKeys();
        bridgeCall(instrumentation, targets, "reportInjectionState",
                new Class<?>[]{String[].class, String[].class, String[].class, int.class},
                new Object[]{applied.toArray(new String[0]),
                        suppressed.toArray(new String[0]),
                        notApplicable.toArray(new String[0]),
                        transformer.declaredCount()});
    }

    /**
     * Counter-measure against another agent re-defining our classes after us.
     *
     * <p><b>Why a behavioural probe instead of a byte fingerprint.</b> JVMTI always hands a
     * retransform the <em>initial</em> define-time bytes, so after somebody else redefined a
     * class our transformer still sees the same input — a content hash cannot tell "unchanged"
     * from "someone overwrote us". The mod-side bridge therefore exposes
     * {@code AgentHooks.hookCallCount()}; if that counter stops advancing between rounds while
     * it previously advanced, our injected call is demonstrably not running any more, which is
     * the tamper signal. That only costs a reflective read, no redefinition.
     *
     * <p><b>Cost control.</b> A round is still a full re-application (retransform is the only
     * way to restore), but it is bounded twice: a per-round time budget aborts the remainder of
     * the round, and a quiet period doubles the interval (up to {@link #MAX_INTERVAL_SECONDS})
     * as long as no tamper is detected. A tamper, or a skipped round, resets the interval to the
     * configured base.
     */
    private static void startWatchdog(Instrumentation instrumentation, GuardTransformer transformer,
                                      Set<String> targets, int baseIntervalSeconds,
                                      GuardTransformer.Mode mode, MappingTable mapping) {
        Thread thread = new Thread(() -> {
            GuardTransformer active = transformer;
            int interval = baseIntervalSeconds;
            long previousHookCalls = -1L;
            int stalledRounds = 0;
            while (true) {
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                long roundStart = System.nanoTime();

                long hookCalls = readHookCounter(instrumentation, targets);
                boolean advancing = hookCalls < 0 || previousHookCalls < 0 || hookCalls > previousHookCalls;
                stalledRounds = advancing ? 0 : stalledRounds + 1;
                boolean tampered = stalledRounds >= 2;
                if (tampered) {
                    AgentLog.infoAlways("[tamper] hook counter stalled at " + hookCalls
                            + " for " + stalledRounds + " round(s) -> re-applying");
                }
                previousHookCalls = hookCalls;
                if (tampered) {
                    bridgeCall(instrumentation, targets, "reportTamper",
                            new Class<?>[]{long.class}, new Object[]{(long) stalledRounds});
                }

                int applied = 0;
                int skipped = 0;
                AgentLog.setQuiet(true);
                try {
                    if (tampered) {
                        // JVMTI runs retransform-capable transformers in registration order and
                        // the last one wins. An attacker that registered after us therefore
                        // overwrites our injection inside the very same retransform pass.
                        // Re-adding the *same* instance turned out not to move it to the end in
                        // practice, so a fresh instance is registered: even if removing the old
                        // one fails, the new one is guaranteed to sit at the tail of the chain.
                        boolean removed = instrumentation.removeTransformer(active);
                        active = new GuardTransformer(mode, targets, mapping);
                        instrumentation.addTransformer(active, true);
                        AgentLog.infoAlways("[tamper] transformer re-registered to run last (removed="
                                + removed + ")");
                    }
                    for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                        if (!targets.contains(loaded.getName())
                                || !instrumentation.isModifiableClass(loaded)) {
                            continue;
                        }
                        if (System.nanoTime() - roundStart > ROUND_BUDGET_NANOS) {
                            skipped++;
                            continue;
                        }
                        try {
                            instrumentation.retransformClasses(loaded);
                            applied++;
                        } catch (Throwable ignored) {
                            // a busy or unmodifiable class is simply retried next round
                        }
                    }
                } finally {
                    AgentLog.setQuiet(false);
                }

                long tookMillis = (System.nanoTime() - roundStart) / 1_000_000L;
                // Only warn when the budget actually cut the round short: a full six-class round
                // naturally costs ~0.5s, and warning on that alone is pure noise.
                if (skipped > 0) {
                    AgentLog.warn("watchdog round hit its budget: took=" + tookMillis
                            + "ms skipped=" + skipped);
                }
                AgentLog.infoAlways("watchdog round: applied=" + applied + " skipped=" + skipped
                        + " took=" + tookMillis + "ms hookCalls=" + hookCalls
                        + " interval=" + interval + "s" + (tampered ? " (tamper)" : ""));
                bridgeCall(instrumentation, targets, "reportReplay",
                        new Class<?>[]{int.class, long.class},
                        new Object[]{applied, tookMillis});
                // 每轮都重新对账一次：降级/重放之后，mod 侧的“哪些入口由 agent 接管”必须跟着变。
                reportAppliedInjections(instrumentation, targets, active);

                interval = (tampered || skipped > 0)
                        ? baseIntervalSeconds
                        : Math.min(interval * 2, MAX_INTERVAL_SECONDS);
            }
        }, "ss-agent-watchdog");
        thread.setDaemon(true);
        thread.start();
        AgentLog.infoAlways("watchdog started (base interval=" + baseIntervalSeconds + "s, max "
                + MAX_INTERVAL_SECONDS + "s, round budget "
                + (ROUND_BUDGET_NANOS / 1_000_000L) + "ms)");
    }

    /** Reflective read of {@code AgentHooks.hookCallCount()} through a target class's loader. */
    private static long readHookCounter(Instrumentation instrumentation, Set<String> targets) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!targets.contains(loaded.getName())) {
                continue;
            }
            try {
                Class<?> hooks = Class.forName(BRIDGE_CLASS, false, loaded.getClassLoader());
                Object value = hooks.getMethod("hookCallCount").invoke(null);
                return value instanceof Long count ? count : -1L;
            } catch (Throwable ignored) {
                // fall through and try another loader
            }
        }
        return -1L;
    }

    /**
     * Best-effort reflective call into the mod-side bridge, so the agent can report its own
     * state (tamper / replay) for {@code /starsword agent status} and the in-game notice.
     */
    private static void bridgeCall(Instrumentation instrumentation, Set<String> targets,
                                   String method, Class<?>[] types, Object[] args) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!targets.contains(loaded.getName())) {
                continue;
            }
            try {
                Class<?> hooks = Class.forName(BRIDGE_CLASS, false, loaded.getClassLoader());
                hooks.getMethod(method, types).invoke(null, args);
                return;
            } catch (Throwable ignored) {
                // the mod may not be initialised yet; reporting is best-effort
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
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
}
