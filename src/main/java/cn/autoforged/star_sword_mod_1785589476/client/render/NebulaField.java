package cn.autoforged.star_sword_mod_1785589476.client.render;

import cn.autoforged.star_sword_mod_1785589476.StarSwordMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 会流动的星云：一张在 CPU 上逐帧重算、再上传成动态纹理的贴图。
 *
 * <p>为什么必须这么做：静态贴图无论怎么旋转都只是"整块在转"，看不出流动。
 * 真正的流动要两件事同时成立——
 * <ol>
 *   <li><b>差速旋转</b>：内圈角速度大于外圈，同一团气体会被越拧越长的螺旋剪切，
 *       这才是"流动"的物理来源。这里用 {@code shear = k*t / (0.18 + r*r)} 实现；</li>
 *   <li><b>图案本身在变</b>：噪声场按时间演化，而不是整体平移。</li>
 * </ol>
 * 两者叠加后每隔 80ms 重算一张 160×160 的 RGBA 图并上传，肉眼看就是气在翻。
 *
 * <p>代价很小：160×160 = 25600 像素 × 约 8 次三角函数 ≈ 每次 0.2ms，
 * 12.5fps 更新，只在介绍框打开时才跑。
 *
 * <p>任何一步失败（显存、驱动、GL 上下文）都会被吞掉并永久关闭自己，绝不影响 tooltip 的其余部分。
 */
@OnlyIn(Dist.CLIENT)
public final class NebulaField {

    /** 贴图边长。工具提示里实际显示约 180px，160 足够且便宜。 */
    private static final int SIZE = 160;
    /** 重算间隔（毫秒）。80ms ≈ 12.5fps，对慢速气体足够。 */
    private static final long UPDATE_MS = 80L;

    private static final ResourceLocation LOCATION =
            new ResourceLocation(StarSwordMod.MOD_ID, "dynamic/blackhole_nebula");

    private static DynamicTexture texture;
    private static long lastUpdate = Long.MIN_VALUE;
    private static boolean disabled;

    private NebulaField() {
    }

    /**
     * 取当前帧的星云贴图。第一次调用时创建并注册动态纹理；
     * 之后按 {@link #UPDATE_MS} 节流重算。返回 {@code null} 表示这一层不可用（调用方跳过即可）。
     */
    public static ResourceLocation texture() {
        if (disabled) {
            return null;
        }
        try {
            if (texture == null) {
                NativeImage image = new NativeImage(SIZE, SIZE, true);
                texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(LOCATION, texture);
            }
            long now = Util.getMillis();
            if (now - lastUpdate >= UPDATE_MS) {
                lastUpdate = now;
                fill(texture.getPixels(), now);
                texture.upload();
            }
            return LOCATION;
        } catch (Throwable t) {
            // 特效永远不该把界面拖垮：出一次错就永久关掉这一层。
            disabled = true;
            return null;
        }
    }

    /**
     * 算一帧。
     *
     * @param image 目标像素缓冲，尺寸必须是 {@link #SIZE}
     * @param nowMs 墙钟毫秒；所有时间项都直接用它，不做累计，避免卡顿时累积误差
     */
    private static void fill(NativeImage image, long nowMs) {
        double t = nowMs / 1000.0;
        double half = SIZE / 2.0;

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double nx = (x + 0.5 - half) / half;
                double ny = (y + 0.5 - half) / half;
                double r = Math.sqrt(nx * nx + ny * ny);

                if (r >= 1.0) {
                    image.setPixelRGBA(x, y, 0);
                    continue;
                }

                double th = Math.atan2(ny, nx);
                // 差速旋转：越靠内转得越快，气团被剪成螺旋
                double shear = 1.35 * t / (0.16 + r * r);
                double a = th + shear;
                double lr = Math.log(r + 0.12);

                // 多尺度湍流：角向频率 × 径向频率 × 各自的演化速度
                double n = 0.5
                        + 0.22 * Math.sin(2.0 * a + 3.0 * lr + 0.90 * t)
                        + 0.18 * Math.sin(3.0 * a - 2.0 * lr - 1.30 * t + 1.7)
                        + 0.13 * Math.sin(5.0 * a + 4.0 * lr + 2.10 * t)
                        + 0.09 * Math.sin(8.0 * a - 6.0 * lr - 2.60 * t + 0.4)
                        + 0.06 * Math.sin(13.0 * a + 9.0 * lr + 3.40 * t)
                        + 0.05 * Math.sin(4.0 * a - 1.1 * lr + 0.60 * t + 2.9);                n = Mth.clamp(n, 0.0, 1.0);
                // 平方后暗部被压下去，亮部拉成细丝，看起来才像"气"而不是"雾"
                double density = n * n;
                // 大尺度扇区：让星云出现明暗块，而不是一圈圈均匀的弧
                double sector = 0.55 + 0.45 * Math.sin(th * 1.0 + 0.7 * t + 0.35);
                density *= 0.45 + 0.75 * sector;

                // 中心留空（事件视界会盖住），外缘渐隐到 0
                double inner = smooth(0.10, 0.34, r);
                double outer = 1.0 - smooth(0.72, 1.0, r);
                double alpha = density * inner * outer * 0.52;
                if (alpha <= 0.004) {
                    imgSet(image, x, y, 0);
                    continue;
                }

                // 色带：外冷内热，亮丝偏青白
                int color = lerpColor(0x0b3b52, 0x7fe8ff, Math.min(1.0, n * 1.15));
                if (n > 0.86) {
                    color = lerpColor(color, 0xeaffff, (n - 0.86) / 0.14 * 0.8);
                }
                int av = (int) (Mth.clamp(alpha, 0.0, 1.0) * 255.0);
                imgSet(image, x, y, av << 24 | (color & 0xFF) << 16
                        | (color >> 8 & 0xFF) << 8 | (color >> 16 & 0xFF));
            }
        }
    }

    /** {@code setPixelRGBA} 的通道顺序是 ABGR（内存里按 R,G,B,A 排），这里单独收一次口，免得每次手写。 */
    private static void imgSet(NativeImage image, int x, int y, int abgr) {
        image.setPixelRGBA(x, y, abgr);
    }

    private static double smooth(double e0, double e1, double v) {
        double k = Mth.clamp((v - e0) / (e1 - e0), 0.0, 1.0);
        return k * k * (3.0 - 2.0 * k);
    }

    private static int lerpColor(int c1, int c2, double k) {
        k = Mth.clamp(k, 0.0, 1.0);
        int r = (int) Mth.lerp(k, c1 >> 16 & 0xFF, c2 >> 16 & 0xFF);
        int g = (int) Mth.lerp(k, c1 >> 8 & 0xFF, c2 >> 8 & 0xFF);
        int b = (int) Mth.lerp(k, c1 & 0xFF, c2 & 0xFF);
        return r << 16 | g << 8 | b;
    }
}
