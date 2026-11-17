package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动游戏：优先走**玩家真正的游戏**（HMCL），开发环境只是兜底。
 *
 * <p>为什么不能直接让 HMCL 进某个实例：HMCL 3.16 的文档里只有 {@code -Dhmcl.*} 调试属性，
 * 没有「命令行启动指定版本」的开关（见 docs.hmcl.net「JVM 选项与环境变量」）。
 * 所以这里做的是：把 mod 与配置就位 → 启动 HMCL → 由玩家点一次「启动」→
 * 编辑器靠心跳文件确认游戏真的起来了（而不是只在开发环境里自嗨）。
 *
 * <p>三种启动方式（按界面选择）：
 * <ol>
 *   <li>{@link #launchHmcl}：玩家真实环境；不传任何参数（HMCL 不认「启动某个版本」）；</li>
 *   <li>{@link #launchDev}：开发环境 {@code gradlew runClient}，会注入 {@code -D}；</li>
 *   <li>{@code -Dstar_sword.launch.command=...} / {@code star_sword-launch.bat}：自定义。</li>
 * </ol>
 */
final class GameLauncher {

    /** 启动结果；{@code process} 供界面轮询「进程还活着吗」。 */
    record Result(String message, Process process) {
    }

    private GameLauncher() {
    }

    /**
     * 启动 HMCL。
     *
     * <p>工作目录设成 HMCL 根目录：它靠这个目录下的 {@code .hmcl/} 找自己的配置与实例列表。
     * 不传参数——HMCL 没有「打开即启动某版本」的命令行。
     */
    static Result launchHmcl(HmclDetector.Hmcl hmcl, Instance instance) throws IOException {
        if (hmcl == null) {
            return new Result("没找到 HMCL：请把 HMCL-*.exe 放到桌面/下载/游戏目录，或用「开发启动」", null);
        }
        ProcessBuilder builder = new ProcessBuilder(hmcl.exe.toAbsolutePath().toString());
        builder.directory(hmcl.root.toFile());
        builder.inheritIO();
        Process process = builder.start();
        String which = instance == null ? "" : "，然后在列表里启动「" + instance.name + "」";
        return new Result("已打开 HMCL" + which + "；我会自动检测心跳", process);
    }

    /** 开发环境：{@code gradlew runClient}，并把配置路径/关面板两个属性注入进去。 */
    static Result launchDev(Path root, Path settingsFile) throws IOException {
        List<String> command = resolveDevCommand(root);
        if (command == null || command.isEmpty()) {
            return new Result("没找到启动方式：可设置 -Dstar_sword.launch.command，或在工程根放 star_sword-launch.bat", null);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.inheritIO();
        Map<String, String> env = builder.environment();
        String existing = env.get("JAVA_TOOL_OPTIONS");
        // ① 明说配置文件绝对路径（游戏目录的 config/ 与工程根不是一回事）；
        // ② 关掉游戏内启动面板（玩家已经在编辑器里配过）。
        String injection = "-Dstar_sword.mech.file=" + settingsFile.toAbsolutePath()
                + " -Dstar_sword.mech.startupPanel=false";
        env.put("JAVA_TOOL_OPTIONS",
                existing == null || existing.isBlank() ? injection : existing + " " + injection);

        Process process = builder.start();
        return new Result("已启动开发环境：" + String.join(" ", command), process);
    }

    /** 解析开发启动命令；找不到返回 null。 */
    static List<String> resolveDevCommand(Path root) {
        String override = System.getProperty("star_sword.launch.command");
        if (override != null && !override.isBlank()) {
            List<String> parts = new ArrayList<>();
            for (String token : override.trim().split("\\s+")) {
                if (!token.isEmpty()) {
                    parts.add(token);
                }
            }
            return parts;
        }

        Path custom = root.resolve("star_sword-launch.bat");
        if (Files.isRegularFile(custom)) {
            return List.of(custom.toAbsolutePath().toString());
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File gradlew = root.resolve(windows ? "gradlew.bat" : "gradlew").toFile();
        if (gradlew.isFile()) {
            return List.of(gradlew.getAbsolutePath(), "runClient");
        }
        return null;
    }
}
