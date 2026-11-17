package cn.autoforged.star_sword_mod_1785589476.client.gui;

import cn.autoforged.star_sword_mod_1785589476.client.ClientMechanicPresets;
import cn.autoforged.star_sword_mod_1785589476.client.ClientMechanicState;
import cn.autoforged.star_sword_mod_1785589476.client.ModKeybinds;
import cn.autoforged.star_sword_mod_1785589476.helper.MechanicRegistry;
import cn.autoforged.star_sword_mod_1785589476.helper.ModConstants;
import cn.autoforged.star_sword_mod_1785589476.networking.ModMessages;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 星辉剑机制面板：一张独立的白底小卡片（HMCL 风格）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>启动模式</b>（{@code startupMode=true}）：游戏在标题界面时自动弹出，替代标题界面，
 *       玩家必须点「启动游戏」才继续。此时没有服务器连接，值来自本地预设
 *       {@link ClientMechanicPresets}，首次登录后由客户端下发给服务端复核。</li>
 *   <li><b>游戏内模式</b>：按 M 打开，值来自服务端权威快照（{@link ClientMechanicState}），
 *       切换即时生效并可「删除存档项」。</li>
 * </ul>
 *
 * <p>样式：纯白卡片 + 浅灰标题栏 + 深灰正文 + 蓝色开关，固定小尺寸（360 宽），
 * 四周只叠一层很淡的纱，不调用 {@code renderBackground}（保留世界/背景画面），字体不描边。
 */
@OnlyIn(Dist.CLIENT)
public class MechanicPanelScreen extends Screen {

    // ===================== 配色（ARGB） =====================
    private static final int VEIL = 0x26000000;
    private static final int SHADOW = 0x0D000000;
    private static final int CARD_BG = 0xFFFFFFFF;
    private static final int HEADER_BG = 0xFFF5F6F8;
    private static final int BORDER = 0xFFD8DCE3;
    private static final int ROW_HOVER = 0xFFF2F6FF;
    private static final int TITLE = 0xFF2B2F36;
    private static final int SUBTITLE = 0xFF8A9099;
    private static final int LABEL = 0xFF2B2F36;
    private static final int DESC = 0xFF8A9099;
    private static final int DISABLED = 0xFFB0B4BA;
    private static final int TRACK_ON = 0xFF2F6FD0;
    private static final int TRACK_OFF = 0xFFC9CDD4;
    private static final int TRACK_DISABLED = 0xFFE4E6EA;
    private static final int KNOB = 0xFFFFFFFF;
    private static final int TAG = 0xFFB07A2A;
    private static final int CLOSE_HOVER = 0xFFE8ECF2;
    private static final int RESET_BG = 0xFFF0F2F6;
    private static final int RESET_BG_HOVER = 0xFFE2E8F2;
    private static final int RESET_TEXT = 0xFF5A6472;
    private static final int PRIMARY = 0xFF2F6FD0;
    private static final int PRIMARY_HOVER = 0xFF3D80E4;

    // ===================== 尺寸 =====================
    private static final int CARD_W = 360;
    private static final int HEADER_H = 30;
    private static final int ROW_H = 34;
    private static final int FOOTER_H = 20;
    private static final int STARTUP_FOOTER_H = 46;
    private static final int SWITCH_W = 26;
    private static final int SWITCH_H = 12;
    private static final int RESET_W = 24;
    private static final int RESET_H = 12;

    /** 启动模式：替代标题界面，点「启动游戏」才继续。 */
    private final boolean startupMode;
    private final List<MechanicRegistry.Def> rows = new ArrayList<>();

    private int cardX;
    private int cardY;
    private int cardH;
    private int footerH;
    private int contentTop;
    private int contentBottom;
    private int scrollOffset;
    private int refreshTicks;

    /** 游戏内模式：按 M 打开。 */
    public MechanicPanelScreen() {
        this(false);
    }

    public MechanicPanelScreen(boolean startupMode) {
        super(Component.translatable(startupMode
                ? "gui.star_sword_mod_1785589476.mech.startup_title"
                : "gui.star_sword_mod_1785589476.mech.title"));
        this.startupMode = startupMode;
    }

    public boolean isStartupMode() {
        return this.startupMode;
    }

