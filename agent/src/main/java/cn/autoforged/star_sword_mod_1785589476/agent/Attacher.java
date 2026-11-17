package cn.autoforged.star_sword_mod_1785589476.agent;

import java.lang.reflect.Method;

/**
 * Helper process entry point: {@code java -jar star_sword_agent.jar <pid> <agentJar> [options]}.
 *
 * <p>Since JDK 9 a JVM may not attach to <em>itself</em> unless it was started with
 * {@code -Djdk.attach.allowAttachSelf=true}, and a mod cannot add JVM arguments to
 * the launcher that started the game. Attaching from a separate process has no such
 * restriction, which is exactly how ByteBuddy's {@code ByteBuddyAgent.install()}
 * solves the same problem.
 *
 * <p>The attach API is used through reflection so that this jar still loads (and can
 * report a clear error) on runtimes that ship without the {@code jdk.attach} module.
 */
public final class Attacher {

    /** 与 {@code ModLog.PROP_ACTIVE} / {@code AgentLog.ACTIVE_PROP} 同一个属性名。 */
    private static final String ACTIVE_PROP = "star_sword.log.active";

    /** 辅助进程同样只在开发环节输出：开关由 mod 通过 JVM 参数传进来。 */
    private static final boolean LOG = resolveLog();

    private Attacher() {
    }

    private static boolean resolveLog() {
        String value = System.getProperty(ACTIVE_PROP);
        if (value == null || value.isBlank()) {
            return true;
        }
        return !("false".equalsIgnoreCase(value) || "0".equals(value)
                || "no".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value));
    }

    private static void out(String message) {
        if (LOG) {
            System.out.println(message);
        }
    }

    private static void err(String message) {
        if (LOG) {
            System.err.println(message);
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            err("usage: java -jar star_sword_agent.jar <pid> <agentJar> [options]");
            System.exit(2);
        }
        String pid = args[0];
        String agentJar = args[1];
        String options = args.length > 2 ? args[2] : "";

        Object vm = null;
        try {
            Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
            Method attach = vmType.getMethod("attach", String.class);
            vm = attach.invoke(null, pid);

            Method loadAgent = vmType.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJar, options);
            out("[ss-agent] attach OK pid=" + pid + " agent=" + agentJar);
        } catch (ClassNotFoundException e) {
            err("[ss-agent] jdk.attach is missing in this runtime: " + e);
            System.exit(3);
        } catch (Throwable t) {
            err("[ss-agent] attach FAILED pid=" + pid + ": " + t);
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (LOG) {
                cause.printStackTrace(System.err);
            }
            System.exit(1);
        } finally {
            if (vm != null) {
                try {
                    vm.getClass().getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }
}
