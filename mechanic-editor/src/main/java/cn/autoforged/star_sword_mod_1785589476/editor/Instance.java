package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HMCL 的一个「版本/实例」：{@code <游戏目录>/versions/<名字>}，隔离模式下面就是它自己的 mods 与 config。
 *
 * <p>判定依据（都来自磁盘事实）：
 * <ul>
 *   <li>有 {@code mods/} 或 {@code .hmcl/} 或同名 {@code <名字>.json} → 算一个可玩实例；</li>
 *   <li>{@code .hmcl/} 存在 = HMCL 对该版本开了「版本隔离」，游戏目录就是这层，mods/config 都在这里；</li>
 *   <li>已装的星辉剑版本 = {@code mods/} 下 {@code star_sword_mod-*.jar} 的文件名。</li>
 * </ul>
 */
final class Instance {

    final String name;
    final Path dir;
    final boolean isolated;
    final int modCount;
    /** 已安装的星辉剑 jar 文件名；没装为 null。 */
    final String installedStarSword;
    final String mcVersion;

    Instance(String name, Path dir, boolean isolated, int modCount,
             String installedStarSword, String mcVersion) {
        this.name = name;
        this.dir = dir;
        this.isolated = isolated;
        this.modCount = modCount;
        this.installedStarSword = installedStarSword;
        this.mcVersion = mcVersion;
    }

    /** 直接给 JComboBox 用：一行说明一个实例的关键事实。 */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        sb.append("　[").append(mcVersion.isEmpty() ? "?" : mcVersion).append(']');
        sb.append("　mods=").append(modCount);
        sb.append(installedStarSword == null ? "　未装星辉剑" : "　已装 " + installedStarSword);
        if (!isolated) {
            sb.append("　（未隔离：mods 可能在游戏目录根）");
        }
        return sb.toString();
    }

    /** 该实例的 config 目录（星辉剑预设文件放这里，mod 默认就在 gameDir/config 下找）。 */
    Path configDir() {
        return dir.resolve("config");
    }

    Path modsDir() {
        return dir.resolve("mods");
    }

    // ===================== 扫描 =====================

    private static final Pattern MC_VERSION = Pattern.compile("\"(?:inheritsFrom|id)\"\\s*:\\s*\"([^\"]+)\"");

    static List<Instance> scan(Path gameDir) {
        List<Instance> out = new ArrayList<>();
        if (gameDir == null) {
            return out;
        }
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) {
            // 没有 versions：把游戏目录本身当一个实例（非隔离的旧布局）。
            Instance single = describe(gameDir.getFileName() == null ? "gameDir" : gameDir.getFileName().toString(),
                    gameDir, gameDir.resolve("mods"));
            if (single != null) {
                out.add(single);
            }
            return out;
        }
        try (var stream = Files.list(versions)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> {
                        Path mods = dir.resolve("mods");
                        // 非隔离实例的 mods 在游戏目录根：也要能认出来。
                        Instance instance = describe(dir.getFileName().toString(), dir, mods);
                        if (instance != null) {
                            out.add(instance);
                        }
                    });
        } catch (IOException ignored) {
            // 读不到目录就返回已收集的。
        }
        return out;
    }

    private static Instance describe(String name, Path dir, Path modsCandidate) {
        try {
            boolean isolated = Files.isDirectory(dir.resolve(".hmcl"));
            Path mods = modsCandidate;
            if (!Files.isDirectory(mods)) {
                mods = dir.resolve("mods");
            }
            Path json = dir.resolve(name + ".json");
            boolean hasJson = Files.isRegularFile(json);
            if (!isolated && !Files.isDirectory(mods) && !hasJson) {
                return null;
            }
            int modCount = 0;
            String installed = null;
            if (Files.isDirectory(mods)) {
                try (var list = Files.list(mods)) {
                    var jars = list.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).toList();
                    modCount = jars.size();
                    for (Path jar : jars) {
                        String fileName = jar.getFileName().toString();
                        if (fileName.startsWith("star_sword_mod-")) {
                            installed = fileName;
                        }
                    }
                }
            }
            String mcVersion = "";
            if (hasJson) {
                try {
                    String text = Files.readString(json, StandardCharsets.UTF_8);
                    Matcher m = MC_VERSION.matcher(text);
                    if (m.find()) {
                        mcVersion = m.group(1);
                    }
                } catch (IOException ignored) {
                    // 版本号读不到不影响实例本身。
                }
            }
            if (mcVersion.isEmpty()) {
                mcVersion = name;
            }
            return new Instance(name, dir, isolated, modCount, installed, mcVersion);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
