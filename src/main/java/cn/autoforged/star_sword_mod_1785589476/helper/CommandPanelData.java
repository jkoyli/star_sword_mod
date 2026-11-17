package cn.autoforged.star_sword_mod_1785589476.helper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 命令面板共用数据：指令树（用于“更多”二级界面）的序列化结构 + 原版根命令表（来源与权限等级）。
 * 权限等级信息仅供界面展示，不参与实际执行（执行时使用满权限命令源）。
 */
public final class CommandPanelData {

    public static final String SOURCE_VANILLA = "vanilla";
    public static final String SOURCE_UNKNOWN = "unknown";
    /** 指令树最大深度与总节点数，防止过大的同步包（整棵树约 600~800 节点，单节点约 20 字节）。 */
    public static final int MAX_TREE_DEPTH = 8;
    public static final int MAX_TREE_NODES = 1000;

    /** 指令树节点（根命令 -> 子命令逐级嵌套），可直接编码进网络包。 */
    public static final class Node {
        public final String name;
        public String source;
        public final List<Node> children = new ArrayList<>();

        public Node(String name, String source) {
            this.name = name;
            this.source = source;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.name);
            buf.writeUtf(this.source);
            buf.writeVarInt(this.children.size());
            for (Node child : this.children) {
                child.encode(buf);
            }
        }

