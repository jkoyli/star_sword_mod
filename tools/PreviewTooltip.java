import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * 离线预览器：把 {@code BlackHoleCard} 的图层堆叠与变换在 CPU 上复刻一遍，输出 PNG。
 *
 * <p>目的只有一个——不启动游戏就能看到黑洞长什么样。渲染逻辑（层顺序、压扁、旋转、
 * 剪刀矩形、加色/alpha 混合）必须和 {@code BlackHoleCard} 保持一致，改效果时两边一起改。
 *
 * <p>用法：{@code java tools/PreviewTooltip.java}，输出 {@code tools/preview/tooltip_preview.png}。
 */
public final class PreviewTooltip {

    private static final Path TEX_DIR = Path.of("src/main/resources/assets/star_sword_mod_1785589476/textures/gui");
    private static final Path OUT = Path.of("tools/preview/tooltip_preview.png");

    /** 屏幕放大倍数：游戏里 1 GUI 像素，这里画 PX 个像素，方便肉眼看细节。 */
    private static final int PX = 4;

    // ---- 与 BlackHoleCard 保持一致的布局常量 ----
    private static final int PAD = 7;
    private static final int GUTTER = 92;
    /** 与 BlackHoleCard.DISK_SQUASH 保持一致。 */
    private static final float SQUASH = 0.38F;

