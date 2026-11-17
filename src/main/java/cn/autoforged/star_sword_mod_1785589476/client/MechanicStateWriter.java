package cn.autoforged.star_sword_mod_1785589476.client;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicRegistry;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏 -> 独立编辑器的心跳/状态回写（「游戏到底在不在、预设到底生效没有」的唯一证据）。
 *
 * <p>为什么必须有它：编辑器是另一个进程，它无法直接查询游戏内部状态；只靠「我 spawn 了进程」
 * 也回答不了「模组加载了吗 / 进世界了吗 / 预设被接受了吗」。所以这里把状态写成一份小 JSON，
 * 由编辑器轮询（见 {@code mechanic-editor} 的 {@code GameStateReader}）：
 *
 * <pre>
 * {
 *   "mod_version": "2.3.3",
 *   "loaded_at": 1700000000000,      // 本进程首次写状态的时间
 *   "heartbeat": 1700000001234,      // 每次刷新都会变；编辑器据此判断「是否还活着」
 *   "in_world": true,                // 是否已进入世界
 *   "player": "Steve",
 *   "dimension": "minecraft:overworld",
 *   "presets_pushed": true,          // 本次进程是否已把预设下发过
 *   "presets_pushed_at": 1700000000000,
 *   "values": { "anti_disarm": true, ... }   // 服务端权威值（来自快照）
 * }
 * </pre>
 *
 * <p>三条硬约束（照搬隔壁项目的教训）：
 * <ul>
 *   <li><b>原子写</b>：先写 {@code .tmp} 再替换，编辑器永远不会读到半截文件；</li>
 *   <li><b>Never throws</b>：写失败只吞掉，绝不能因为它影响游戏；</li>
 *   <li><b>节流</b>：每 40 tick（≈2s）一次，且只在客户端主线程调用。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class MechanicStateWriter {

    /** 状态文件名（与预设文件同目录；编辑器按同样的规则定位）。 */
    public static final String STATE_FILE_NAME = "star_sword_mod_1785589476_mechanics.state.json";

    /** 写入间隔（tick）。20 tick = 1 秒。 */
    private static final int INTERVAL_TICKS = 40;

    private static int ticks;
    private static long loadedAt;
    private static long lastWriteAt;
    private static boolean snapshotRequested;
    private static long presetsPushedAt;
    private static String lastError = "";

    private MechanicStateWriter() {
    }

    /** 客户端每 tick 调用；内部自我节流。 */
    public static void tick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        boolean inWorld = mc.level != null && mc.player != null;
        if (inWorld && !snapshotRequested) {
            // 进世界后先要一份权威快照，否则状态文件里的 values 只能是默认值。
            snapshotRequested = true;
            if (mc.getConnection() != null) {
                cn.autoforged.star_sword_mod_1785589476.networking.ModMessages.sendMechanicSnapshotRequest();
            }
        }
        if (++ticks < INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        // 标题界面也要写：编辑器据此区分「游戏起来了但模组没装」与「模组在跑、还没进世界」。
        write(mc, inWorld);
    }

    /** 预设下发成功后调用，让编辑器看到「已下发」以及时间点。 */
    public static void notePresetsPushed() {
        presetsPushedAt = System.currentTimeMillis();
    }

    /** 记录最近一次失败原因（编辑器会把它显示出来）。 */
    public static void noteError(String message) {
        lastError = message == null ? "" : message;
    }

    /** 立即写一次（进世界、退出世界等关键时刻调用）。 */
    public static void flush(Minecraft mc) {
        write(mc, true);
    }

    private static void write(Minecraft mc, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWriteAt < 1_500L) {
            return;
        }
        lastWriteAt = now;
        if (loadedAt == 0L) {
            loadedAt = now;
        }
        try {
            Path file = stateFile();
            Files.createDirectories(file.getParent());
            String json = buildJson(mc, now);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveFailed) {
                Files.deleteIfExists(tmp);
                throw moveFailed;
            }
        } catch (Throwable throwable) {
            // 状态文件写不出去不影响游戏；编辑器会显示「无心跳」。
            lastError = String.valueOf(throwable);
        }
    }

    /** 与预设文件同目录（因此 {@code -Dstar_sword.mech.file} 一改，两个文件一起搬）。 */
    static Path stateFile() {
        Path presets = ClientMechanicPresets.filePath();
        Path parent = presets.getParent();
        return parent == null ? presets : parent.resolve(STATE_FILE_NAME);
    }

    private static String buildJson(Minecraft mc, long now) {
        boolean inWorld = mc.level != null && mc.player != null;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"mod_version\": \"").append(escape(modVersion())).append("\",\n");
        sb.append("  \"loaded_at\": ").append(loadedAt).append(",\n");
        sb.append("  \"heartbeat\": ").append(now).append(",\n");
        sb.append("  \"in_world\": ").append(inWorld).append(",\n");
        sb.append("  \"player\": \"").append(escape(inWorld ? mc.player.getGameProfile().getName() : ""))
                .append("\",\n");
        sb.append("  \"dimension\": \"")
                .append(escape(mc.level == null ? "" : mc.level.dimension().location().toString()))
                .append("\",\n");
        boolean pushed = presetsPushedAt > 0L;
        sb.append("  \"presets_pushed\": ").append(pushed).append(",\n");
        sb.append("  \"presets_pushed_at\": ").append(presetsPushedAt).append(",\n");
        sb.append("  \"last_error\": \"").append(escape(lastError)).append("\",\n");
        sb.append("  \"values\": {");
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (MechanicRegistry.Def def : MechanicRegistry.all()) {
            values.put(def.id, ClientMechanicState.value(def.id));
        }
        boolean first = true;
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("\n    \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(StarSwordMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("?");
        } catch (Throwable throwable) {
            return "?";
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
