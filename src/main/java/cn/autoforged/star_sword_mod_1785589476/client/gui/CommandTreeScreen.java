package cn.autoforged.star_sword_mod_1785589476.client.gui;

import cn.autoforged.star_sword_mod_1785589476.client.CommandDescHelper;
import cn.autoforged.star_sword_mod_1785589476.client.ModKeybinds;
import cn.autoforged.star_sword_mod_1785589476.helper.CommandPanelData;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * “更多”二级界面：按原版 TAB 补全的顺序（指令树的子节点顺序）以层级结构展示所有可用指令。
 * 每个条目显示来源模组；原版条目额外显示中文用途文本与所需权限等级（仅提示）。
 */
@OnlyIn(Dist.CLIENT)
public class CommandTreeScreen extends Screen {

    /** 服务端同步回来的指令树（由 CommandTreeSyncPacket 填充）。 */
    private static List<CommandPanelData.Node> pendingData;

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expandedKeys = new HashSet<>();
    private int scrollOffset;
    private int rowHeight;

    private static final class Row {
        final String key;
        final CommandPanelData.Node node;
        final int depth;
        final String rootName;
        /** 第二行说明文字（原版条目：用途 / 权限），非原版为 null。 */
        final String detail;

        Row(String key, CommandPanelData.Node node, int depth, String rootName, String detail) {
            this.key = key;
            this.node = node;
            this.depth = depth;
            this.rootName = rootName;
            this.detail = detail;
        }
    }

    public CommandTreeScreen() {
        super(Component.translatable("gui.star_sword_mod_1785589476.tree_title"));
    }

    /** 网络线程收包后调用：缓存数据并填充当前打开的界面。 */
    public static void acceptData(List<CommandPanelData.Node> roots) {
        pendingData = roots;
        if (Minecraft.getInstance().screen instanceof CommandTreeScreen screen) {
            screen.onData(roots);
        }
    }

    /** 供命令面板做名称级校验使用的服务端完整指令树（可能为 null，表示尚未同步）。 */
    public static List<CommandPanelData.Node> getCachedData() {
        return pendingData;
    }

    @Override
    protected void init() {
        this.rowHeight = this.font.lineHeight * 2 + 5;
        if (pendingData != null) {
            this.onData(pendingData);
        }
    }

    private void onData(List<CommandPanelData.Node> roots) {
        this.rows.clear();
        for (CommandPanelData.Node root : roots) {
            this.addRow(root, null, 0, root.name);
        }
    }

    private void addRow(CommandPanelData.Node node, String parentKey, int depth, String rootName) {
        String key = parentKey == null ? node.name : parentKey + "." + node.name;
        String detail = detailFor(node, key, depth, rootName);
        this.rows.add(new Row(key, node, depth, rootName, detail));
        if (this.expandedKeys.contains(key)) {
            for (CommandPanelData.Node child : node.children) {
                this.addRow(child, key, depth + 1, rootName);
            }
        }
    }

