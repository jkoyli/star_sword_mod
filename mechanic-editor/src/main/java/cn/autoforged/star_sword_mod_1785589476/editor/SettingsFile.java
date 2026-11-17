package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 与 mod 之间的唯一契约：{@code config/star_sword_mod_1785589476_mechanics.json}。
 *
 * <p>格式与 mod 端 {@code ClientMechanicPresets} 完全一致：
 * <pre>
 * {
 *   "values": {
 *     "anti_disarm": true,
 *     ...
 *   }
 * }
 * </pre>
 *
 * <p>三个借鉴来的要点：
 * <ol>
 *   <li><b>路径三级解析</b>：{@code -Dstar_sword.mech.file} 覆盖 → 运行目录 {@code config/} →
 *       运行目录根。这样开发期（工程根）与发布期（游戏目录）用同一份代码；</li>
 *   <li><b>永不抛异常</b>：文件缺失/损坏一律按默认值处理，编辑器还能正常打开；</li>
 *   <li><b>只存文本文件</b>：不依赖 Gson/Jackson，避免给编辑器引入第三方依赖。</li>
 * </ol>
 */
final class SettingsFile {

    static final String FILE_NAME = "star_sword_mod_1785589476_mechanics.json";

    /** 与 mod 端一致的取值键。 */
    private static final Pattern ENTRY = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*(true|false)");

    private SettingsFile() {
    }

    /** 解析出配置文件路径（可能尚不存在）。 */
    static Path resolve(Path root) {
        String override = System.getProperty("star_sword.mech.file");
        if (override != null && !override.isBlank()) {
            try {
                return Paths.get(override.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                // 覆盖路径非法就继续走默认规则。
            }
        }
        Path inConfig = root.resolve("config").resolve(FILE_NAME);
        if (Files.isRegularFile(inConfig)) {
            return inConfig;
        }
        Path atRoot = root.resolve(FILE_NAME);
        if (Files.isRegularFile(atRoot)) {
            return atRoot;
        }
        // 都不存在：默认写到游戏/工程目录的 config 下。
        return inConfig;
    }

    /**
     * 指定游戏目录（HMCL 版本隔离时就是 {@code versions/<实例名>}）下的配置文件路径。
     *
     * <p>这条与 mod 侧 {@code ClientMechanicPresets} 的默认解析完全对应：
     * 游戏读 {@code <gameDirectory>/config/star_sword_...json}，所以把文件写到实例目录的
     * {@code config/} 里，就**不需要**再注入 {@code -Dstar_sword.mech.file}。
     */
    static Path forGameDir(Path gameDir) {
        return gameDir.resolve("config").resolve(FILE_NAME);
    }

    /**
     * 读现有值；缺失、损坏、编码异常都返回默认值（Never throws）。
     */
    static Map<String, Boolean> load(Path file) {
        Map<String, Boolean> values = SettingsCatalog.defaults();
        if (file == null || !Files.isRegularFile(file)) {
            return values;
        }
        String text;
        try {
            byte[] bytes = Files.readAllBytes(file);
            text = new String(bytes, StandardCharsets.UTF_8);
            // 去掉 UTF-8 BOM（Gson 能容忍，这里自己解析就得先剥掉）。
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
        } catch (IOException e) {
            return values;
        }
        int valuesAt = text.indexOf("\"values\"");
        String span = valuesAt >= 0 ? text.substring(valuesAt) : text;
        Matcher matcher = ENTRY.matcher(span);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (SettingsCatalog.byId(id) == null) {
                continue; // 未知键（旧版本/别的分支写的）直接忽略，不崩。
            }
            values.put(id, Boolean.parseBoolean(matcher.group(2)));
        }
        return values;
    }

    /**
     * 写回配置文件（先写临时文件再替换，避免写一半断电留下半个文件）。
     */
    static void save(Path file, Map<String, Boolean> values) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"values\": {\n");
        List<SettingsCatalog.Def> defs = SettingsCatalog.all();
        for (int i = 0; i < defs.size(); i++) {
            String id = defs.get(i).id;
            boolean value = values.getOrDefault(id, defs.get(i).defaultValue);
            sb.append("    \"").append(id).append("\": ").append(value);
            if (i < defs.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  }\n}\n");

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            Files.deleteIfExists(tmp);
            throw moveFailed;
        }
    }

    /** 从磁盘读一份「上次保存的状态」用于展示；读不到就是默认值。 */
    static Map<String, Boolean> loadOrDefaults(Path file) {
        Map<String, Boolean> loaded = load(file);
        Map<String, Boolean> merged = new LinkedHashMap<>(SettingsCatalog.defaults());
        merged.putAll(loaded);
        return merged;
    }
}
