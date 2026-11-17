package cn.autoforged.star_sword_mod_1785589476.client.render;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 星辉剑介绍框：右侧放一颗"进阶档"黑洞，左侧是原来的彩色文字。
 *
 * <p>整颗黑洞由 5 层贴图叠加而成（都由 {@code tools/GenFx.java} 程序化生成），
 * 没有实时后处理，也不碰主 framebuffer，因此在任何光影包 / Sodium / 低配显卡下行为一致：
 * <ol>
 *   <li>辉光（halo）：加色，尺寸最大，负责"周围发亮"；</li>
 *   <li>盘的后半：加色，压扁成椭圆并按时间旋转；</li>
 *   <li>事件视界（core）：普通 alpha 的纯黑圆——球体投影永远是正圆，所以这一层不压扁；</li>
 *   <li>光子环（ring）：加色，贴着视界的一圈亮边，是黑洞最标志性的部分；</li>
 *   <li>盘的前半：同一张盘再画一次，但用剪刀矩形只保留视界中心线以下的部分，
 *       于是近侧盘从黑洞前面压过去——这是整张图从"旋转的甜甜圈"变成"有前后关系"的关键；</li>
 *   <li>流光（streak）：加色，反向缓慢旋转，制造星点被拉成弧的感觉。</li>
 * </ol>
 *
 * <p>可用 {@code -Dstar_sword.fx.tooltip=false} 关掉，退回原来那版纯色边框介绍框。
 */
@OnlyIn(Dist.CLIENT)
public final class BlackHoleCard {

    private static final ResourceLocation TEX_DISK = tex("blackhole_disk");
    private static final ResourceLocation TEX_HALO = tex("blackhole_halo");
    private static final ResourceLocation TEX_RING = tex("blackhole_ring");
    private static final ResourceLocation TEX_CORE = tex("blackhole_core");
    private static final ResourceLocation TEX_STREAK = tex("blackhole_streak");

    /** 卡片内边距。 */
    private static final int PAD = 7;
    /** 右侧留给黑洞的栏宽。 */
    private static final int GUTTER = 92;
    /** 卡片最小尺寸，太小的框放不下黑洞。 */
    private static final int MIN_W = 168;
    private static final int MIN_H = 84;

    private static final int CARD_BG = 0xE6080A10;
    /** 盘的竖向压扁系数：越小越"侧视"，0.38 大约是 22° 倾角。视界与光子环不压扁。 */
    private static final float DISK_SQUASH = 0.38F;

    private static final int CARD_BORDER = 0x557FE9FF;
    private static final int CARD_BORDER_HOT = 0x99BFF4FF;
    private static final int DIVIDER = 0x337FE9FF;

