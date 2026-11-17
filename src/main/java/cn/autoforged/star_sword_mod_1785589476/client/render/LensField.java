package cn.autoforged.star_sword_mod_1785589476.client.render;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Optional;

/**
 * 黑洞背后的深空照片 + 伪引力透镜。
 *
 * <p>为什么要在 CPU 上逐像素重算，而不是直接 blit 一张图：
 * 界面是二维的，没法用屏幕空间后处理去弯折背景，所以"扭曲"只能靠**采样时把 UV 掰弯**——
 * 对每个输出像素，算出它到黑洞中心的距离，把采样半径按 1/r² 推到外面去，
 * 于是靠近视界的背景被压缩成环，远处的几乎不动。这就是引力透镜最像的那一部分。
 *
 * <p>另外两件事：
 * <ul>
 *   <li><b>色散</b>：红/蓝通道用不同的偏折强度采样，环附近会自然出现彩虹边；</li>
 *   <li><b>无缝流动</b>：素材照片不可平铺，用**镜像延拓**（mirror wrap）就能无限接下去，
 *       边缘天然对齐，再叠一个缓慢漂移，看起来就是深空在移动。</li>
 * </ul>
 *
 * <p>生成分辨率只有 160×80，被拉伸到卡片大小——背景本来就是虚的，这样足够且便宜
 * （每次约 1ms，90ms 一次，只在介绍框打开时跑）。任何异常都会永久关掉这一层。
 */
@OnlyIn(Dist.CLIENT)
public final class LensField {

    /** 生成分辨率。拉伸到卡片上，低分辨率反而更"虚"，接近景深。 */
    private static final int TW = 160;
    private static final int TH = 80;

    private static final long UPDATE_MS = 90L;

    /** 照片相对卡片宽度的放大倍数：越大越"放大放进去"。 */
    private static final double ZOOM = 1.7;

    /** 透镜强度（以视界半径为单位）。0.55 大约能把背景压出一个明显的环。 */
    private static final double LENS = 0.85;

    /** 色散强度：红蓝通道偏折差的比例。 */
    private static final double DISPERSION = 0.06;

    /** 漂移速度（源图像素/秒）。 */
    private static final double DRIFT_X = 9.0;
    private static final double DRIFT_Y = 4.0;

    private static final ResourceLocation LOCATION =
            new ResourceLocation(StarSwordMod.MOD_ID, "dynamic/blackhole_lens");
    private static final ResourceLocation SOURCE =
            new ResourceLocation(StarSwordMod.MOD_ID, "textures/gui/blackhole_deepspace.png");

    private static DynamicTexture texture;
    private static int[] source;
    private static int sourceW;
    private static int sourceH;
    private static boolean disabled;

    private static long lastUpdate = Long.MIN_VALUE;
    private static float lastCardW = -1.0F;
    private static float lastCardH = -1.0F;
    private static float lastHoleX = -1.0F;
    private static float lastHoleY = -1.0F;
    private static float lastShadow = -1.0F;

    private LensField() {
    }

    /**
     * 取当前帧的（已扭曲）背景贴图。
     *
     * @param cardW   卡片宽（GUI 像素）
     * @param cardH   卡片高
     * @param holeX   黑洞中心在卡片内的 x
     * @param holeY   黑洞中心在卡片内的 y
     * @param shadowR 事件视界半径（GUI 像素），用作透镜的尺度
     * @return 可直接铺在卡片矩形上的贴图；{@code null} 表示这一层不可用
     */
    public static ResourceLocation texture(float cardW, float cardH,
                                           float holeX, float holeY, float shadowR) {
        if (disabled) {
            return null;
        }
        try {
            if (source == null) {
                loadSource();
                if (source == null) {
                    disabled = true;
                    return null;
                }
            }
            if (texture == null) {
                texture = new DynamicTexture(new NativeImage(TW, TH, true));
                Minecraft.getInstance().getTextureManager().register(LOCATION, texture);
            }

            long now = Util.getMillis();
            boolean geometryChanged = Math.abs(cardW - lastCardW) > 0.5F
                    || Math.abs(cardH - lastCardH) > 0.5F
                    || Math.abs(holeX - lastHoleX) > 0.5F
                    || Math.abs(holeY - lastHoleY) > 0.5F
                    || Math.abs(shadowR - lastShadow) > 0.5F;

            if (geometryChanged || now - lastUpdate >= UPDATE_MS) {
                fill(texture.getPixels(), now, cardW, cardH, holeX, holeY, shadowR);
                texture.upload();
                lastUpdate = now;
                lastCardW = cardW;
                lastCardH = cardH;
                lastHoleX = holeX;
                lastHoleY = holeY;
                lastShadow = shadowR;
            }
            return LOCATION;
        } catch (Throwable t) {
            // 只在第一次失败时打一次完整堆栈：特效坏掉必须能从日志里看出来，
            // 而不是变成"背景莫名其妙是黑的"。
            disabled = true;
            System.err.println("[star_sword] LensField disabled after failure:");
            t.printStackTrace();
            return null;
        }
    }

