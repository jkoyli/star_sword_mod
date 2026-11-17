package cn.autoforged.star_sword_mod_1785589476.client.gui;

import cn.autoforged.star_sword_mod_1785589476.client.CommandDescHelper;
import cn.autoforged.star_sword_mod_1785589476.client.CommandPanelStore;
import cn.autoforged.star_sword_mod_1785589476.client.ModKeybinds;
import cn.autoforged.star_sword_mod_1785589476.helper.CommandPanelData;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.CommandExecutePacket;
import cn.autoforged.star_sword_mod_1785589476.networking.packet.CommandTreeRequestPacket;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 星辉剑命令面板：命令输入 + 中文功能描述 / 用法提示 + 历史记录 + 随机快捷根命令。
 * 布局：屏幕左侧一列为历史记录；顶部（左到右从历史列右缘起、上到顶、下到屏幕 1/5）
 * 为命令输入区，输入行尺寸与原版命令方块的输入行一致（300x20）并居中。
 */
@OnlyIn(Dist.CLIENT)
public class CommandPanelScreen extends Screen {

    /** 与原版命令方块输入行相同的尺寸（见 AbstractCommandBlockEditScreen）。 */
    private static final int COMMAND_LINE_WIDTH = 300;
    private static final int COMMAND_LINE_HEIGHT = 20;

    private static final int QUICK_COUNT = 6;
    private static final int SLOT_COLUMNS = 3;
    /** 快捷根命令随机池（约 20 条常用命令，代表 TAB 补全的初始层级）。 */
    private static final List<String> QUICK_POOL = List.of(
            "time", "gamemode", "gamerule", "give", "tp", "summon", "effect", "kill",
            "weather", "difficulty", "experience", "clear", "enchant", "fill", "clone",
            "setblock", "say", "title", "particle", "playsound");

    private int historyWidth;
    private int stripBottom;
    private int infoTop;
    private int infoBottom;
    private int quickTop;
    private int quickBottom;

    private EditBox commandEdit;
    private CommandSuggestions commandSuggestions;
    private Button confirmButton;
    private Button clearHistoryButton;
    private Button moreButton;

    private final List<String> history = new ArrayList<>();
    private int historyRowHeight;

    private final List<String> quickCommands = new ArrayList<>();
    private final List<int[]> quickRects = new ArrayList<>();

    /** 描述 / 用法提示行（无内容时不显示，即格式错误无任何提示）。 */
    private final List<String> infoLines = new ArrayList<>();

    public CommandPanelScreen() {
        super(Component.translatable("gui.star_sword_mod_1785589476.title"));
    }

