package cn.autoforged.star_sword_mod_1785589476.client;

import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;

/**
 * 命令中文说明工具：指令用途文本统一从语言文件读取（确保中文本地化），
 * 并为命令参数名提供中文对照，用于生成“参数说明 / 用法提示”。
 */
@OnlyIn(Dist.CLIENT)
public final class CommandDescHelper {

    public static final String DESC_PREFIX = "command.star_sword_mod_1785589476.desc.";

    /** 常见命令参数名的中文对照（未收录的参数名原样显示）。 */
    private static final Map<String, String> ARG_ZH = Map.ofEntries(
            Map.entry("time", "时间"), Map.entry("amount", "数量"), Map.entry("target", "目标"),
            Map.entry("targets", "目标实体"), Map.entry("entity", "实体"), Map.entry("entities", "实体列表"),
            Map.entry("player", "玩家"), Map.entry("players", "玩家列表"), Map.entry("pos", "位置"),
            Map.entry("pos1", "起点"), Map.entry("pos2", "终点"), Map.entry("blockPos", "方块位置"),
            Map.entry("destination", "目标位置"), Map.entry("from", "来源"), Map.entry("to", "目标"),
            Map.entry("item", "物品"), Map.entry("items", "物品"), Map.entry("block", "方块"),
            Map.entry("gamemode", "游戏模式"), Map.entry("rule", "规则"), Map.entry("value", "值"),
            Map.entry("message", "消息"), Map.entry("name", "名称"), Map.entry("count", "数量"),
            Map.entry("enchantment", "附魔"), Map.entry("level", "等级"), Map.entry("seconds", "秒数"),
            Map.entry("cause", "伤害原因"), Map.entry("damage", "伤害"), Map.entry("attribute", "属性"),
            Map.entry("modifier", "修饰符"), Map.entry("sound", "音效"), Map.entry("source", "声音来源"),
            Map.entry("channel", "频道"), Map.entry("biome", "生物群系"), Map.entry("structure", "结构"),
            Map.entry("feature", "要素"), Map.entry("dimension", "维度"), Map.entry("score", "分数"),
            Map.entry("advancement", "进度"), Map.entry("tag", "标签"), Map.entry("team", "队伍"),
            Map.entry("recipe", "配方"), Map.entry("function", "函数"), Map.entry("slot", "槽位"),
            Map.entry("x", "X坐标"), Map.entry("y", "Y坐标"), Map.entry("z", "Z坐标"),
            Map.entry("range", "范围"), Map.entry("seed", "种子"), Map.entry("title", "标题"),
            Map.entry("text", "文本"), Map.entry("color", "颜色"), Map.entry("objectives", "计分项"),
            Map.entry("criteria", "准则"), Map.entry("id", "ID"), Map.entry("path", "路径"),
            Map.entry("nbt", "NBT"), Map.entry("threshold", "阈值"), Map.entry("duration", "时长"),
            Map.entry("velocity", "速度"), Map.entry("speed", "速度"), Map.entry("rot", "角度"),
            Map.entry("rotation", "角度"), Map.entry("anchor", "锚点"), Map.entry("align", "对齐"),
            Map.entry("location", "位置"), Map.entry("direction", "方向"), Map.entry("scoreboard", "计分板"));

    private CommandDescHelper() {
    }

    public static String descriptionKey(String rootName) {
        return DESC_PREFIX + rootName;
    }

    public static boolean hasDescription(String rootName) {
        return rootName != null && I18n.exists(descriptionKey(rootName));
    }

    public static String description(String rootName) {
        return I18n.get(descriptionKey(rootName));
    }

    public static String translateArgName(String name) {
        String zh = ARG_ZH.get(name);
        return zh != null ? zh : name;
    }

    /** 把补全值（如 "day"、"<time>"）中的参数名中文化。 */
    public static String translateSuggestion(String suggestion) {
        if (suggestion.startsWith("<") && suggestion.endsWith(">") && suggestion.length() > 2) {
            return "<" + translateArgName(suggestion.substring(1, suggestion.length() - 1)) + ">";
        }
        return suggestion;
    }

    /** 由解析路径生成参数说明，例如 "set day" 或 "set <时间>"（跳过前 skip 个节点，即根命令）。 */
    public static <S> String paramLine(List<ParsedCommandNode<S>> nodes, int skip) {
        StringBuilder sb = new StringBuilder();
        for (int i = skip; i < nodes.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            CommandNode<?> node = nodes.get(i).getNode();
            if (node instanceof ArgumentCommandNode<?, ?>) {
                sb.append('<').append(translateArgName(node.getName())).append('>');
            } else {
                sb.append(node.getName());
            }
        }
        return sb.toString();
    }
}
