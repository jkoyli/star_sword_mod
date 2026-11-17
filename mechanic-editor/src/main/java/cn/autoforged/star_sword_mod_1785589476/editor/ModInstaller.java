package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把构建产物装进 HMCL 实例的 {@code mods/}。
 *
 * <p>与隔壁 project 的做法一致但更克制：**复制**（不移动，build/libs 里的产物可以反复安装），
 * 旧的 {@code star_sword_mod-*.jar} 移到 {@code mods/.star-sword-backup/}（不删，方便回滚）。
 * 失败只返回消息、绝不抛给界面。
 */
final class ModInstaller {

    /** 安装结果。 */
    record Result(boolean ok, String message) {
    }

    private ModInstaller() {
    }

    /** 从工程 {@code build/libs} 里找最新的星辉剑 jar。 */
    static Path findSourceJar(Path projectRoot) {
        Path libs = projectRoot.resolve("build").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (var stream = Files.list(libs)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("star_sword_mod-") && n.endsWith(".jar");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** 安装到实例；{@code sourceJar} 为空时自动找。 */
    static Result install(Path projectRoot, Instance instance, Path sourceJar) {
        if (instance == null) {
            return new Result(false, "还没有选择实例");
        }
        Path source = sourceJar != null ? sourceJar : findSourceJar(projectRoot);
        if (source == null || !Files.isRegularFile(source)) {
            return new Result(false, "找不到构建产物：先跑一次 gradlew build（build/libs/star_sword_mod-*.jar）");
        }
        Path mods = instance.modsDir();
        try {
            Files.createDirectories(mods);
            List<Path> old = new ArrayList<>();
            try (var stream = Files.list(mods)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("star_sword_mod-") && n.endsWith(".jar");
                }).forEach(old::add);
            }
            if (!old.isEmpty()) {
                Path backup = mods.resolve(".star-sword-backup");
                Files.createDirectories(backup);
                for (Path jar : old) {
                    Files.move(jar, backup.resolve(jar.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path target = mods.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return new Result(true, "已装 " + source.getFileName() + " → " + mods
                    + (old.isEmpty() ? "" : "（旧 jar 已移到 .star-sword-backup）"));
        } catch (IOException e) {
            return new Result(false, "安装失败：" + e.getMessage());
        }
    }
}