    @Override
    protected void init() {
        this.historyWidth = Mth.clamp(this.width * 25 / 100, 140, 320);
        this.stripBottom = Math.max(96, this.height / 5);

        int inputX = this.historyWidth + (this.width - this.historyWidth - COMMAND_LINE_WIDTH) / 2;
        if (inputX < this.historyWidth + 2) {
            inputX = this.historyWidth + 2;
        }
        int inputY = this.stripBottom / 2 - COMMAND_LINE_HEIGHT / 2;

        this.commandEdit = new EditBox(this.font, inputX, inputY, COMMAND_LINE_WIDTH, COMMAND_LINE_HEIGHT,
                Component.translatable("gui.star_sword_mod_1785589476.command"));
        this.commandEdit.setMaxLength(32500);
        this.commandEdit.setResponder(s -> {
            this.updateInfoLines();
            if (this.commandSuggestions != null) {
                this.commandSuggestions.updateCommandInfo();
            }
        });
        this.addWidget(this.commandEdit);

        this.confirmButton = Button.builder(
                        Component.translatable("gui.star_sword_mod_1785589476.confirm"),
                        b -> this.executeCurrentCommand())
                .bounds(inputX + COMMAND_LINE_WIDTH + 4, inputY, 60, COMMAND_LINE_HEIGHT)
                .build();
        this.addRenderableWidget(this.confirmButton);

        this.clearHistoryButton = Button.builder(
                        Component.translatable("gui.star_sword_mod_1785589476.clear_history"),
                        b -> {
                            CommandPanelStore.clearHistory();
                            this.history.clear();
                            this.history.addAll(CommandPanelStore.getHistory());
                        })
                .bounds(this.historyWidth - 74, 3, 70, 16)
                .build();
        this.addRenderableWidget(this.clearHistoryButton);

        this.infoTop = this.stripBottom + 4;
        this.infoBottom = this.infoTop + this.font.lineHeight * 4 + 8;
        this.quickTop = this.infoBottom;
        this.quickBottom = this.quickTop + 100;

        int areaX = this.historyWidth + 4;
        int areaWidth = this.width - areaX - 4;
        int cellW = areaWidth / 4;
        int slotW = cellW - 4;
        int slotH = 46;
        int gapX = 4;
        int gapY = 4;

        this.quickRects.clear();
        for (int i = 0; i < QUICK_COUNT; i++) {
            int col = i % SLOT_COLUMNS;
            int row = i / SLOT_COLUMNS;
            this.quickRects.add(new int[]{
                    areaX + col * cellW + gapX,
                    this.quickTop + row * (slotH + gapY) + gapY,
                    slotW,
                    slotH});
        }
        this.moreButton = Button.builder(
                        Component.translatable("gui.star_sword_mod_1785589476.more"),
                        b -> this.openTreeScreen())
                .bounds(areaX + cellW * 3 + gapX, this.quickTop + (slotH + gapY) + gapY, slotW, slotH)
                .build();
        this.addRenderableWidget(this.moreButton);

        this.commandSuggestions = new CommandSuggestions(this.minecraft, this, this.commandEdit, this.font,
                true, true, 0, 7, false, Integer.MIN_VALUE);
        this.commandSuggestions.setAllowSuggestions(true);
        this.commandSuggestions.updateCommandInfo();
        this.setInitialFocus(this.commandEdit);
        this.commandEdit.setFocused(true);

        // 请求一次完整指令树，供“用法提示”在客户端无对应指令时做名称级校验
        ModMessages.sendToServer(new CommandTreeRequestPacket());

        this.history.clear();
        this.history.addAll(CommandPanelStore.getHistory());
        this.historyRowHeight = this.font.lineHeight + 4;

        this.pickQuickCommands();
        this.updateInfoLines();
    }

    /** 每次打开界面随机展示 6 条快捷命令；已锁定的命令始终保留，不被随机替换。 */
    private void pickQuickCommands() {
        this.quickCommands.clear();
        List<String> pool = new ArrayList<>(QUICK_POOL);
        for (String locked : CommandPanelStore.getLocked()) {
            if (pool.remove(locked)) {
                this.quickCommands.add(locked);
                if (this.quickCommands.size() >= QUICK_COUNT) {
                    break;
                }
            }
        }
        Collections.shuffle(pool, new Random());
        for (String name : pool) {
            if (this.quickCommands.size() >= QUICK_COUNT) {
                break;
            }
            this.quickCommands.add(name);
        }
    }

    private void executeCurrentCommand() {
        String command = this.commandEdit.getValue().trim();
        if (command.isEmpty()) {
            return;
        }
        CommandPanelStore.addHistory(command);
        this.history.clear();
        this.history.addAll(CommandPanelStore.getHistory());
        ModMessages.sendToServer(new CommandExecutePacket(command));
    }

    private void openTreeScreen() {
        ModMessages.sendToServer(new CommandTreeRequestPacket());
        this.minecraft.setScreen(new CommandTreeScreen());
    }