    @Override
    protected void init() {
        this.rows.clear();
        this.rows.addAll(MechanicRegistry.all());

        this.footerH = this.startupMode ? STARTUP_FOOTER_H : FOOTER_H;
        int wanted = HEADER_H + this.rows.size() * ROW_H + this.footerH;
        this.cardH = Math.min(wanted, Math.max(140, this.height - 24));
        this.cardX = (this.width - CARD_W) / 2;
        this.cardY = (this.height - this.cardH) / 2;
        this.contentTop = this.cardY + HEADER_H;
        this.contentBottom = this.cardY + this.cardH - this.footerH;
        this.scrollOffset = 0;
        this.refreshTicks = 0;

        if (this.startupMode) {
            // 标题界面还没有连接：只读本地预设，不发包。
            return;
        }
        // 清掉上一局缓存，再从服务端拉权威值：面板不显示任何本地推断。
        ClientMechanicState.clear();
        if (this.minecraft.getConnection() != null) {
            ModMessages.sendMechanicSnapshotRequest();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.startupMode) {
            return;
        }
        // 与命令面板一致：剑不在手上就关闭。
        if (this.minecraft.player == null || !ModConstants.isHoldingSword(this.minecraft.player)) {
            this.onClose();
            return;
        }
        if (++this.refreshTicks >= 20) {
            this.refreshTicks = 0;
            if (this.minecraft.getConnection() != null) {
                ModMessages.sendMechanicSnapshotRequest();
            }
        }
    }