    public static void main(String[] args) throws Exception {
        Layer disk = new Layer("blackhole_disk.png");
        Layer halo = new Layer("blackhole_halo.png");
        Layer ring = new Layer("blackhole_ring.png");
        Layer core = new Layer("blackhole_core.png");
        Layer streak = new Layer("blackhole_streak.png");

        int cardW = 240;
        int cardH = 84;
        int w = cardW * PX;
        int h = cardH * PX;

        // 背景：给个"世界"底色，方便看辉光溢出
        int[] canvas = new int[w * h];
        for (int i = 0; i < canvas.length; i++) {
            canvas[i] = 0xFF1A2430;
        }

        // 卡片本体
        fillRect(canvas, w, 3 * PX, 3 * PX, (cardW - 3) * PX, (cardH - 3) * PX, 0x50000000);
        fillRect(canvas, w, 0, 0, cardW * PX, cardH * PX, 0xE6080A10);
        border(canvas, w, cardW * PX, cardH * PX);

        // 黑洞
        float cx = (cardW - PAD - GUTTER / 2.0F) * PX;
        float cy = (cardH / 2.0F) * PX;
        float diameter = Math.min(GUTTER, cardH) * 0.94F * PX;

        long now = Long.parseLong(System.getProperty("t", "1700"));
        drawBlackHole(canvas, w, h, cx, cy, diameter, now, disk, halo, ring, core, streak);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, canvas, 0, w);
        OUT.getParent().toFile().mkdirs();
        ImageIO.write(out, "png", OUT.toFile());
        System.out.println("wrote " + OUT + "  " + w + "x" + h);
    }

    private static void drawBlackHole(int[] canvas, int w, int h, float cx, float cy, float diameter, long now,
                                      Layer disk, Layer halo, Layer ring, Layer core, Layer streak) {
        float angle = now * 0.00060F;
        float breathe = 1.0F + 0.02F * (float) Math.sin(now * 0.0016F);
        float d = diameter * breathe;

        halo.blitAdditive(canvas, w, h, cx, cy, d * 2.30F, 1.0F, angle * 0.15F, 0x66BFEFFF);
        disk.blitAdditive(canvas, w, h, cx, cy, d, SQUASH, angle, 0xF0FFFFFF);
        core.blitAlpha(canvas, w, h, cx, cy, d, 1.0F, 0.0F, 0xF0000000);
        ring.blitAdditive(canvas, w, h, cx, cy, d, 1.0F, 0.0F, 0xFFE8FFFF);

        int scissorTop = (int) (cy - d * 0.02F);
        int scissorBottom = (int) (cy + d * 0.50F);
        disk.blitAdditive(canvas, w, h, cx, cy, d, SQUASH, angle, 0x9AFFFFFF, scissorTop, scissorBottom);
        streak.blitAdditive(canvas, w, h, cx, cy, d * 1.35F, SQUASH, -angle * 0.55F, 0x40BFEFFF);
    }

    // ------------------------------------------------------------------
    // 软件合成
    // ------------------------------------------------------------------

    static final class Layer {
        final BufferedImage img;
        final int tw;
        final int th;

        Layer(String name) throws Exception {
            this.img = ImageIO.read(new File(TEX_DIR.toFile(), name));
            this.tw = img.getWidth();
            this.th = img.getHeight();
        }

        void blitAdditive(int[] dst, int dw, int dh, float cx, float cy,
                          float size, float squashY, float angle, int argb) {
            blitAdditive(dst, dw, dh, cx, cy, size, squashY, angle, argb, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        /** MC 的 blendFunc(SRC_ALPHA, ONE)：out = src.rgb * src.a + dst。 */
        void blitAdditive(int[] dst, int dw, int dh, float cx, float cy,
                          float size, float squashY, float angle, int argb, int scissorTop, int scissorBottom) {
            blend(dst, dw, dh, cx, cy, size, squashY, angle, argb, true, scissorTop, scissorBottom);
        }

        /** MC 的默认 alpha 混合：out = src.rgb * src.a + dst * (1 - src.a)。 */
        void blitAlpha(int[] dst, int dw, int dh, float cx, float cy,
                       float size, float squashY, float angle, int argb) {
            blend(dst, dw, dh, cx, cy, size, squashY, angle, argb, false, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        private void blend(int[] dst, int dw, int dh, float cx, float cy,
                           float size, float squashY, float angle, int argb,
                           boolean additive, int scissorTop, int scissorBottom) {
            float layerA = (argb >>> 24 & 0xFF) / 255.0F;
            if (layerA <= 0.0F) return;
            float nR = (argb >>> 16 & 0xFF) / 255.0F;
            float nG = (argb >>> 8 & 0xFF) / 255.0F;
            float nB = (argb & 0xFF) / 255.0F;

            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float half = size * 0.5F;

            int x0 = Math.max(0, (int) Math.floor(cx - size));
            int x1 = Math.min(dw - 1, (int) Math.ceil(cx + size));
            int y0 = Math.max(0, (int) Math.floor(cy - size));
            int y1 = Math.min(dh - 1, (int) Math.ceil(cy + size));

            for (int y = y0; y <= y1; y++) {
                if (y < scissorTop || y > scissorBottom) continue;
                for (int x = x0; x <= x1; x++) {
                    float dx = x - cx;
                    float dy = y - cy;
                    // 逆变换：先反压扁，再反旋转
                    float uy = dy / squashY;
                    float lx = dx * cos + uy * sin;
                    float ly = -dx * sin + uy * cos;
                    if (lx < -half || lx > half || ly < -half || ly > half) continue;

                    float u = (lx + half) / size;
                    float v = (ly + half) / size;
                    int texX = Math.min(tw - 1, Math.max(0, (int) (u * tw)));
                    int texY = Math.min(th - 1, Math.max(0, (int) (v * th)));
                    int texel = img.getRGB(texX, texY);

                    float ta = (texel >>> 24 & 0xFF) / 255.0F * layerA;
                    if (ta <= 0.001F) continue;
                    float tr = (texel >>> 16 & 0xFF) / 255.0F * nR;
                    float tg = (texel >>> 8 & 0xFF) / 255.0F * nG;
                    float tb = (texel & 0xFF) / 255.0F * nB;

                    int idx = y * dw + x;
                    int dr = (dst[idx] >>> 16 & 0xFF);
                    int dg = (dst[idx] >>> 8 & 0xFF);
                    int db = (dst[idx] & 0xFF);

                    int or;
                    int og;
                    int ob;
                    if (additive) {
                        or = clamp(dr + tr * ta * 255.0F);
                        og = clamp(dg + tg * ta * 255.0F);
                        ob = clamp(db + tb * ta * 255.0F);
                    } else {
                        or = clamp(tr * ta * 255.0F + dr * (1.0F - ta));
                        og = clamp(tg * ta * 255.0F + dg * (1.0F - ta));
                        ob = clamp(tb * ta * 255.0F + db * (1.0F - ta));
                    }
                    dst[idx] = 0xFF000000 | or << 16 | og << 8 | ob;
                }
            }
        }
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    private static void fillRect(int[] c, int w, int x0, int y0, int x1, int y1, int argb) {
        float a = (argb >>> 24 & 0xFF) / 255.0F;
        int r = (argb >>> 16 & 0xFF);
        int g = (argb >>> 8 & 0xFF);
        int b = (argb & 0xFF);
        for (int y = Math.max(0, y0); y < Math.min(y1, c.length / w); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, w); x++) {
                int i = y * w + x;
                int dr = c[i] >>> 16 & 0xFF;
                int dg = c[i] >>> 8 & 0xFF;
                int db = c[i] & 0xFF;
                c[i] = 0xFF000000 | clamp(r * a + dr * (1 - a)) << 16
                        | clamp(g * a + dg * (1 - a)) << 8 | clamp(b * a + db * (1 - a));
            }
        }
    }

    private static void border(int[] c, int w, int cw, int ch) {
        fillRect(c, w, 0, 0, cw, 1, 0x557FE9FF);
        fillRect(c, w, 0, ch - 1, cw, ch, 0x557FE9FF);
        fillRect(c, w, 0, 0, 1, ch, 0x557FE9FF);
        fillRect(c, w, cw - 1, 0, cw, ch, 0x557FE9FF);
        fillRect(c, w, cw - PAD - GUTTER, PAD - 2, cw - PAD - GUTTER + 1, ch - PAD + 2, 0x337FE9FF);
    }
}
