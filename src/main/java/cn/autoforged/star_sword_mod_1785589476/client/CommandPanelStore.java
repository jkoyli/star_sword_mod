package cn.autoforged.star_sword_mod_1785589476.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令面板的本地状态：已执行命令历史（最多缓存 20 条，最新在前）与锁定快捷命令。
 * 锁定状态持久化到本地配置文件（config/star_sword_mod_1785589476_command_panel.json）。
 */
@OnlyIn(Dist.CLIENT)
public final class CommandPanelStore {

    public static final int MAX_HISTORY = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> HISTORY = new ArrayList<>();
    private static final Set<String> LOCKED = new LinkedHashSet<>();
    private static boolean loaded = false;

    private CommandPanelStore() {
    }

    private static Path filePath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("star_sword_mod_1785589476_command_panel.json");
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
            if (data == null) {
                return;
            }
            HISTORY.clear();
            if (data.history != null) {
                HISTORY.addAll(data.history.subList(0, Math.min(data.history.size(), MAX_HISTORY)));
            }
            LOCKED.clear();
            if (data.locked != null) {
                LOCKED.addAll(data.locked);
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        try {
            Data data = new Data();
            data.history = new ArrayList<>(HISTORY);
            data.locked = new ArrayList<>(LOCKED);
            Path path = filePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 记录一条已执行的命令（最新放最前，最多 20 条）。 */
    public static void addHistory(String command) {
        ensureLoaded();
        HISTORY.remove(command);
        HISTORY.add(0, command);
        while (HISTORY.size() > MAX_HISTORY) {
            HISTORY.remove(HISTORY.size() - 1);
        }
        save();
    }

    /** 历史记录，最新在前。 */
    public static List<String> getHistory() {
        ensureLoaded();
        return new ArrayList<>(HISTORY);
    }

    public static void clearHistory() {
        ensureLoaded();
        HISTORY.clear();
        save();
    }

    public static boolean isLocked(String command) {
        ensureLoaded();
        return LOCKED.contains(command);
    }

    public static void toggleLocked(String command) {
        ensureLoaded();
        if (!LOCKED.remove(command)) {
            LOCKED.add(command);
        }
        save();
    }

    public static Set<String> getLocked() {
        ensureLoaded();
        return new LinkedHashSet<>(LOCKED);
    }

    private static final class Data {
        List<String> history;
        List<String> locked;
    }
}
