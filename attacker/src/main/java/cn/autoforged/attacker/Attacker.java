package cn.autoforged.attacker;

import java.lang.reflect.Method;

/**
 * Helper process entry point of the TEST HARNESS:
 * {@code java -jar attacker.jar <pid> <agentJar> [options]}.
 *
 * <p>Since JDK 9 a JVM may not attach to <em>itself</em> unless it was started
 * with {@code -Djdk.attach.allowAttachSelf=true}; a separate process has no such
 * restriction. Modelled on the production {@code Attacher}, but the attach API is
 * used through reflection so this jar still loads (and can print a clear error)
 * on runtimes that ship without the {@code jdk.attach} module.
 *
 * <p>Every line it prints is prefixed {@code [attacker]} so harness output is
 * easy to separate from our agent's {@code [ss-agent]} lines.
 */
public final class Attacker {

    private Attacker() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: java -jar attacker.jar <pid> <agentJar> [options]");
            System.err.println("  options: target=a.b.C,intercept=getHealth,descriptor=()F,"
                    + "value=1.0,reapplySeconds=20,mode=replace|nop");
            System.exit(2);
        }
        String pid = args[0];
        String agentJar = args[1];
        // Everything after the jar is the agent option string; join the remaining
        // argv entries so an unquoted option list still works on shells that split
        // on spaces.
        String options = args.length > 2 ? String.join(",", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";

        Object vm = null;
        try {
            Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
            Method attach = vmType.getMethod("attach", String.class);
            vm = attach.invoke(null, pid);
            System.out.println("[attacker] attached to pid=" + pid);

            Method loadAgent = vmType.getMethod("loadAgent", String.class, String.class);
            loadAgent.invoke(vm, agentJar, options);
            System.out.println("[attacker] agent loaded: " + agentJar
                    + (options.isEmpty() ? "" : " options=" + options));
        } catch (ClassNotFoundException e) {
            System.err.println("[attacker] ERROR: jdk.attach is missing in this runtime: " + e);
            System.err.println("[attacker] run the attacker with a full JDK (not a JRE), "
                    + "e.g. the same <JAVA_HOME>/bin/java that launched the game");
            System.exit(3);
        } catch (Throwable t) {
            // InvocationTargetException would hide the interesting cause; unwrap one level.
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.err.println("[attacker] ERROR: attach FAILED pid=" + pid
                    + " agent=" + agentJar + ": " + cause);
            cause.printStackTrace(System.err);
            System.err.println("[attacker] hint: check the pid, that it is a JVM, that the "
                    + "launcher allows attach, and that attacker.jar is the built shaded jar");
            System.exit(1);
        } finally {
            if (vm != null) {
                try {
                    vm.getClass().getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                    // best effort, the agent stays loaded in the target JVM
                }
            }
        }
    }
}