    /**
     * 解析当前输入：
     * - 完整且正确的原版命令 -> 显示中文功能描述（命令名称 + 参数说明 + 所需权限）；
     * - 合法前缀（未输完）的原版命令 -> 显示动态用法提示（子参数说明）；
     * - 格式错误 -> 不显示任何提示。
     * 客户端 dispatcher 只包含玩家权限内可见的指令，因此解析失败时再用服务端同步的
     * 完整指令树做一次名称级校验（权限不足时也能显示说明）。
     */
    private void updateInfoLines() {
        this.infoLines.clear();
        String input = this.commandEdit.getValue().trim();
        if (input.isEmpty() || this.minecraft.player == null) {
            return;
        }
        ClientPacketListener connection = this.minecraft.getConnection();
        if (connection == null) {
            return;
        }
        StringReader reader = new StringReader(input);
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }
        ParseResults<SharedSuggestionProvider> parse =
                connection.getCommands().parse(reader, connection.getSuggestionsProvider());
        if (parse.getExceptions().isEmpty()) {
            List<ParsedCommandNode<SharedSuggestionProvider>> nodes = parse.getContext().getNodes();
            if (!nodes.isEmpty() && nodes.get(0).getNode() instanceof LiteralCommandNode<?> literal) {
                String root = literal.getLiteral();
                if (CommandPanelData.isVanilla(root)) {
                    if (!parse.getReader().canRead()) {
                        this.showFullDescription(root, CommandDescHelper.paramLine(nodes, 1));
                    } else {
                        this.showUsageHint(root, parse);
                    }
                    return;
                }
            }
        }
        this.updateInfoLinesFromTree(input);
    }

    /** 完整命令：命令名称 + 功能说明 + 参数说明 + 所需权限等级。 */
    private void showFullDescription(String root, String params) {
        this.infoLines.add(Component.translatable("gui.star_sword_mod_1785589476.cmd_name", "/" + root).getString());
        this.infoLines.add(Component.translatable("gui.star_sword_mod_1785589476.desc_title")
                + CommandDescHelper.description(root));
        if (!params.isEmpty()) {
            this.infoLines.add(Component.translatable("gui.star_sword_mod_1785589476.param") + params);
        }
        Integer permission = CommandPanelData.permissionOf(root);
        if (permission != null) {
            this.infoLines.add(Component.translatable("gui.star_sword_mod_1785589476.permission", permission).getString());
        }
    }

    /** 合法前缀：用法提示（当前节点下可用的子参数）。 */
    private void showUsageHint(String root, ParseResults<SharedSuggestionProvider> parse) {
        // 解析用的 reader 已去掉开头的 "/"，光标必须换算成该 reader 的坐标系
        SuggestionContext<SharedSuggestionProvider> context =
                parse.getContext().findSuggestionContext(parse.getContext().getRange().getEnd());
        if (context == null) {
            return;
        }
        Map<CommandNode<SharedSuggestionProvider>, String> usage = this.minecraft.getConnection()
                .getCommands().getSmartUsage(context.parent, this.minecraft.getConnection().getSuggestionsProvider());
        if (usage.isEmpty()) {
            return;
        }
        StringBuilder hint = new StringBuilder("/" + root);
        String path = CommandDescHelper.paramLine(parse.getContext().getNodes(), 1);
        if (!path.isEmpty()) {
            hint.append(' ').append(path);
        }
        hint.append(' ');
        boolean first = true;
        for (String value : usage.values()) {
            if (!first) {
                hint.append('|');
            }
            hint.append(CommandDescHelper.translateSuggestion(value));
            first = false;
        }
        this.infoLines.add(Component.translatable("gui.star_sword_mod_1785589476.usage_hint").getString() + hint);
    }

    /** 服务端同步指令树回退：按“根命令 -> 子命令”逐级校验输入并生成说明。 */
    private void updateInfoLinesFromTree(String input) {
        List<CommandPanelData.Node> roots = CommandTreeScreen.getCachedData();
        if (roots == null) {
            return;
        }
        String[] tokens = input.split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return;
        }
        String first = tokens[0];
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        CommandPanelData.Node root = null;
        for (CommandPanelData.Node node : roots) {
            if (node.name.equals(first)) {
                root = node;
                break;
            }
        }
        if (root == null || !CommandPanelData.isVanilla(first)) {
            return;
        }
        StringBuilder params = new StringBuilder();
        CommandPanelData.Node current = root;
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            CommandPanelData.Node literalMatch = null;
            CommandPanelData.Node argumentMatch = null;
            for (CommandPanelData.Node child : current.children) {
                if (!child.name.startsWith("<")) {
                    if (child.name.equals(token)) {
                        literalMatch = child;
                    }
                } else if (argumentMatch == null) {
                    argumentMatch = child;
                }
            }
            if (literalMatch != null) {
                if (params.length() > 0) {
                    params.append(' ');
                }
                params.append(literalMatch.name);
                current = literalMatch;
            } else if (argumentMatch != null) {
                if (params.length() > 0) {
                    params.append(' ');
                }
                params.append('<')
                        .append(CommandDescHelper.translateArgName(
                                argumentMatch.name.substring(1, argumentMatch.name.length() - 1)))
                        .append('>');
                current = argumentMatch;
                if (current.children.isEmpty()) {
                    // 参数节点没有子命令时，剩余输入全部属于该参数
                    i = tokens.length;
                    break;
                }
            } else {
                return;
            }
        }
        this.showFullDescription(first, params.toString());
    }

    @Override
    public void tick() {
        super.tick();
        // 星辉剑不在手上时自动关闭面板
        if (this.minecraft.player == null || !ModConstants.isHoldingSword(this.minecraft.player)) {
            this.onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 绑定键为键盘键时，按下面板开关键关闭面板（不把该键输入到输入框）
        if (ModKeybinds.OPEN_COMMAND_PANEL.getKey().getType() == InputConstants.Type.KEYSYM
                && ModKeybinds.OPEN_COMMAND_PANEL.getKey().getValue() == keyCode) {
            this.onClose();
            return true;
        }
        if (this.commandSuggestions.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            this.executeCurrentCommand();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.commandSuggestions.mouseScrolled(delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 快捷指令：Shift + 右键锁定 / 解锁；左键填入前缀并自动补全空格
        for (int i = 0; i < this.quickRects.size(); i++) {
            int[] rect = this.quickRects.get(i);
            if (i < this.quickCommands.size() && isIn(mouseX, mouseY, rect)) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && Screen.hasShiftDown()) {
                    CommandPanelStore.toggleLocked(this.quickCommands.get(i));
                    this.pickQuickCommands();
                    return true;
                }
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    this.commandEdit.setValue(this.quickCommands.get(i) + " ");
                    this.commandEdit.setFocused(true);
                    this.setFocused(this.commandEdit);
                    return true;
                }
            }
        }
        // 历史记录：点击复制到输入框（仅复制，不执行）
        if (mouseX < this.historyWidth) {
            int index = (int) ((mouseY - 24) / this.historyRowHeight);
            if (index >= 0 && index < this.history.size()) {
                this.commandEdit.setValue(this.history.get(index));
                this.commandEdit.setFocused(true);
                this.setFocused(this.commandEdit);
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // 绑定键为鼠标键时，点击面板空白处关闭面板
        if (ModKeybinds.OPEN_COMMAND_PANEL.getKey().getType() == InputConstants.Type.MOUSE
                && ModKeybinds.OPEN_COMMAND_PANEL.getKey().getValue() == button) {
            this.onClose();
            return true;
        }
        return true;
    }

    private static boolean isIn(double x, double y, int[] rect) {
        return x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 历史记录列（屏幕左边一列，多行显示，数量随屏幕高度自动适配）
        graphics.fill(0, 0, this.historyWidth, this.height, 0x90000000);
        graphics.drawString(this.font, Component.translatable("gui.star_sword_mod_1785589476.history"),
                4, 5, 0xFFFFFF);
        int y = 24;
        for (String entry : this.history) {
            if (y + this.historyRowHeight > this.height) {
                break;
            }
            if (mouseX < this.historyWidth && mouseY >= y && mouseY < y + this.historyRowHeight) {
                graphics.fill(0, y, this.historyWidth, y + this.historyRowHeight, 0x50FFFFFF);
            }
            graphics.drawString(this.font, this.font.plainSubstrByWidth(entry, this.historyWidth - 10),
                    4, y + 2, 0xA0A0A0);
            y += this.historyRowHeight;
        }

        // 命令输入区（左起历史列右缘、上到屏幕顶、下到屏幕 1/5）
        graphics.fill(this.historyWidth, 0, this.width, this.stripBottom, 0x40000000);

        // 描述 / 用法提示区（格式错误时为空，不渲染任何提示）
        if (!this.infoLines.isEmpty()) {
            graphics.fill(this.historyWidth, this.infoTop, this.width, this.infoBottom, 0x50000000);
            int iy = this.infoTop + 4;
            for (String line : this.infoLines) {
                if (iy + this.font.lineHeight > this.infoBottom) {
                    break;
                }
                graphics.drawString(this.font,
                        this.font.plainSubstrByWidth(line, this.width - this.historyWidth - 14),
                        this.historyWidth + 6, iy, 0xFFFFFF);
                iy += this.font.lineHeight + 1;
            }
        }

        // 快捷根命令区 + “更多”按钮（区域右下角）
        graphics.fill(this.historyWidth, this.quickTop, this.width, this.quickBottom, 0x60000000);
        for (int i = 0; i < this.quickRects.size(); i++) {
            int[] rect = this.quickRects.get(i);
            if (i >= this.quickCommands.size()) {
                break;
            }
            String name = this.quickCommands.get(i);
            boolean locked = CommandPanelStore.isLocked(name);
            boolean hovered = isIn(mouseX, mouseY, rect);
            graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3],
                    hovered ? 0x70333AFF : 0x70202020);
            if (locked) {
                graphics.fill(rect[0], rect[1], rect[0] + rect[2], rect[1] + 2, 0xFFE0A020);
            }
            graphics.drawString(this.font, (locked ? "[锁] " : "") + name, rect[0] + 3, rect[1] + 3, 0xFFFFFF);
            String desc = CommandDescHelper.hasDescription(name) ? CommandDescHelper.description(name) : "";
            graphics.drawString(this.font, this.font.plainSubstrByWidth(desc, rect[2] - 6),
                    rect[0] + 3, rect[1] + 3 + this.font.lineHeight + 1, 0xA0A0A0);
            if (hovered) {
                this.drawHint(graphics,
                        Component.translatable(Screen.hasShiftDown()
                                ? "gui.star_sword_mod_1785589476.unlock_hint"
                                : "gui.star_sword_mod_1785589476.lock_hint"),
                        mouseX, mouseY);
            }
        }

        this.commandEdit.render(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.commandSuggestions.render(graphics, mouseX, mouseY);
    }

    private void drawHint(GuiGraphics graphics, Component text, int x, int y) {
        int w = this.font.width(text) + 6;
        int h = this.font.lineHeight + 4;
        int tx = Mth.clamp(x + 8, 0, this.width - w - 2);
        int ty = Mth.clamp(y + 8, 0, this.height - h - 2);
        graphics.fill(tx, ty, tx + w, ty + h, 0xF0101010);
        graphics.fill(tx, ty, tx + w, ty + 1, 0xFFAAAAAA);
        graphics.fill(tx, ty + h - 1, tx + w, ty + h, 0xFFAAAAAA);
        graphics.fill(tx, ty, tx + 1, ty + h, 0xFFAAAAAA);
        graphics.fill(tx + w - 1, ty, tx + w, ty + h, 0xFFAAAAAA);
        graphics.drawString(this.font, text, tx + 3, ty + 2, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
