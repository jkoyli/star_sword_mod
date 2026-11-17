package cn.autoforged.star_sword_mod_1785589476.agent;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Minimal logger for the agent. The agent lives on the system class loader and
 * must not touch game logging frameworks (they are not visible from there).
 *
 * <p><b>输出到两个地方</b>：
 * <ul>
 *   <li>控制台（{@code System.out}）——受 {@value #ACTIVE_PROP} 开关控制，发布版默认静默；</li>
 *   <li>独立文件 {@code logs/starsword-agent.log}——<b>始终写</b>，不依赖 mod 的日志配置。
 *       原因：排查“注入到底有没有生效”必须看 agent 自己的记录，而 mod 的日志开关一关，
 *       agent 侧就完全不可见（实测踩过一次，整轮排查缺了这一块）。</li>
 * </ul>
 *
 * <p>{@link #setQuiet(boolean)} 只影响控制台：它压掉 watchdog 每轮重放时的逐类输出，
 * 文件里仍保留完整记录。
 */
public final class AgentLog {

    /** 与 {@code ModLog.PROP_ACTIVE} 同一个属性名（agent 不能依赖 mod 的类）。 */
    static final String ACTIVE_PROP = "star_sword.log.active";

    private static final String PREFIX = "[ss-agent] ";

    /** 独立日志文件：{cwd}/logs/starsword-agent.log。 */
    private static final PrintWriter FILE = openFile();

    private static final boolean ENABLED = resolveEnabled();

    private static volatile boolean quiet;

    private AgentLog() {
    }

    private static PrintWriter openFile() {
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);
            PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                    dir.resolve("starsword-agent.log"),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND), true);
            writer.println("===== StarSword agent log session =====");
            return writer;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean resolveEnabled() {
        String value;
        try {
            value = System.getProperty(ACTIVE_PROP);
        } catch (Throwable t) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return true;
        }
        return !("false".equalsIgnoreCase(value) || "0".equals(value)
                || "no".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value));
    }

    /** 开关是否打开（只影响控制台；文件始终写）。 */
    public static boolean enabled() {
        return ENABLED;
    }

    /** Suppress {@link #info(String)} console output (used around watchdog rounds). */
    public static void setQuiet(boolean value) {
        quiet = value;
    }

    public static void info(String message) {
        if (quiet && ENABLED) {
            return;
        }
        emit(PREFIX + message);
    }

    /** Logs even while quiet. */
    public static void infoAlways(String message) {
        emit(PREFIX + message);
    }

    public static void warn(String message) {
        emit(PREFIX + "WARN " + message);
    }

    public static void warn(String message, Throwable error) {
        emit(PREFIX + "WARN " + message + ": " + error);
        if (FILE != null) {
            try {
                error.printStackTrace(FILE);
                FILE.flush();
            } catch (Throwable ignored) {
                // 文件写失败不影响主流程
            }
        }
        if (ENABLED) {
            error.printStackTrace(System.out);
        }
    }

    /** 控制台受开关控制；文件始终写。 */
    private static void emit(String text) {
        if (ENABLED) {
            System.out.println(text);
        }
        if (FILE != null) {
            try {
                FILE.println(text);
            } catch (Throwable ignored) {
                // 文件写失败不影响主流程
            }
        }
    }
}