    /** 原版条目：用途文本（根命令读语言文件；子命令重建路径）+ 所需权限等级。 */
    private String detailFor(CommandPanelData.Node node, String key, int depth, String rootName) {
        if (!CommandPanelData.isVanilla(rootName)) {
            return null;
        }
        String permission = Component.translatable("gui.star_sword_mod_1785589476.permission",
                CommandPanelData.permissionOf(rootName)).getString();
        if (depth == 0) {
            String desc = CommandDescHelper.hasDescription(node.name) ? CommandDescHelper.description(node.name) : "";
            return desc + "   " + permission;
        }
        // 子命令：由 key（含根名）重建完整路径作为用途
        String path = key.substring(rootName.length()).replace('.', ' ').trim();
        StringBuilder usage = new StringBuilder("/" + rootName);
        if (!path.isEmpty()) {
            usage.append(' ').append(path);
        }
        StringBuilder args = new StringBuilder();
        for (CommandPanelData.Node child : node.children) {
            if (args.length() > 0) {
                args.append('|');
            }
            args.append(child.name.startsWith("<") ? CommandDescHelper.translateSuggestion(child.name) : child.name);
        }
        if (args.length() > 0) {
            usage.append(' ').append(args);
        }
        return Component.translatable("gui.star_sword_mod_1785589476.usage_hint").getString() + usage
                + "   " + permission;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ModKeybinds.OPEN_COMMAND_PANEL.getKey().getType() == InputConstants.Type.KEYSYM
                && ModKeybinds.OPEN_COMMAND_PANEL.getKey().getValue() == keyCode) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int index = (int) ((mouseY - 28) / this.rowHeight) + this.scrollOffset;
            if (index >= 0 && index < this.rows.size()) {
                Row row = this.rows.get(index);
                if (this.expandedKeys.remove(row.key)) {
                    this.rebuild();
                } else if (!row.node.children.isEmpty()) {
                    this.expandedKeys.add(row.key);
                    this.rebuild();
                }
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (ModKeybinds.OPEN_COMMAND_PANEL.getKey().getType() == InputConstants.Type.MOUSE
                && ModKeybinds.OPEN_COMMAND_PANEL.getKey().getValue() == button) {
            this.onClose();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, this.rows.size() * this.rowHeight - (this.height - 28));
        this.scrollOffset = Mth.clamp(this.scrollOffset - (int) (delta * 14), 0, maxScroll);
        return true;
    }

    private void rebuild() {
        this.rows.clear();
        if (pendingData != null) {
            for (CommandPanelData.Node root : pendingData) {
                this.addRow(root, null, 0, root.name);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);
        graphics.drawString(this.font, Component.translatable("gui.star_sword_mod_1785589476.tree_hint"),
                4, 16, 0xA0A0A0);

        int viewTop = 28;
        int viewBottom = this.height;
        if (this.rows.isEmpty()) {
            graphics.drawString(this.font,
                    Component.translatable("gui.star_sword_mod_1785589476.tree_loading"),
                    this.width / 2 - this.font.width(Component.translatable("gui.star_sword_mod_1785589476.tree_loading")) / 2,
                    this.height / 2 - 4, 0x808080);
        }
        graphics.enableScissor(0, viewTop, this.width, viewBottom - viewTop);
        int y = viewTop - this.scrollOffset;
        for (Row row : this.rows) {
            if (y + this.rowHeight > viewTop) {
                int indent = row.depth * 10;
                String marker = row.node.children.isEmpty() ? "" : (this.expandedKeys.contains(row.key) ? "▼ " : "▶ ");
                boolean hovered = mouseY >= y && mouseY < y + this.rowHeight;
                if (hovered) {
                    graphics.fill(0, y, this.width, y + this.rowHeight, 0x30FFFFFF);
                }
                graphics.drawString(this.font, marker + row.node.name, 4 + indent, y + 3, 0xFFFFFF);
                String source = sourceText(row.node);
                graphics.drawString(this.font, source,
                        4 + indent + this.font.width(marker + row.node.name) + 6, y + 3, 0x808080);
                if (row.detail != null) {
                    graphics.drawString(this.font,
                            this.font.plainSubstrByWidth(row.detail, this.width - 16 - indent),
                            4 + indent, y + 3 + this.font.lineHeight + 1, 0xC0C0C0);
                }
            }
            y += this.rowHeight;
        }
        graphics.disableScissor();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String sourceText(CommandPanelData.Node node) {
        if (CommandPanelData.SOURCE_VANILLA.equals(node.source)) {
            return Component.translatable("gui.star_sword_mod_1785589476.source_vanilla").getString();
        }
        if (CommandPanelData.SOURCE_UNKNOWN.equals(node.source)) {
            return Component.translatable("gui.star_sword_mod_1785589476.source_unknown").getString();
        }
        return Component.translatable("gui.star_sword_mod_1785589476.source_mod", node.source).getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
