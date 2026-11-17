package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 找 HMCL 与它管理的游戏目录。
 *
 * <p>为什么不猜路径：HMCL 的根目录就是「里面有 {@code HMCL*.exe} 且旁边有 {@code .hmcl/}」的那个目录，
 * 而它管理的游戏目录写在 {@code .hmcl/config/game-directories.json} 里（相对路径相对 HMCL 根）。
 * 这两条都是 HMCL 自己落盘的，所以按它们找比硬编码 {@code %APPDATA%\.minecraft} 准得多。
 *
 * <p>只读、Never-throws：找不到就返回空列表，界面退化成「手动填路径」。
 */
final class HmclDetector {

    /** HMCL 安装信息。 */
    static final class Hmcl {
        final Path root;
        final Path exe;

        Hmcl(Path root, Path exe) {
            this.root = root;
            this.exe = exe;
        }
    }

    private static final Pattern DIRECTORY_PATH =
            Pattern.compile("\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private HmclDetector() {
    }

    /** 在常见位置找 HMCL（有 exe 且旁边有 .hmcl 目录）。 */
    static Hmcl find() {
        for (Path dir : candidateRoots()) {
            try {
                if (!Files.isDirectory(dir) || !Files.isDirectory(dir.resolve(".hmcl"))) {
                    continue;
                }
                try (var stream = Files.list(dir)) {
                    Path exe = stream
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase();
                                return n.startsWith("hmcl") && (n.endsWith(".exe") || n.endsWith(".jar"));
                            })
                            .findFirst().orElse(null);
                    if (exe != null) {
                        return new Hmcl(dir, exe);
                    }
                }
            } catch (Throwable ignored) {
                // 单个候选失败不影响其它候选。
            }
        }
        return null;
    }

    private static List<Path> candidateRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        String home = System.getProperty("user.home", "");
        for (String s : new String[]{
                home + "\\Desktop\\文件夹\\游戏",
                home + "\\Desktop",
                home + "\\Downloads",
                home,
                System.getProperty("user.dir", "."),
        }) {
            addIfDir(roots, s);
        }
        // 桌面/下载下面再挖两层（HMCL 常被放进「游戏」这类子目录）。
        for (String base : new String[]{home + "\\Desktop", home + "\\Downloads"}) {
            Path basePath = Paths.get(base);
            if (!Files.isDirectory(basePath)) {
                continue;
            }
            try (var stream = Files.list(basePath)) {
                stream.filter(Files::isDirectory).limit(64).forEach(child -> {
                    addIfDir(roots, child.toString());
                    try (var inner = Files.list(child)) {
                        inner.filter(Files::isDirectory).limit(64)
                                .forEach(grand -> addIfDir(roots, grand.toString()));
                    } catch (Throwable ignored) {
                        // 权限/占用等：跳过。
                    }
                });
            } catch (Throwable ignored) {
                // 跳过。
            }
        }
        return new ArrayList<>(roots);
    }

    private static void addIfDir(Set<Path> roots, String value) {
        try {
            Path p = Paths.get(value).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                roots.add(p);
            }
        } catch (Throwable ignored) {
            // 非法路径。
        }
    }

    /** 读 {@code .hmcl/config/game-directories.json} 里的游戏目录（相对路径按 HMCL 根展开）。 */
    static List<Path> gameDirectories(Hmcl hmcl) {
        List<Path> out = new ArrayList<>();
        if (hmcl == null) {
            return out;
        }
        Path json = hmcl.root.resolve(".hmcl").resolve("config").resolve("game-directories.json");
        if (!Files.isRegularFile(json)) {
            return out;
        }
        try {
            String text = Files.readString(json, StandardCharsets.UTF_8);
            Matcher matcher = DIRECTORY_PATH.matcher(text);
            while (matcher.find()) {
                String raw = unescape(matcher.group(1));
                Path p = Paths.get(raw);
                if (!p.isAbsolute()) {
                    p = hmcl.root.resolve(raw);
                }
                p = p.toAbsolutePath().normalize();
                if (Files.isDirectory(p) && !out.contains(p)) {
                    out.add(p);
                }
            }
        } catch (IOException ignored) {
            // 读不到就退化成固定候选。
        }
        return out;
    }

    /** 兜底：常见的游戏目录（HMCL 配置不可读时用）。 */
    static List<Path> fallbackGameDirectories() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            addIfDir2(out, appData + "\\.minecraft");
        }
        String home = System.getProperty("user.home", "");
        addIfDir2(out, home + "\\AppData\\Roaming\\.minecraft");
        return out;
    }

    private static void addIfDir2(List<Path> out, String value) {
        try {
            Path p = Paths.get(value).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                out.add(p);
            }
        } catch (Throwable ignored) {
            // 忽略。
        }
    }

    private static String unescape(String value) {
        return value.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/");
    }
}
