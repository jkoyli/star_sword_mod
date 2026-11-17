package cn.autoforged.star_sword_mod_1785589476.agent;

import cn.autoforged.star_sword_mod_1785589476.helper.ModLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Installs the self-attaching agent.
 *
 * <p><b>Enabled by default, and in {@code bridge} mode</b> — the mode that actually injects the
 * method-layer guards. Opt out with {@code -Dstar_sword.poc.agent=0} ({@code -PssAgent=0} on the
 * Gradle run tasks); use {@code -Dstar_sword.poc.mode=probe} if you only want the diagnostic
 * report without any bytecode change. Installing extracts the embedded agent jar, starts a
 * short-lived helper process and lets that helper {@code loadAgent} into this JVM. Self-attachment
 * from inside the JVM is refused unless {@code -Djdk.attach.allowAttachSelf=true} was passed at
 * launch, which a mod cannot do, hence the helper process.
 *
 * <p>Optional system properties:
 * <ul>
 *   <li>{@code star_sword.poc.mode} – {@code bridge} (default), {@code probe} or {@code guard}</li>
 *   <li>{@code star_sword.poc.targets} – comma separated class names</li>
 *   <li>{@code star_sword.poc.mapping} – path to a named-to-SRG tsrg file</li>
 *   <li>{@code star_sword.poc.agent.jar} – use this jar instead of the embedded one</li>
 * </ul>
 *
 * <p>Everything is best effort: a failure is logged and the game continues.
 */
public final class PocAgentBootstrap {

    private static final String ENABLE_PROPERTY = "star_sword.poc.agent";
    private static final String MODE_PROPERTY = "star_sword.poc.mode";
    private static final String AGENT_JAR_PROPERTY = "star_sword.poc.agent.jar";
    private static final String EMBEDDED_AGENT = "/agent/star_sword_agent.jar";
    private static final String ATTACHER_CLASS = "cn.autoforged.star_sword_mod_1785589476.agent.Attacher";

    /**
     * Agent 默认模式：{@code bridge} = 真正注入方法层防御。
     *
     * <p>只有显式指定 {@code -Dstar_sword.poc.mode=probe} 才会退化成“只看不改”的诊断模式；
     * 不指定时绝不能是 probe——那样 agent 一辈子在跑却不防任何东西。
     */
    static final String DEFAULT_MODE = "bridge";

    private PocAgentBootstrap() {
    }

    /**
     * Installs the agent. <b>Enabled by default</b> for shipped builds; set
     * {@code -Dstar_sword.poc.agent=0} to opt out (e.g. a server admin who does not want a
     * helper process, or an environment where {@code jdk.attach} is unavailable).
     *
     * <p>A failure is never silent: it is logged, and {@code /starsword agent status} then
     * reports the method layer as not attached instead of pretending to be active.
     */
    public static void installIfEnabled() {
        String flag = System.getProperty(ENABLE_PROPERTY, "1");
        if (!truthy(flag)) {
            log("agent disabled by " + ENABLE_PROPERTY + "=" + flag
                    + " (method-layer defence off, data-layer defence still on)");
            return;
        }
        try {
            install();
        } catch (Throwable t) {
            // 异常栈整段进日志（log4j + 独立文件），不直接写 System.out。
            ModLog.log(ModLog.AGENT, "agent 安装失败，方法层防御不可用", t);
        }
    }

    private static boolean truthy(String value) {
        return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    private static void install() throws Exception {
        File agentJar = resolveAgentJar();
        String java = javaBinary();
        long pid = ProcessHandle.current().pid();

        StringBuilder options = new StringBuilder();
        options.append("mode=").append(System.getProperty(MODE_PROPERTY, DEFAULT_MODE));
        String targets = System.getProperty("star_sword.poc.targets");
        if (targets != null && !targets.isBlank()) {
            options.append(",targets=").append(targets);
        }
        String mapping = System.getProperty("star_sword.poc.mapping");
        if (mapping == null || mapping.isBlank()) {
            mapping = defaultDevMapping();
        }
        if (mapping != null && !mapping.isBlank()) {
            options.append(",mapping=").append(mapping);
        }
        // 反制 watchdog：定期重新应用注入，防止被别的 agent 再 retransform 覆盖；0 关闭。
        options.append(",watchdog=").append(System.getProperty("star_sword.poc.watchdog", "15"));

        List<String> command = new ArrayList<>();
        command.add(java);
        // 把“开发/发布”的最终决定传给辅助进程：它的 stdout 会直接进游戏控制台，
        // 发布版必须与 mod 日志一起静默。
        command.add("-D" + ModLog.PROP_ACTIVE + "=" + ModLog.ENABLED);
        command.add("-cp");
        command.add(agentJar.getAbsolutePath());
        command.add(ATTACHER_CLASS);
        command.add(Long.toString(pid));
        command.add(agentJar.getAbsolutePath());
        command.add(options.toString());

        log("pid=" + pid + " agent=" + agentJar.getName() + " options=" + options);
        Process helper = new ProcessBuilder(command).inheritIO().start();
        if (!helper.waitFor(30, TimeUnit.SECONDS)) {
            helper.destroyForcibly();
            log("attach helper timed out");
            return;
        }
        log("attach helper exit=" + helper.exitValue());
    }

    private static File resolveAgentJar() throws IOException {
        String override = System.getProperty(AGENT_JAR_PROPERTY);
        if (override != null && !override.isBlank()) {
            File explicit = new File(override);
            if (explicit.isFile()) {
                return explicit;
            }
            throw new IOException(AGENT_JAR_PROPERTY + " points to a missing file: " + override);
        }
        try (InputStream in = PocAgentBootstrap.class.getResourceAsStream(EMBEDDED_AGENT)) {
            if (in == null) {
                throw new IOException("agent jar is not embedded at " + EMBEDDED_AGENT
                        + " (build it first, or set " + AGENT_JAR_PROPERTY + ")");
            }
            Path target = Files.createTempFile("star_sword_agent", ".jar");
            target.toFile().deleteOnExit();
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toFile();
        }
    }

    private static String javaBinary() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    /**
     * In a development run the working directory is the project root, where the build
     * already produced the named-to-SRG table; production clients fall back to the
     * agent's built-in SRG names.
     */
    private static String defaultDevMapping() {
        File mapping = new File("build/moddev/artifacts/namedToIntermediate.tsrg");
        return mapping.isFile() ? mapping.getAbsolutePath() : null;
    }

    private static void log(String message) {
        ModLog.log(ModLog.AGENT, message);
    }
}
