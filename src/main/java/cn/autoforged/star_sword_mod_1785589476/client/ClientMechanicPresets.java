package cn.autoforged.star_sword_mod_1785589476.client;

import cn.autoforged.star_sword_mod_1785589476.helper.MechanicRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动面板的「预设」：进服之前玩家在标题界面调好的开关值。
 *
 * <p>为什么需要单独一层：标题界面还没有服务器连接，玩家 NBT 与世界存档都读不到，
 * 所以启动面板能展示/修改的只能是本地预设；点「启动」后照常进入标题界面，
 * 第一次登录时由 {@link cn.autoforged.star_sword_mod_1785589476.client.ModClientForgeEvents}
 * 逐个下发给服务端复核（持剑 / op / 限频都照旧），成功后由服务端回发权威快照。
 *
 * <p>预设落本地 {@code config/star_sword_mod_1785589476_mechanics.json}，跨重启保留，
 * 这样「调完设置再点启动」这件事在下次开游戏时仍然是上次调好的样子。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientMechanicPresets {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 与独立编辑器 {@code SettingsFile.FILE_NAME} 必须逐字一致。 */
    private static final String FILE_NAME = "star_sword_mod_1785589476_mechanics.json";

    private static final Map<String, Boolean> VALUES = new LinkedHashMap<>();
    private static boolean loaded;
    /** 本进程是否已经把预设下发过一次（只在首次进服时下发，之后以服务端状态为准）。 */
    private static boolean pushed;

    private ClientMechanicPresets() {
    }

    /**
     * 定位预设文件。三级解析（照搬 stardome-editor 的做法）：
     * <ol>
     *   <li>{@code -Dstar_sword.mech.file=<绝对路径>} —— 独立编辑器启动游戏时会注入，
     *       因为编辑器的 {@code config/} 与游戏的 {@code run/config/} 通常不是同一目录；</li>
     *   <li>游戏目录下的 {@code config/star_sword_mod_1785589476_mechanics.json}（发布形态）；</li>
     *   <li>游戏目录根下的同名文件（单机/开发时手动放置）。</li>
     * </ol>
     */
    static Path filePath() {
        String override = System.getProperty("star_sword.mech.file");
        if (override != null && !override.isBlank()) {
            try {
                return Paths.get(override.trim()).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                // 覆盖路径非法：继续走默认规则。
            }
        }
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path inConfig = gameDir.resolve("config").resolve(FILE_NAME);
        if (Files.isRegularFile(inConfig)) {
            return inConfig;
        }
        Path atRoot = gameDir.resolve(FILE_NAME);
        return Files.isRegularFile(atRoot) ? atRoot : inConfig;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path path = filePath();
            if (!Files.exists(path)) {
                return;
            }
            Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (data == null || data.values == null) {
                return;
            }
            VALUES.putAll(data.values);
        } catch (Exception ignored) {
            // 预设文件坏掉不应该影响开游戏：按空预设处理。
        }
    }

    private static void save() {
        try {
            Data data = new Data();
            data.values = new LinkedHashMap<>(VALUES);
            Path path = filePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 同上：写失败只影响下次启动的默认值。
        }
    }

    /** 预设值；没设过就用注册表默认值。 */
    public static boolean get(String id) {
        ensureLoaded();
        Boolean value = VALUES.get(id);
        if (value != null) {
            return value;
        }
        MechanicRegistry.Def def = MechanicRegistry.byId(id);
        return def != null && def.defaultValue;
    }

    /** 修改预设（立即写盘）。 */
    public static void set(String id, boolean value) {
        ensureLoaded();
        VALUES.put(id, value);
        save();
    }

    /** 是否还有没下发过的预设。 */
    public static boolean hasPending() {
        ensureLoaded();
        return !pushed && !VALUES.isEmpty();
    }

    /** 取出待下发的预设并标记为已下发（只下发一次）。 */
    public static Map<String, Boolean> takePending() {
        ensureLoaded();
        pushed = true;
        return new LinkedHashMap<>(VALUES);
    }

    private static final class Data {
        Map<String, Boolean> values;
    }
}