    private BlackHoleCard() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(StarSwordMod.MOD_ID, "textures/gui/" + name + ".png");
    }

    /** 允许用系统属性关掉整个特效（出问题时的逃生口，与原版启动面板开关同一套做法）。 */
    private static boolean enabled() {
        String raw = System.getProperty("star_sword.fx.tooltip", "true");
        return !("0".equals(raw) || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw));
    }

    /**
     * 替换原版 tooltip 的全部绘制。
     *
     * @param lines 已经被 {@code ItemTooltipEvent} 改写成逐字彩色的组件
     */
    public static void render(GuiGraphics graphics, Font font, List<ClientTooltipComponent> lines,
                              int mouseX, int mouseY) {
        int textW = 0;
        int textH = 0;
        for (ClientTooltipComponent line : lines) {
            textW = Math.max(textW, line.getWidth(font));
            textH += line.getHeight();
        }

        int cardW = Math.max(textW + PAD * 2 + GUTTER, MIN_W);
        int cardH = Math.max(textH + PAD * 2, MIN_H);
        int x = Mth.clamp(mouseX, 2, Math.max(2, graphics.guiWidth() - cardW - 2));
        int y = Mth.clamp(mouseY, 2, Math.max(2, graphics.guiHeight() - cardH - 2));

        if (!enabled()) {
            renderFallback(graphics, font, lines, x, y, cardW, cardH);
            return;
        }

        long now = Util.getMillis();

        // ---- 卡片本体：投影 + 深色底 + 边框 ----
        graphics.fill(x + 3, y + 3, x + cardW + 3, y + cardH + 3, 0x50000000);
        graphics.fill(x, y, x + cardW, y + cardH, CARD_BG);
        drawBorder(graphics, x, y, cardW, cardH, now);

        // ---- 右侧黑洞 ----
        int gutterCx = x + cardW - PAD - GUTTER / 2;
        int gutterCy = y + cardH / 2;
        float diameter = Math.min(GUTTER, cardH) * 0.94F;
        drawBlackHole(graphics, gutterCx, gutterCy, diameter, now, x, y, cardW, cardH);

        // 文字区压一层从左到右渐隐的底色：星云会往左溢出，不压住的话文字对比度会被吃掉。
        // 1.20.1 的 fillGradient 只有纵向两色版本，横向用 32 条竖带拼出来。
        int scrimRight = x + cardW - PAD - GUTTER;
        int scrimSteps = 32;
        for (int i = 0; i < scrimSteps; i++) {
            int sx0 = x + (scrimRight - x) * i / scrimSteps;
            int sx1 = x + (scrimRight - x) * (i + 1) / scrimSteps;
            graphics.fill(sx0, y, sx1, y + cardH, lerpColor(CARD_BG, 0x00080A10, i / (float) (scrimSteps - 1)));
        }

        // 文字与黑洞之间的分隔线，避免文字压到发光的盘上。
        graphics.fill(x + cardW - PAD - GUTTER, y + PAD - 2,
                x + cardW - PAD - GUTTER + 1, y + cardH - PAD + 2, DIVIDER);

        // ---- 左侧文字 ----
        int textTop = y + (cardH - textH) / 2;
        for (ClientTooltipComponent line : lines) {
            line.renderText(font, x + PAD, textTop, graphics.pose().last().pose(), graphics.bufferSource());
            textTop += line.getHeight();
        }
    }

    /**
     * 画一颗黑洞。所有层共用同一个直径，因为几张贴图的内径是按同一个半径比例生成的
     * （视界 70/128、盘内缘 72/128、光子环 76/128），换直径时比例才不走样。
     */
    private static void drawBlackHole(GuiGraphics graphics, int cx, int cy, float diameter, long now,
                                      int cardX, int cardY, int cardW, int cardH) {
        // 旋转速度：盘 3.4°/s 左右，慢到能看清，快到不像静止图。
        float angle = now * 0.00060F;
        // 呼吸：整体尺寸 ±2%，让画面"活着"但不晃。
        float breathe = 1.0F + 0.02F * Mth.sin(now * 0.0016F);
        float d = diameter * breathe;

        FxDraw.additive();
        // 星云与辉光都比卡片大，必须裁到卡片内，否则会糊到界面其它地方。
        graphics.enableScissor(cardX, cardY, cardX + cardW, cardY + cardH);
        // 0) 会流动的星云：每 80ms 在 CPU 上重算一次，差速旋转把气团剪成螺旋。
        //    这一层是"流动感"的主要来源——静态贴图再怎么转都只是整块在转。
        ResourceLocation nebula = NebulaField.texture();
        if (nebula != null) {
            layer(graphics, nebula, cx, cy, d * 2.30F, 1.0F, -angle * 0.40F, 0x80FFFFFF);
        }
        // 1) 辉光
        layer(graphics, TEX_HALO, cx, cy, d * 2.30F, 1.0F, angle * 0.15F, 0x66BFEFFF);
        graphics.disableScissor();
        // 2) 盘的后半（压扁成椭圆，模拟倾角；视界仍然是正圆，只有盘被压扁）
        layer(graphics, TEX_DISK, cx, cy, d, DISK_SQUASH, angle, 0xF0FFFFFF);

        // 3) 事件视界：球体投影永远是正圆，压扁就露馅了
        FxDraw.alpha();
        layer(graphics, TEX_CORE, cx, cy, d, 1.0F, 0.0F, 0xF0000000);

        FxDraw.additive();
        // 4) 光子环
        layer(graphics, TEX_RING, cx, cy, d, 1.0F, 0.0F, 0xFFE8FFFF);
        // 5) 盘的前半：只保留中心线以下的区域，形成"近侧从前面压过去"的前后关系
        int scissorTop = (int) (cy - d * 0.02F);
        int scissorBottom = (int) (cy + d * 0.50F);
        graphics.enableScissor((int) (cx - d * 0.55F), scissorTop,
                (int) (cx + d * 0.55F), scissorBottom);
        layer(graphics, TEX_DISK, cx, cy, d, DISK_SQUASH, angle, 0x9AFFFFFF);
        graphics.disableScissor();
        // 6) 被拉成弧的星光
        layer(graphics, TEX_STREAK, cx, cy, d * 1.35F, DISK_SQUASH, -angle * 0.55F, 0x40BFEFFF);

        FxDraw.reset();
    }

    /** 在 (cx,cy) 处按 size 铺一层贴图，可压扁（squashY）与旋转。 */
    private static void layer(GuiGraphics graphics, ResourceLocation texture, float cx, float cy,
                              float size, float squashY, float angle, int argb) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0.0F);
        pose.mulPose(Axis.ZP.rotation(angle));
        pose.scale(1.0F, squashY, 1.0F);
        float half = size * 0.5F;
        FxDraw.texQuad(graphics, texture, -half, -half, half, half, argb);
        pose.popPose();
    }

    /** 四边 1 像素边框，顶部与底部亮一点，随呼吸轻微变化。 */
    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, long now) {
        float pulse = 0.5F + 0.5F * Mth.sin(now * 0.0022F);
        int top = lerpColor(CARD_BORDER, CARD_BORDER_HOT, pulse);
        graphics.fill(x, y, x + w, y + 1, top);
        graphics.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
        graphics.fill(x, y, x + 1, y + h, CARD_BORDER);
        graphics.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (int) Mth.lerp(t, a >>> 24 & 0xFF, b >>> 24 & 0xFF);
        int rr = (int) Mth.lerp(t, a >>> 16 & 0xFF, b >>> 16 & 0xFF);
        int gg = (int) Mth.lerp(t, a >>> 8 & 0xFF, b >>> 8 & 0xFF);
        int bb = (int) Mth.lerp(t, a & 0xFF, b & 0xFF);
        return aa << 24 | rr << 16 | gg << 8 | bb;
    }

    /** 特效关闭时的兜底：保留原来那版纯色卡片，保证任何时候都读得清。 */
    private static void renderFallback(GuiGraphics graphics, Font font, List<ClientTooltipComponent> lines,
                                       int x, int y, int cardW, int cardH) {
        graphics.fill(x + 3, y + 3, x + cardW + 3, y + cardH + 3, 0x50000000);
        graphics.fill(x, y, x + cardW, y + cardH, 0xF0100010);
        graphics.fill(x, y, x + cardW, y + 1, 0x505000FF);
        graphics.fill(x, y + cardH - 1, x + cardW, y + cardH, 0x505000FF);
        graphics.fill(x, y, x + 1, y + cardH, 0x505000FF);
        graphics.fill(x + cardW - 1, y, x + cardW, y + cardH, 0x505000FF);

        int textTop = y + PAD;
        for (ClientTooltipComponent line : lines) {
            int lineX = x + (cardW - line.getWidth(font)) / 2;
            line.renderText(font, lineX, textTop, graphics.pose().last().pose(), graphics.bufferSource());
            textTop += line.getHeight();
        }
    }
}
