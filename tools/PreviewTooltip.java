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
        // 星云是运行时算出来的，这里复刻一份固定时刻的帧
        Layer nebula = new Layer(nebulaPixels(1.7), NEB_SIZE, NEB_SIZE);
        Layer stars = new Layer("blackhole_stars.png");

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
        float shadowR = diameter * 0.273F;
        int[] lensPx = lensPixels(cardW, cardH, (cardW - PAD - GUTTER / 2.0F), cardH / 2.0F,
                shadowR, now / 1000.0);
        blitCover(canvas, w, h, lensPx, 160, 80, 0xECFFFFFF);
        drawBlackHole(canvas, w, h, cx, cy, diameter, now, disk, halo, ring, core, streak, nebula, stars);

        // 文字区的横向渐隐底（与 BlackHoleCard 的 32 条竖带一致）
        int scrimRight = (cardW - PAD - GUTTER) * PX;
        for (int i = 0; i < 32; i++) {
            int sx0 = scrimRight * i / 32;
            int sx1 = scrimRight * (i + 1) / 32;
            fillRect(canvas, w, sx0, 0, sx1, h, lerpColorA(0xB4080A10, 0x00080A10, i / 31.0));
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, canvas, 0, w);
        OUT.getParent().toFile().mkdirs();
        ImageIO.write(out, "png", OUT.toFile());
        System.out.println("wrote " + OUT + "  " + w + "x" + h);
    }

    private static void drawBlackHole(int[] canvas, int w, int h, float cx, float cy, float diameter, long now,
                                      Layer disk, Layer halo, Layer ring, Layer core, Layer streak, Layer nebula, Layer stars) {
        float angle = now * 0.00060F;
        float breathe = 1.0F + 0.02F * (float) Math.sin(now * 0.0016F);
        float d = diameter * breathe;


        nebula.blitAdditive(canvas, w, h, cx, cy, d * 2.30F, 1.0F, -angle * 0.40F, 0x30FFFFFF);
        halo.blitAdditive(canvas, w, h, cx, cy, d * 2.30F, 1.0F, angle * 0.15F, 0x66BFEFFF);
        disk.blitAdditive(canvas, w, h, cx, cy, d, SQUASH, angle, 0xF0FFFFFF);
        core.blitAlpha(canvas, w, h, cx, cy, d, 1.0F, 0.0F, 0xF0000000);
        ring.blitAdditive(canvas, w, h, cx, cy, d, 1.0F, 0.0F, 0xFFE8FFFF);

        int scissorTop = (int) (cy - d * 0.02F);
        int scissorBottom = (int) (cy + d * 0.50F);
        disk.blitAdditive(canvas, w, h, cx, cy, d, SQUASH, angle, 0x9AFFFFFF, scissorTop, scissorBottom);
        streak.blitAdditive(canvas, w, h, cx, cy, d * 1.35F, SQUASH, -angle * 0.55F, 0x40BFEFFF);
    }

    static final int NEB_SIZE = 160;

    /**
     * 复刻 {@code NebulaField.fill}。两边必须保持一致：改了一边就要改另一边，
     * 否则预览和游戏里看到的是两种东西。
     */
    static int[] nebulaPixels(double t) {
        int[] out = new int[NEB_SIZE * NEB_SIZE];
        double half = NEB_SIZE / 2.0;
        for (int y = 0; y < NEB_SIZE; y++) {
            for (int x = 0; x < NEB_SIZE; x++) {
                double nx = (x + 0.5 - half) / half;
                double ny = (y + 0.5 - half) / half;
                double r = Math.sqrt(nx * nx + ny * ny);
                if (r >= 1.0) {
                    out[y * NEB_SIZE + x] = 0;
                    continue;
                }
                double th = Math.atan2(ny, nx);
                double shear = 1.35 * t / (0.16 + r * r);
                double a = th + shear;
                double lr = Math.log(r + 0.12);

                double n = 0.5
                        + 0.22 * Math.sin(2.0 * a + 3.0 * lr + 0.90 * t)
                        + 0.18 * Math.sin(3.0 * a - 2.0 * lr - 1.30 * t + 1.7)
                        + 0.13 * Math.sin(5.0 * a + 4.0 * lr + 2.10 * t)
                        + 0.09 * Math.sin(8.0 * a - 6.0 * lr - 2.60 * t + 0.4)
                        + 0.06 * Math.sin(13.0 * a + 9.0 * lr + 3.40 * t)
                        + 0.05 * Math.sin(4.0 * a - 1.1 * lr + 0.60 * t + 2.9);                n = Math.max(0, Math.min(1, n));
                double density = n * n;
                // 大尺度扇区：让星云出现明暗块，而不是一圈圈均匀的弧
                double sector = 0.55 + 0.45 * Math.sin(th * 1.0 + 0.7 * t + 0.35);
                density *= 0.45 + 0.75 * sector;

                double inner = smooth(0.10, 0.34, r);
                double outer = 1.0 - smooth(0.72, 1.0, r);
                double alpha = density * inner * outer * 0.52;
                if (alpha <= 0.004) {
                    out[y * NEB_SIZE + x] = 0;
                    continue;
                }
                int color = lerpColor(0x0b3b52, 0x7fe8ff, Math.min(1.0, n * 1.15));
                if (n > 0.86) {
                    color = lerpColor(color, 0xeaffff, (n - 0.86) / 0.14 * 0.8);
                }
                int av = (int) (Math.max(0, Math.min(1, alpha)) * 255.0);
                out[y * NEB_SIZE + x] = av << 24 | color;
            }
        }
        return out;
    }

    static int lerpColorA(int a, int b, double k) {
        k = Math.max(0, Math.min(1, k));
        int aa = (int) (a >>> 24 & 0xFF) + (int) (((b >>> 24 & 0xFF) - (a >>> 24 & 0xFF)) * k);
        int c = lerpColor(a & 0xFFFFFF, b & 0xFFFFFF, k);
        return aa << 24 | c;
    }
    static double smooth(double e0, double e1, double v) {
        double k = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    static int lerpColor(int c1, int c2, double k) {
        k = Math.max(0, Math.min(1, k));
        int r = (int) (c1 >> 16 & 0xFF) + (int) (((c2 >> 16 & 0xFF) - (c1 >> 16 & 0xFF)) * k);
        int g = (int) (c1 >> 8 & 0xFF) + (int) (((c2 >> 8 & 0xFF) - (c1 >> 8 & 0xFF)) * k);
        int b = (int) (c1 & 0xFF) + (int) (((c2 & 0xFF) - (c1 & 0xFF)) * k);
        return r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------
    // 软件合成
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 深空照片 + 伪引力透镜（复刻 LensField）
    // ------------------------------------------------------------------

    static final int LENS_W = 160;
    static final int LENS_H = 80;

    static int[] SRC;
    static int SRCW;
    static int SRCH;

    static void loadSource() {
        if (SRC != null) {
            return;
        }
        try {
            BufferedImage img = ImageIO.read(new File(TEX_DIR.toFile(), "blackhole_deepspace.png"));
            SRCW = img.getWidth();
            SRCH = img.getHeight();
            SRC = img.getRGB(0, 0, SRCW, SRCH, null, 0, SRCW);
        } catch (Exception e) {
            throw new RuntimeException("cannot read blackhole_deepspace.png", e);
        }
    }

    static int[] lensPixels(float cardW, float cardH, float holeX, float holeY, float shadowR, double t) {
        loadSource();
        int[] out = new int[LENS_W * LENS_H];
        double scale = SRCW / (cardW * 1.7);
        double driftX = t * 9.0;
        double driftY = t * 4.0;
        double shadow = Math.max(1.0, shadowR);

        for (int j = 0; j < LENS_H; j++) {
            for (int i = 0; i < LENS_W; i++) {
                double px = (i + 0.5) / LENS_W * cardW;
                double py = (j + 0.5) / LENS_H * cardH;
                double dx = px - holeX;
                double dy = py - holeY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double ux = dist > 1e-4 ? dx / dist : 0.0;
                double uy = dist > 1e-4 ? dy / dist : 0.0;
                double rr = dist / shadow;
                double ring = Math.exp(-Math.pow((rr - 1.0) / 0.55, 2.0));
                double bend = rr > 1.0 ? Double.parseDouble(System.getProperty("lens","0.85")) * shadow * ring : 0.0;

                double sr = sampleChannel(px, py, ux, uy, bend * 1.06, scale, driftX, driftY, holeX, holeY, 16);
                double sg = sampleChannel(px, py, ux, uy, bend, scale, driftX, driftY, holeX, holeY, 8);
                double sb = sampleChannel(px, py, ux, uy, bend * 0.94, scale, driftX, driftY, holeX, holeY, 0);

                double vig = 0.82 * (1.0 - 0.30 * smooth(0.55, 1.6, rr < 1.0 ? 1.0 : rr));
                int r = (int) Math.max(0, Math.min(255, sr * vig));
                int g = (int) Math.max(0, Math.min(255, sg * vig));
                int b = (int) Math.max(0, Math.min(255, sb * vig));
                out[j * LENS_W + i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return out;
    }

    static double sampleChannel(double px, double py, double ux, double uy, double bend,
                                double scale, double driftX, double driftY,
                                double holeX, double holeY, int shift) {
        double sx = px + ux * bend;
        double sy = py + uy * bend;
        double su = (sx - holeX) * scale + SRCW * 0.5 + driftX;
        double sv = (sy - holeY) * scale + SRCH * 0.5 + driftY;
        su = mirror(su, SRCW);
        sv = mirror(sv, SRCH);
        int argb = srcBilinear(su, sv);
        return (argb >> shift) & 0xFF;
    }

    static double mirror(double v, double period) {
        double p2 = period * 2.0;
        double m = v % p2;
        if (m < 0) {
            m += p2;
        }
        return m < period ? m : p2 - m;
    }

    static int srcBilinear(double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        x0 = Math.max(0, Math.min(SRCW - 1, x0));
        y0 = Math.max(0, Math.min(SRCH - 1, y0));
        x1 = Math.max(0, Math.min(SRCW - 1, x1));
        y1 = Math.max(0, Math.min(SRCH - 1, y1));
        double fx = u - Math.floor(u);
        double fy = v - Math.floor(v);
        int c00 = SRC[y0 * SRCW + x0];
        int c10 = SRC[y0 * SRCW + x1];
        int c01 = SRC[y1 * SRCW + x0];
        int c11 = SRC[y1 * SRCW + x1];
        int r = bilerpI(c00 >> 16 & 0xFF, c10 >> 16 & 0xFF, c01 >> 16 & 0xFF, c11 >> 16 & 0xFF, fx, fy);
        int g = bilerpI(c00 >> 8 & 0xFF, c10 >> 8 & 0xFF, c01 >> 8 & 0xFF, c11 >> 8 & 0xFF, fx, fy);
        int b = bilerpI(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int bilerpI(int c00, int c10, int c01, int c11, double fx, double fy) {
        double top = c00 + (c10 - c00) * fx;
        double bottom = c01 + (c11 - c01) * fx;
        return (int) Math.round(top + (bottom - top) * fy);
    }

    /** 把一帧透镜图拉伸铺满整块画布（画布就是卡片）。 */
    static void blitCover(int[] canvas, int w, int h, int[] src, int sw, int sh, int argb) {
        float la = (argb >>> 24 & 0xFF) / 255.0F;
        for (int y = 0; y < h; y++) {
            double v = (y + 0.5) / h * sh - 0.5;
            for (int x = 0; x < w; x++) {
                double u = (x + 0.5) / w * sw - 0.5;
                int x0 = (int) Math.floor(u);
                int y0 = (int) Math.floor(v);
                int x1 = Math.min(sw - 1, x0 + 1);
                int y1 = Math.min(sh - 1, y0 + 1);
                x0 = Math.max(0, Math.min(sw - 1, x0));
                y0 = Math.max(0, Math.min(sh - 1, y0));
                double fx = u - Math.floor(u);
                double fy = v - Math.floor(v);
                int c00 = src[y0 * sw + x0];
                int c10 = src[y0 * sw + x1];
                int c01 = src[y1 * sw + x0];
                int c11 = src[y1 * sw + x1];
                int r = bilerpI(c00 >> 16 & 0xFF, c10 >> 16 & 0xFF, c01 >> 16 & 0xFF, c11 >> 16 & 0xFF, fx, fy);
                int g = bilerpI(c00 >> 8 & 0xFF, c10 >> 8 & 0xFF, c01 >> 8 & 0xFF, c11 >> 8 & 0xFF, fx, fy);
                int b = bilerpI(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy);
                int idx = y * w + x;
                int dr = canvas[idx] >> 16 & 0xFF;
                int dg = canvas[idx] >> 8 & 0xFF;
                int db = canvas[idx] & 0xFF;
                canvas[idx] = 0xFF000000
                        | clamp(r * la + dr * (1 - la)) << 16
                        | clamp(g * la + dg * (1 - la)) << 8
                        | clamp(b * la + db * (1 - la));
            }
        }
    }
    static final class Layer {
        final BufferedImage img;
        final int tw;
        final int th;

        Layer(String name) throws Exception {
            this.img = ImageIO.read(new File(TEX_DIR.toFile(), name));
            this.tw = img.getWidth();
            this.th = img.getHeight();
        }

        /** 给动态算出来的图层用（星云）。 */
        Layer(int[] argb, int w, int h) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, w, h, argb, 0, w);
            this.img = image;
            this.tw = w;
            this.th = h;
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

        private int sampleBilinear(float u, float v) {
            int x0 = (int) Math.floor(u);
            int y0 = (int) Math.floor(v);
            int x1 = Math.min(tw - 1, x0 + 1);
            int y1 = Math.min(th - 1, y0 + 1);
            x0 = Math.max(0, Math.min(tw - 1, x0));
            y0 = Math.max(0, Math.min(th - 1, y0));
            float fx = u - x0;
            float fy = v - y0;
            int c00 = img.getRGB(x0, y0);
            int c10 = img.getRGB(x1, y0);
            int c01 = img.getRGB(x0, y1);
            int c11 = img.getRGB(x1, y1);
            int a = bilerp(c00 >>> 24 & 0xFF, c10 >>> 24 & 0xFF, c01 >>> 24 & 0xFF, c11 >>> 24 & 0xFF, fx, fy);
            int r = bilerp(c00 >>> 16 & 0xFF, c10 >>> 16 & 0xFF, c01 >>> 16 & 0xFF, c11 >>> 16 & 0xFF, fx, fy);
            int g = bilerp(c00 >>> 8 & 0xFF, c10 >>> 8 & 0xFF, c01 >>> 8 & 0xFF, c11 >>> 8 & 0xFF, fx, fy);
            int b = bilerp(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, fx, fy);
            return a << 24 | r << 16 | g << 8 | b;
        }

        private int bilerp(int c00, int c10, int c01, int c11, float fx, float fy) {
            float top = c00 + (c10 - c00) * fx;
            float bottom = c01 + (c11 - c01) * fx;
            return Math.round(top + (bottom - top) * fy);
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

                    // 双线性采样：游戏里这些贴图用 GL_LINEAR，最近邻预览会产生假的摩尔纹
                    float u = (lx + half) / size * (tw - 1);
                    float v = (ly + half) / size * (th - 1);
                    int texel = sampleBilinear(u, v);

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
