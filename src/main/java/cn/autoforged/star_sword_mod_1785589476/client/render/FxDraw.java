package cn.autoforged.star_sword_mod_1785589476.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 界面上画"加色/半透明四边形"的最小工具。
 *
 * <p>为什么不用 {@link GuiGraphics#blit}：{@code blit} 走的是 {@code position_tex} 着色器，
 * 没有逐顶点颜色，既不能调透明度也不能调色，做不了"同一张盘叠 3 层不同亮度"的黑洞。
 * 这里改用 {@code position_tex_color}（原版 GUI 画物品/文字用的就是它），逐顶点带 ARGB，
 * 一层纹理 = 一个 quad = 一次立即绘制，混合状态在每次绘制前设置，互不影响。
 *
 * <p>只有客户端可见，所有调用都发生在 Screen 的 render 阶段，不碰世界渲染状态。
 */
public final class FxDraw {

    private FxDraw() {
    }

    /** 加色混合：黑洞的盘、辉光、光子环都靠它叠出"发光"感。 */
    public static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    /** 普通 alpha 混合：事件视界（纯黑遮住背后的盘）必须用它。 */
    public static void alpha() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    /** 还原成原版 GUI 的默认状态，避免污染同帧后续的原版绘制。 */
    public static void reset() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 把整张纹理铺到当前 pose 下的 (x0,y0)-(x1,y1) 矩形上。
     *
     * <p>坐标是 pose 空间，所以调用方可以先 translate/rotate/scale 再调用，
     * 这一点是黑洞"倾斜的盘"能画出来的前提。
     *
     * @param argb 逐顶点颜色，alpha 直接决定这层的亮度
     */
    public static void texQuad(GuiGraphics graphics, ResourceLocation texture,
                               float x0, float y0, float x1, float y1, int argb) {
        float a = (argb >>> 24 & 0xFF) / 255.0F;
        float r = (argb >>> 16 & 0xFF) / 255.0F;
        float g = (argb >>> 8 & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(matrix, x0, y1, 0.0F).uv(0.0F, 1.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, 0.0F).uv(1.0F, 1.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, 0.0F).uv(1.0F, 0.0F).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y0, 0.0F).uv(0.0F, 0.0F).color(r, g, b, a).endVertex();
        Tesselator.getInstance().end();
    }
}