    private static void loadSource() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Optional<Resource> resource = mc.getResourceManager().getResource(SOURCE);
            if (resource.isEmpty()) {
                System.err.println("[star_sword] LensField: resource not found: " + SOURCE);
                return;
            }
            try (InputStream in = resource.get().open()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    return;
                }
                sourceW = image.getWidth();
                sourceH = image.getHeight();
                source = image.getRGB(0, 0, sourceW, sourceH, null, 0, sourceW);
            }
        } catch (Throwable ignored) {
            // 交给调用方按 null 处理
        }
    }

    /** 逐像素生成一帧：采样 → 透镜位移 → 色散 → 镜像平铺。 */
    private static void fill(NativeImage image, long nowMs, float cardW, float cardH,
                             float holeX, float holeY, float shadowR) {
        double t = nowMs / 1000.0;
        double scale = sourceW / (cardW * ZOOM);
        double driftX = t * DRIFT_X;
        double driftY = t * DRIFT_Y;
        double shadow = Math.max(1.0, shadowR);

        for (int j = 0; j < TH; j++) {
            for (int i = 0; i < TW; i++) {
                double px = (i + 0.5) / TW * cardW;
                double py = (j + 0.5) / TH * cardH;

                double dx = px - holeX;
                double dy = py - holeY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double ux = dist > 1e-4 ? dx / dist : 0.0;
                double uy = dist > 1e-4 ? dy / dist : 0.0;

                double rr = dist / shadow;
                // 偏折集中成贴着视界的一圈（爱因斯坦环）：环外几乎没有畸变，
                // 环内背景被强行拉开，于是视界边缘会出现一圈被压缩的星空。
                double ring = Math.exp(-Math.pow((rr - 1.0) / 0.55, 2.0));
                double bend = rr > 1.0 ? LENS * shadow * ring : 0.0;

                double sr = sampleChannel(px, py, ux, uy, bend * (1.0 + DISPERSION),
                        scale, driftX, driftY, holeX, holeY, 16);
                double sg = sampleChannel(px, py, ux, uy, bend,
                        scale, driftX, driftY, holeX, holeY, 8);
                double sb = sampleChannel(px, py, ux, uy, bend * (1.0 - DISPERSION),
                        scale, driftX, driftY, holeX, holeY, 0);

                // 视界内部无所谓（会被纯黑圆盖住），外面往边缘轻微压暗，聚焦到黑洞
                double vignette = 0.82 * (1.0 - 0.30 * smooth(0.55, 1.6, rr < 1.0 ? 1.0 : rr));
                int r = (int) (Mth.clamp(sr * vignette, 0.0, 255.0));
                int g = (int) (Mth.clamp(sg * vignette, 0.0, 255.0));
                int b = (int) (Mth.clamp(sb * vignette, 0.0, 255.0));

                image.setPixelRGBA(i, j, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
    }

    /**
     * 按偏移后的半径采样源图的一个通道。
     *
     * @param shift 通道在源像素坐标里的采样偏移（红蓝各让一点，制造色散）
     */
    private static double sampleChannel(double px, double py, double ux, double uy, double bend,
                                        double scale, double driftX, double driftY,
                                        double holeX, double holeY, int shift) {
        double sx = px + ux * bend;
        double sy = py + uy * bend;

        double su = (sx - holeX) * scale + sourceW * 0.5 + driftX;
        double sv = (sy - holeY) * scale + sourceH * 0.5 + driftY;

        // 镜像平铺：照片本身不可无缝拼接，镜像延拓后边界天然连续
        su = mirror(su, sourceW);
        sv = mirror(sv, sourceH);

        int argb = sampleBilinear(su, sv);
        return (argb >> shift) & 0xFF;
    }

    private static double mirror(double v, double period) {
        double p2 = period * 2.0;
        double m = v % p2;
        if (m < 0) {
            m += p2;
        }
        return m < period ? m : p2 - m;
    }

    private static int sampleBilinear(double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        x0 = Mth.clamp(x0, 0, sourceW - 1);
        y0 = Mth.clamp(y0, 0, sourceH - 1);
        x1 = Mth.clamp(x1, 0, sourceW - 1);
        y1 = Mth.clamp(y1, 0, sourceH - 1);
        double fx = u - Math.floor(u);
        double fy = v - Math.floor(v);

        int c00 = source[y0 * sourceW + x0];
        int c10 = source[y0 * sourceW + x1];
        int c01 = source[y1 * sourceW + x0];
        int c11 = source[y1 * sourceW + x1];

        int r = bilerp(c00 >> 16 & 0xFF, c10 >> 16 & 0xFF, c01 >> 16 & 0xFF, c11 >> 16 & 0xFF, fx, fy);
        int g = bilerp(c00 >> 8 & 0xFF, c10 >> 8 & 0xFF, c01 >> 8 & 0xFF, c11 >> 8 & 0xFF, fx, fy);
        int b = bilerp(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int bilerp(int c00, int c10, int c01, int c11, double fx, double fy) {
        double top = c00 + (c10 - c00) * fx;
        double bottom = c01 + (c11 - c01) * fx;
        return (int) Math.round(top + (bottom - top) * fy);
    }

    private static double smooth(double e0, double e1, double v) {
        double k = Mth.clamp((v - e0) / (e1 - e0), 0.0, 1.0);
        return k * k * (3.0 - 2.0 * k);
    }
}