    /** 启动模式关屏 = 进入标题界面（不能把 screen 置空，否则主菜单会消失）；游戏内模式照常。 */
    @Override
    public void onClose() {
        if (this.startupMode) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new TitleScreen());
            return;
        }
        super.onClose();
    }

    /** 启动模式下 Esc 不允许跳过，必须点「启动游戏」。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return !this.startupMode;
    }

    // ===================== 输入 =====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.startupMode
                && ModKeybinds.OPEN_MECHANIC_PANEL.getKey().getType() == InputConstants.Type.KEYSYM
                && ModKeybinds.OPEN_MECHANIC_PANEL.getKey().getValue() == keyCode) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 启动模式的「启动游戏」按钮。
        if (this.startupMode && isInStartButton(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        // 标题栏右侧的关闭按钮（仅游戏内模式有）。
        if (!this.startupMode && isInCloseButton(mouseX, mouseY)) {
            this.onClose();
            return true;
        }
        int index = this.rowAt(mouseX, mouseY);
        if (index < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        MechanicRegistry.Def def = this.rows.get(index);
        if (isInResetButton(mouseX, mouseY, index)) {
            if (this.canEdit(def)) {
                if (this.startupMode) {
                    // 启动阶段没有存档可删：预设直接回到注册表默认值。
                    ClientMechanicPresets.set(def.id, def.defaultValue);
                } else {
                    // A 方案：删档同时把本地预设写成默认值。
                    // 否则会出现「删档回到默认 → 下次启动又被上次留下的预设推回旧值」。
                    ClientMechanicPresets.set(def.id, def.defaultValue);
                    ModMessages.sendMechanicReset(def.id);
                }
            }
            return true;
        }
        if (this.canEdit(def)) {
            this.applyToggle(def, !this.value(def));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, this.rows.size() * ROW_H - (this.contentBottom - this.contentTop));
        this.scrollOffset = Mth.clamp(this.scrollOffset - (int) (delta * 12), 0, maxScroll);
        return true;
    }

    /** 启动模式改本地预设；游戏内模式发设值包（幂等）。 */
    private void applyToggle(MechanicRegistry.Def def, boolean value) {
        if (this.startupMode) {
            ClientMechanicPresets.set(def.id, value);
            return;
        }
        ModMessages.sendMechanicSet(def.id, value);
    }

    /** 当前显示值：启动模式看预设，游戏内看服务端快照。 */
    private boolean value(MechanicRegistry.Def def) {
        return this.startupMode ? ClientMechanicPresets.get(def.id) : ClientMechanicState.value(def.id);
    }

    /** 启动模式没有权限信息（还没连接），一律允许调预设；游戏内以服务端下发的 editable 为准。 */
    private boolean canEdit(MechanicRegistry.Def def) {
        if (this.startupMode) {
            return true;
        }
        return ClientMechanicState.hasData() && ClientMechanicState.editable(def.id);
    }

    private boolean isInStartButton(double x, double y) {
        int w = 132;
        int h = 18;
        int bx = this.cardX + (CARD_W - w) / 2;
        int by = this.cardY + this.cardH - 30;
        return x >= bx && x <= bx + w && y >= by && y <= by + h;
    }

    private boolean isInCloseButton(double x, double y) {
        int bx = this.cardX + CARD_W - 22;
        int by = this.cardY + 8;
        return x >= bx - 4 && x <= bx + 12 && y >= by - 4 && y <= by + 12;
    }

    private boolean isInResetButton(double x, double y, int index) {
        int rowY = this.contentTop - this.scrollOffset + index * ROW_H;
        if (rowY + ROW_H <= this.contentTop || rowY >= this.contentBottom) {
            return false;
        }
        int rx = this.cardX + CARD_W - 12 - SWITCH_W - 6 - RESET_W;
        int ry = rowY + (ROW_H - RESET_H) / 2;
        return x >= rx && x <= rx + RESET_W && y >= ry && y <= ry + RESET_H;
    }

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < this.cardX || mouseX > this.cardX + CARD_W
                || mouseY < this.contentTop || mouseY >= this.contentBottom) {
            return -1;
        }
        int index = (int) ((mouseY - this.contentTop + this.scrollOffset) / ROW_H);
        return index >= 0 && index < this.rows.size() ? index : -1;
    }

    // ===================== 绘制 =====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, VEIL);
        this.drawShadow(graphics);

        graphics.fill(this.cardX, this.cardY, this.cardX + CARD_W, this.cardY + this.cardH, CARD_BG);
        this.drawBorder(graphics, this.cardX, this.cardY, CARD_W, this.cardH, BORDER);

        // 标题栏。
        graphics.fill(this.cardX + 1, this.cardY + 1, this.cardX + CARD_W - 1,
                this.cardY + HEADER_H, HEADER_BG);
        graphics.fill(this.cardX + 1, this.cardY + HEADER_H - 1, this.cardX + CARD_W - 1,
                this.cardY + HEADER_H, BORDER);
        graphics.drawString(this.font, this.title, this.cardX + 12, this.cardY + 6, TITLE, false);
        graphics.drawString(this.font,
                Component.translatable(this.startupMode
                        ? "gui.star_sword_mod_1785589476.mech.startup_subtitle"
                        : "gui.star_sword_mod_1785589476.mech.subtitle"),
                this.cardX + 12, this.cardY + 17, SUBTITLE, false);

        if (this.startupMode) {
            this.drawStartButton(graphics, mouseX, mouseY);
        } else {
            int closeX = this.cardX + CARD_W - 22;
            int closeY = this.cardY + 8;
            boolean closeHover = isInCloseButton(mouseX, mouseY);
            if (closeHover) {
                graphics.fill(closeX - 4, closeY - 4, closeX + 12, closeY + 12, CLOSE_HOVER);
            }
            graphics.drawString(this.font, "×", closeX, closeY, closeHover ? TITLE : SUBTITLE, false);
        }

        this.drawRows(graphics, mouseX, mouseY);

        // 底部提示。
        Component hint = Component.translatable(this.startupMode
                ? "gui.star_sword_mod_1785589476.mech.startup_hint"
                : "gui.star_sword_mod_1785589476.mech.hint");
        graphics.drawString(this.font, hint,
                this.cardX + (CARD_W - this.font.width(hint)) / 2,
                this.cardY + this.cardH - this.footerH + 3, SUBTITLE, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawStartButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int w = 132;
        int h = 18;
        int bx = this.cardX + (CARD_W - w) / 2;
        int by = this.cardY + this.cardH - 30;
        boolean hover = isInStartButton(mouseX, mouseY);
        graphics.fill(bx, by, bx + w, by + h, hover ? PRIMARY_HOVER : PRIMARY);
        Component label = Component.translatable("gui.star_sword_mod_1785589476.mech.start");
        graphics.drawString(this.font, label,
                bx + (w - this.font.width(label)) / 2, by + (h - this.font.lineHeight) / 2 + 1,
                0xFFFFFFFF, false);
    }

    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = this.contentTop - this.scrollOffset;
        graphics.enableScissor(this.cardX + 1, this.contentTop,
                this.cardX + CARD_W - 1, this.contentBottom);
        for (int i = 0; i < this.rows.size(); i++) {
            MechanicRegistry.Def def = this.rows.get(i);
            if (y + ROW_H > this.contentTop && y < this.contentBottom) {
                boolean hovered = mouseX >= this.cardX + 1 && mouseX <= this.cardX + CARD_W - 1
                        && mouseY >= Math.max(y, this.contentTop)
                        && mouseY < Math.min(y + ROW_H, this.contentBottom);
                boolean editable = this.canEdit(def);
                boolean value = this.value(def);
                boolean known = this.startupMode || ClientMechanicState.hasData();

                if (hovered && editable) {
                    graphics.fill(this.cardX + 1, y, this.cardX + CARD_W - 1, y + ROW_H, ROW_HOVER);
                }

                int textX = this.cardX + 12;
                Component name = Component.translatable(nameKey(def.id));
                graphics.drawString(this.font, name, textX, y + 6,
                        known && !editable ? DISABLED : LABEL, false);

                if (def.kind == MechanicRegistry.Kind.BOOL_GLOBAL) {
                    graphics.drawString(this.font,
                            Component.translatable("gui.star_sword_mod_1785589476.mech.global_tag"),
                            textX + this.font.width(name) + 6, y + 7, TAG, false);
                }

                String desc = Component.translatable(descKey(def.id)).getString();
                int descWidth = CARD_W - 24 - RESET_W - 6 - SWITCH_W - 8;
                graphics.drawString(this.font,
                        this.font.plainSubstrByWidth(desc, descWidth),
                        textX, y + 6 + this.font.lineHeight + 3,
                        known && !editable ? DISABLED : DESC, false);

                this.drawReset(graphics, mouseX, mouseY, i, y, def, editable);
                this.drawSwitch(graphics, this.cardX + CARD_W - 12 - SWITCH_W,
                        y + (ROW_H - SWITCH_H) / 2, value, known, editable);
            }
            y += ROW_H;
        }
        graphics.disableScissor();
    }

    /** 每行右侧的「删除存档项」小按钮；启动模式下它把预设恢复成默认值。 */
    private void drawReset(GuiGraphics graphics, int mouseX, int mouseY, int index, int rowY,
                           MechanicRegistry.Def def, boolean editable) {
        int rx = this.cardX + CARD_W - 12 - SWITCH_W - 6 - RESET_W;
        int ry = rowY + (ROW_H - RESET_H) / 2;
        boolean hover = isInResetButton(mouseX, mouseY, index);
        boolean usable = editable;
        graphics.fill(rx, ry, rx + RESET_W, ry + RESET_H,
                usable && hover ? RESET_BG_HOVER : RESET_BG);
        Component label = Component.translatable(this.startupMode
                ? "gui.star_sword_mod_1785589476.mech.reset_default"
                : "gui.star_sword_mod_1785589476.mech.reset");
        graphics.drawString(this.font, label,
                rx + (RESET_W - this.font.width(label)) / 2, ry + 2,
                usable ? RESET_TEXT : DISABLED, false);
    }

    /** HMCL 风格开关：圆角用「上下各缩 1px 的两层矩形」近似；开=蓝、关=灰、无权=更浅的灰。 */
    private void drawSwitch(GuiGraphics graphics, int x, int y, boolean on, boolean known, boolean editable) {
        boolean usable = known && editable;
        int track = !known ? TRACK_DISABLED : (on ? TRACK_ON : TRACK_OFF);
        if (!editable) {
            track = TRACK_DISABLED;
        }
        graphics.fill(x + 1, y, x + SWITCH_W - 1, y + SWITCH_H, track);
        graphics.fill(x, y + 1, x + SWITCH_W, y + SWITCH_H - 1, track);
        if (!usable) {
            return;
        }
        int knobX = on ? x + SWITCH_W - SWITCH_H + 1 : x + 1;
        int knobY = y + 1;
        int knobSize = SWITCH_H - 2;
        graphics.fill(knobX + 1, knobY, knobX + knobSize - 1, knobY + knobSize, KNOB);
        graphics.fill(knobX, knobY + 1, knobX + knobSize, knobY + knobSize - 1, KNOB);
    }

    private void drawShadow(GuiGraphics graphics) {
        for (int i = 1; i <= 3; i++) {
            graphics.fill(this.cardX - i, this.cardY + i, this.cardX + CARD_W + i,
                    this.cardY + this.cardH + i, SHADOW);
        }
    }

    private void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static String nameKey(String id) {
        return "gui.star_sword_mod_1785589476.mech." + id;
    }

    private static String descKey(String id) {
        return "gui.star_sword_mod_1785589476.mech." + id + ".desc";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
