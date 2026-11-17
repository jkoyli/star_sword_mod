package cn.autoforged.star_sword_mod_1785589476.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读游戏侧写出的心跳/状态文件（{@code *.mechanics.state.json}）。
 *
 * <p>这是「编辑器如何知道游戏在不在」的答案：编辑器不猜、不 ping 端口，只读这份由模组
 * 每 2 秒原子重写的小 JSON。三个判定：
 * <ul>
 *   <li><b>文件不存在</b> → 游戏从未启动过，或从未进入世界；</li>
 *   <li><b>心跳时间戳变旧（> {@link #FRESH_MS}）</b> → 游戏已退出或卡死；</li>
 *   <li><b>心跳新鲜</b> → 游戏在跑；再看 {@code in_world} 与 {@code values} 判断预设是否真的生效。</li>
 * </ul>
 *
 * <p>解析用正则而不是 JSON 库：编辑器有「零第三方依赖」的硬约束，而这份文件由我们自己写出，
 * 结构固定。解析失败一律当作「没有状态」，绝不抛。
 */
final class GameStateReader {

    /** 心跳超过这个毫秒数就认为游戏已退出（模组每 2 秒写一次，留 4 倍余量）。 */
    static final long FRESH_MS = 8_000L;

    private static final Pattern NUMBER = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(\\d+)");
    private static final Pattern BOOLEAN = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*(true|false)");
    private static final Pattern STRING = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern VALUE_ENTRY = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*(true|false)");

    private GameStateReader() {
    }

    /** 状态快照（不可变）。 */
    record State(String modVersion,
                 long heartbeat,
                 boolean inWorld,
                 String player,
                 String dimension,
                 boolean presetsPushed,
                 long presetsPushedAt,
                 String lastError,
                 Map<String, Boolean> values) {

        boolean fresh(long now) {
            return heartbeat > 0L && now - heartbeat <= FRESH_MS;
        }
    }

    /** 状态文件与预设文件同目录（模组侧也是这么算的）。 */
    static Path resolve(Path settingsFile) {
        if (settingsFile == null) {
            return null;
        }
        Path parent = settingsFile.getParent();
        String name = "star_sword_mod_1785589476_mechanics.state.json";
        return parent == null ? settingsFile.resolveSibling(name) : parent.resolve(name);
    }

    /** 读状态；文件缺失或解析失败返回 {@code null}（Never throws）。 */
    static State read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        if (text.isBlank()) {
            return null;
        }

        Map<String, String> strings = new LinkedHashMap<>();
        Matcher sm = STRING.matcher(text);
        while (sm.find()) {
            strings.put(sm.group(1), unescape(sm.group(2)));
        }

        Map<String, Long> numbers = new LinkedHashMap<>();
        Matcher nm = NUMBER.matcher(text);
        while (nm.find()) {
            try {
                numbers.put(nm.group(1), Long.parseLong(nm.group(2)));
            } catch (NumberFormatException ignored) {
                // 单个字段坏掉不影响其它字段。
            }
        }

        Map<String, Boolean> booleans = new LinkedHashMap<>();
        Matcher bm = BOOLEAN.matcher(text);
        while (bm.find()) {
            booleans.put(bm.group(1), Boolean.parseBoolean(bm.group(2)));
        }

        long heartbeat = numbers.getOrDefault("heartbeat", 0L);
        if (heartbeat <= 0L) {
            // 没有心跳就当作「文件是坏的/写了一半」，不据此判断游戏在跑。
            return null;
        }

        Map<String, Boolean> values = new LinkedHashMap<>();
        int valuesAt = text.indexOf("\"values\"");
        if (valuesAt >= 0) {
            Matcher vm = VALUE_ENTRY.matcher(text.substring(valuesAt));
            while (vm.find()) {
                String id = vm.group(1);
                if (SettingsCatalog.byId(id) != null) {
                    values.put(id, Boolean.parseBoolean(vm.group(2)));
                }
            }
        }

        return new State(
                strings.getOrDefault("mod_version", "?"),
                heartbeat,
                booleans.getOrDefault("in_world", false),
                strings.getOrDefault("player", ""),
                strings.getOrDefault("dimension", ""),
                booleans.getOrDefault("presets_pushed", false),
                numbers.getOrDefault("presets_pushed_at", 0L),
                strings.getOrDefault("last_error", ""),
                values);
    }

    private static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