        public static Node decode(FriendlyByteBuf buf) {
            Node node = new Node(buf.readUtf(64), buf.readUtf(128));
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) {
                node.children.add(decode(buf));
            }
            return node;
        }
    }

    /** 原版根命令集合（与 mc-src/net/minecraft/commands/Commands.java 中注册的指令一致）。 */
    public static final Set<String> VANILLA_COMMANDS = Set.of(
            "advancement", "attribute", "bossbar", "clear", "clone", "damage", "data", "datapack",
            "debug", "defaultgamemode", "difficulty", "effect", "enchant", "execute",
            "experience", "xp", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule",
            "give", "help", "item", "kick", "kill", "list", "locate", "loot", "me", "msg", "tell",
            "w", "particle", "place", "playsound", "reload", "recipe", "return", "ride", "say",
            "schedule", "scoreboard", "seed", "setblock", "setidletimeout", "setworldspawn",
            "spawnpoint", "spectate", "spreadplayers", "stopsound", "summon", "tag", "team",
            "teammsg", "tm", "tellraw", "time", "title", "trigger", "weather", "worldborder",
            "teleport", "tp", "jfr", "perf", "publish", "save-all", "save-off", "save-on", "stop",
            "ban", "ban-ip", "banlist", "deop", "op", "pardon", "pardon-ip", "whitelist", "test",
            "spawn_armor_trims");

    /** 原版根命令所需权限等级（仅作提示；执行时无视权限）。来源：各命令类的 hasPermission(N) 注册。 */
    public static final Map<String, Integer> VANILLA_PERMISSIONS = Map.ofEntries(
            Map.entry("help", 0), Map.entry("list", 0), Map.entry("me", 0), Map.entry("msg", 0),
            Map.entry("tell", 0), Map.entry("w", 0), Map.entry("teammsg", 0), Map.entry("tm", 0),
            Map.entry("trigger", 0), Map.entry("test", 0),
            Map.entry("advancement", 2), Map.entry("attribute", 2), Map.entry("bossbar", 2),
            Map.entry("clear", 2), Map.entry("clone", 2), Map.entry("damage", 2), Map.entry("data", 2),
            Map.entry("datapack", 2), Map.entry("defaultgamemode", 2),
            Map.entry("difficulty", 2), Map.entry("effect", 2), Map.entry("enchant", 2),
            Map.entry("execute", 2), Map.entry("experience", 2), Map.entry("xp", 2),
            Map.entry("fill", 2), Map.entry("fillbiome", 2), Map.entry("forceload", 2),
            Map.entry("function", 2), Map.entry("gamemode", 2), Map.entry("gamerule", 2),
            Map.entry("give", 2), Map.entry("item", 2), Map.entry("kill", 2), Map.entry("locate", 2),
            Map.entry("loot", 2), Map.entry("particle", 2), Map.entry("place", 2),
            Map.entry("playsound", 2), Map.entry("recipe", 2), Map.entry("reload", 2),
            Map.entry("return", 2), Map.entry("ride", 2), Map.entry("say", 2), Map.entry("seed", 2),
            Map.entry("schedule", 2), Map.entry("scoreboard", 2),
            Map.entry("setblock", 2), Map.entry("spawnpoint", 2), Map.entry("setworldspawn", 2),
            Map.entry("spectate", 2), Map.entry("spreadplayers", 2), Map.entry("stopsound", 2),
            Map.entry("summon", 2), Map.entry("tag", 2), Map.entry("team", 2), Map.entry("tellraw", 2),
            Map.entry("time", 2), Map.entry("title", 2), Map.entry("weather", 2),
            Map.entry("worldborder", 2), Map.entry("teleport", 2), Map.entry("tp", 2),
            Map.entry("spawn_armor_trims", 2),
            Map.entry("ban", 3), Map.entry("ban-ip", 3), Map.entry("banlist", 3), Map.entry("deop", 3),
            Map.entry("debug", 3), Map.entry("kick", 3), Map.entry("op", 3), Map.entry("pardon", 3),
            Map.entry("pardon-ip", 3), Map.entry("setidletimeout", 3), Map.entry("whitelist", 3),
            Map.entry("jfr", 4), Map.entry("perf", 4), Map.entry("publish", 4), Map.entry("save-all", 4),
            Map.entry("save-off", 4), Map.entry("save-on", 4), Map.entry("stop", 4));

    private CommandPanelData() {
    }

    public static boolean isVanilla(String rootName) {
        return rootName != null && VANILLA_COMMANDS.contains(rootName);
    }

    /** 原版根命令权限等级；非原版返回 null（界面不显示权限提示）。 */
    public static Integer permissionOf(String rootName) {
        return VANILLA_PERMISSIONS.get(rootName);
    }

    /** 从服务端 dispatcher 构建完整指令树（含每个根命令的来源模组标记）。 */
    public static <S> List<Node> buildTree(CommandDispatcher<S> dispatcher) {
        List<Node> roots = new ArrayList<>();
        for (CommandNode<S> child : dispatcher.getRoot().getChildren()) {
            roots.add(buildNode(child, sourceOf(child), 1, new int[]{0}));
        }
        return roots;
    }

    private static <S> Node buildNode(CommandNode<S> node, String source, int depth, int[] counter) {
        String name = node.getName();
        if (node instanceof ArgumentCommandNode<?, ?>) {
            name = "<" + name + ">";
        }
        Node out = new Node(name, source);
        counter[0]++;
        if (depth < MAX_TREE_DEPTH && counter[0] < MAX_TREE_NODES) {
            // 重定向（如 tp -> teleport）节点本身没有子节点，展开重定向目标
            CommandNode<S> target = node;
            if (node.getRedirect() != null && node.getChildren().isEmpty()) {
                target = node.getRedirect();
            }
            for (CommandNode<S> child : target.getChildren()) {
                out.children.add(buildNode(child, source, depth + 1, counter));
            }
        }
        return out;
    }

    /** 判定根命令来源：原版直接识别；其余通过命令实现类的 CodeSource 与各模组文件路径匹配。 */
    private static <S> String sourceOf(CommandNode<S> node) {
        if (isVanilla(node.getName())) {
            return SOURCE_VANILLA;
        }
        CommandNode<?> cmdNode = findCommandNode(node);
        if (cmdNode == null || cmdNode.getCommand() == null) {
            return SOURCE_UNKNOWN;
        }
        ProtectionDomain pd = cmdNode.getCommand().getClass().getProtectionDomain();
        CodeSource codeSource = pd == null ? null : pd.getCodeSource();
        URL location = codeSource == null ? null : codeSource.getLocation();
        if (location == null) {
            return SOURCE_UNKNOWN;
        }
        try {
            Path classPath = Paths.get(location.toURI()).toAbsolutePath().normalize();
            for (IModInfo mod : ModList.get().getMods()) {
                IModFileInfo fileInfo = mod.getOwningFile();
                if (fileInfo == null || fileInfo.getFile() == null) {
                    continue;
                }
                try {
                    Path modPath = fileInfo.getFile().getFilePath().toAbsolutePath().normalize();
                    if (classPath.equals(modPath)) {
                        return mod.getModId();
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return SOURCE_UNKNOWN;
    }

    /** 在子树中找一个带执行体的节点（客户端同步树中的节点命令是空实现，只在服务端有意义）。 */
    private static CommandNode<?> findCommandNode(CommandNode<?> node) {
        if (node.getCommand() != null) {
            return node;
        }
        for (CommandNode<?> child : node.getChildren()) {
            CommandNode<?> found = findCommandNode(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
