import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * GenFx - single-file, dependency-free asset generator for star_sword_mod.
 *
 * <p>Produces:
 * <ul>
 *   <li>animated star sword strip (80 x 80*96) + .mcmeta，带边缘泛光；</li>
 *   <li>blackhole disk / halo / ring / core / streak (256x256 RGBA)。</li>
 * </ul>
 *
 * <p>动态星云不在这里生成——它由 {@code NebulaField} 在运行时逐帧算，
 * 因为"流动"必须真的每帧变，静态贴图无论怎么旋转都只是整块在转。
 *
 * <p>Run: {@code java tools/GenFx.java}
 */
public final class GenFx {

    /* ---------------- sword animation constants ---------------- */
    static final int SRC = 16;              // original texture is 16x16
    static final int SCALE = 2;             // nearest-neighbour upscale
    static final int ART = SRC * SCALE;     // 64：画在画布中间的原图
    static final int MARGIN = 4;            // 四周留白，专门给泛光用
    static final int FRAME = ART + MARGIN * 2;  // 80
    static final int FRAMES = 96;
    static final int BORDER = SCALE;        // 原图最外圈 1 像素（=4 texel）永远不动

    /* palette: same family as the original cyan/blue/white blade */
    static final int C_BASE = 0x04121c;
    static final int C_MID = 0x0a5f7a;
    static final int C_BRIGHT = 0x7ff0ff;
    static final int C_STAR = 0xffffff;
    static final int C_GLOW = 0x4fd8ff;

    /* ---------------- helpers ---------------- */
    static int hash(int x, int y, int z) {
        int h = x * 0x27d4eb2d + y * 0x165667b1 + z * 0x9e3779b9;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return h;
    }

    static double rnd(int x, int y, int z) {
        return (hash(x, y, z) >>> 8 & 0xFFFFFF) / 16777216.0;
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    static double smooth(double e0, double e1, double x) {
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    static int lerp(int c1, int c2, double t) {
        t = clamp01(t);
        int r = (int) Math.round(((c1 >> 16) & 255) + (((c2 >> 16) & 255) - ((c1 >> 16) & 255)) * t);
        int g = (int) Math.round(((c1 >> 8) & 255) + (((c2 >> 8) & 255) - ((c1 >> 8) & 255)) * t);
        int b = (int) Math.round((c1 & 255) + ((c2 & 255) - (c1 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    static int argb(double a, int rgb) {
        int av = (int) Math.round(clamp01(a) * 255);
        return (av << 24) | (rgb & 0xFFFFFF);
    }

    static int scaleRgb(int rgb, double f) {
        int r = (int) Math.round(clamp01(((rgb >> 16) & 255) / 255.0 * f) * 255);
        int g = (int) Math.round(clamp01(((rgb >> 8) & 255) / 255.0 * f) * 255);
        int b = (int) Math.round(clamp01((rgb & 255) / 255.0 * f) * 255);
        return (r << 16) | (g << 8) | b;
    }

    static double lum(int rgb) {
        return (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255.0;
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        System.out.println("base dir = " + root);

        Path res = root.resolve("src/main/resources/assets/star_sword_mod_1785589476/textures");
        Path swordPng = res.resolve("item/star_sword.png");
        Path backupDir = root.resolve("tools/original");
        Path backupPng = backupDir.resolve("star_sword_16.png");
        Path guiDir = res.resolve("gui");

        Files.createDirectories(backupDir);
        Files.createDirectories(guiDir);

        /* ---- 1. read source (self-healing if the file is already a strip) ---- */
        BufferedImage src;
        if (Files.exists(swordPng) && ImageIO.read(swordPng.toFile()).getHeight() == SRC) {
            src = ImageIO.read(swordPng.toFile());
            if (!Files.exists(backupPng)) {
                Files.copy(swordPng, backupPng, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } else if (Files.exists(backupPng)) {
            src = ImageIO.read(backupPng.toFile());
            System.out.println("source already animated; using backup " + backupPng);
        } else {
            throw new IllegalStateException("no usable source sword texture found at " + swordPng);
        }
        if (src.getWidth() != SRC || src.getHeight() != SRC) {
            throw new IllegalStateException("source must be 16x16, got " + src.getWidth() + "x" + src.getHeight());
        }

        /* ---- 2. art 4x upscale, centred inside a wider canvas ---- */
        int[][] base = new int[FRAME][FRAME];
        for (int y = 0; y < FRAME; y++) {
            for (int x = 0; x < FRAME; x++) {
                int ax = x - MARGIN;
                int ay = y - MARGIN;
                boolean inside = ax >= 0 && ay >= 0 && ax < ART && ay < ART;
                base[y][x] = inside ? src.getRGB(ax / SCALE, ay / SCALE) : 0;
            }
        }

        boolean[][] opaque = new boolean[FRAME][FRAME];
        boolean[][] locked = new boolean[FRAME][FRAME];
        for (int y = 0; y < FRAME; y++) {
            for (int x = 0; x < FRAME; x++) {
                opaque[y][x] = ((base[y][x] >>> 24) & 255) != 0;
                // 原图最外一圈（放大后 4 texel）逐字节锁死，泛光只能画在它外面
                int ax = x - MARGIN;
                int ay = y - MARGIN;
                locked[y][x] = opaque[y][x] && (ax < BORDER || ay < BORDER
                        || ax >= ART - BORDER || ay >= ART - BORDER);
            }
        }

        /* ---- 3. two fields used by every frame ----
           bloom：剑身内部靠近轮廓的几像素，做"边缘自己也在发亮"；
           glow ：轮廓外的软光晕（画在留白里，最远 MARGIN 像素）。 */
        double[][] bloom = new double[FRAME][FRAME];
        double[][] glow = new double[FRAME][FRAME];
        for (int y = 0; y < FRAME; y++) {
            for (int x = 0; x < FRAME; x++) {
                double best = 999;
                for (int dy = -MARGIN; dy <= MARGIN; dy++) {
                    for (int dx = -MARGIN; dx <= MARGIN; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= FRAME || ny >= FRAME || !opaque[ny][nx]) continue;
                        double d = Math.hypot(dx, dy);
                        if (d < best) best = d;
                    }
                }
                if (best > MARGIN) {
                    glow[y][x] = 0;
                } else {
                    // 1 - (d/MARGIN)^2：贴着轮廓最亮，向外快速衰减
                    double k = Math.pow(1 - best / MARGIN, 1.35);
                    glow[y][x] = k * k;
                }
                if (opaque[y][x]) {
                    // 内部：只有真正贴着轮廓的 1-2 px 才提亮，否则整把剑会糊成一片白
                    bloom[y][x] = Math.max(0, 1.0 - best / 2.6);
                }
            }
        }

        /* ---- 4. build the 96-frame vertical strip ---- */
        BufferedImage strip = new BufferedImage(FRAME, FRAME * FRAMES, BufferedImage.TYPE_INT_ARGB);
        for (int f = 0; f < FRAMES; f++) {
            double phase = 2 * Math.PI * f / FRAMES;

            for (int y = 0; y < FRAME; y++) {
                for (int x = 0; x < FRAME; x++) {
                    int px = base[y][x];
                    int out;

                    if (locked[y][x]) {
                        out = px; // 原图最外圈：一个字节都不改
                    } else if (opaque[y][x]) {
                        out = 0xFF000000 | framePixel(x, y, phase, px, bloom[y][x]);
                    } else if (glow[y][x] > 0.002) {
                        out = glowPixel(x, y, phase, px, glow[y][x]);
                    } else {
                        out = 0; // 剑身之外的纯透明区域保持透明
                    }
                    strip.setRGB(x, f * FRAME + y, out);
                }
            }
        }

        /* ---- 5. write strip + mcmeta ---- */
        ImageIO.write(strip, "PNG", swordPng.toFile());
        String mcmeta = "{\"animation\":{\"frametime\":1,\"interpolate\":true}}";
        Files.write(swordPng.resolveSibling("star_sword.png.mcmeta"),
                mcmeta.getBytes(StandardCharsets.UTF_8));

        /* ---- 6. verify: border bytes + transparency + loop seam ---- */
        BufferedImage check = ImageIO.read(swordPng.toFile());
        int badBorder = 0;
        int badTransparent = 0;
        for (int f = 0; f < FRAMES; f++) {
            for (int y = 0; y < FRAME; y++) {
                for (int x = 0; x < FRAME; x++) {
                    int got = check.getRGB(x, f * FRAME + y);
                    if (locked[y][x] && got != base[y][x]) badBorder++;
                    boolean shouldBeEmpty = !opaque[y][x] && glow[y][x] <= 0.002;
                    if (shouldBeEmpty && ((got >>> 24) & 255) != 0) badTransparent++;
                }
            }
        }
        int seam = 0;
        for (int y = 0; y < FRAME; y++) {
            for (int x = 0; x < FRAME; x++) {
                int px = base[y][x];
                int rerender;
                if (locked[y][x]) {
                    rerender = px;
                } else if (opaque[y][x]) {
                    rerender = 0xFF000000 | framePixel(x, y, 2 * Math.PI, px, bloom[y][x]);
                } else if (glow[y][x] > 0.002) {
                    rerender = glowPixel(x, y, 2 * Math.PI, px, glow[y][x]);
                } else {
                    rerender = 0;
                }
                if (rerender != strip.getRGB(x, y)) seam++;
            }
        }
        System.out.printf("sword strip %dx%d frames=%d borderMismatch=%d strayPixels=%d seamDelta=%d%n",
                check.getWidth(), check.getHeight(), check.getHeight() / FRAME,
                badBorder, badTransparent, seam);

        /* ---- 7. black hole textures ---- */
        writePng(guiDir.resolve("blackhole_disk.png"), disk());
        writePng(guiDir.resolve("blackhole_halo.png"), halo());
        writePng(guiDir.resolve("blackhole_ring.png"), ring());
        writePng(guiDir.resolve("blackhole_core.png"), core());
        writePng(guiDir.resolve("blackhole_streak.png"), streak());

        System.out.println("done");
    }

    /**
     * One editable pixel of the sword for a given animation phase.
     * All time-dependent terms are 2*pi-periodic in phase, so frame 96 == frame 0.
     */
    static int framePixel(int x, int y, double phase, int px, double bloomV) {
        double u = (x - MARGIN + 0.5) / SCALE;
        double v = (y - MARGIN + 0.5) / SCALE;
        /* 沿剑身的长轴坐标：这把剑是左下 -> 右上的对角线，(u-v) 走一遍就是从剑柄到剑尖 */
        double s = (u - v) * 0.70710678;
        double t = (u + v) * 0.70710678;
        double L = lum(px);

        /* 归一化到 0..1（16x16 对角线约有 22 个原生像素长） */
        double s01 = clamp01((s + 11.0) / 22.0);

        /* 彗星头：一道高光沿剑身从剑柄扫到剑尖，一圈正好走一次，靠 frac 保证首尾相接 */
        double p = s01 - phase / (2 * Math.PI);
        p = p - Math.floor(p);
        double head = Math.exp(-Math.pow((p - 0.5) / 0.16, 2));
        double tail = Math.exp(-Math.pow((p - 0.42) / 0.30, 2));

        /* 低频星云：波长比剑身宽，读起来是"云在挪"，不是"像素在噪" */
        double n = 0.5
                + 0.22 * Math.sin(2 * Math.PI * (s * 0.09) - 3 * phase)
                + 0.16 * Math.sin(2 * Math.PI * (s * 0.14 + t * 0.05) - 5 * phase + 1.7);
        n = clamp01(n);

        /* 亮度：不再用色带覆盖原图（那样整把剑会糊成一片），而是给原像素乘一个增益。
           原图的明暗结构原样保留，只是被"点亮/压暗"，这是像素画做流光最省事也最不出错的做法 */
        double m = 0.52
                + 0.80 * head
                + 0.32 * (n - 0.5)
                + 0.10 * bloomV;
        int out = scaleRgb(px, m);
        /* 彗星头偏冷白，刃口偏亮青——两个方向的偏色都只加在对应的地方 */
        out = lerp(out, C_BRIGHT, 0.30 * head);
        out = lerp(out, 0x9ff4ff, 0.45 * bloomV);

        /* 稀疏的闪烁星点：每个原生像素一格，频率取整数保证循环无缝 */
        int cu = (int) Math.floor(u);
        int cv = (int) Math.floor(v);
        double hg = rnd(cu, cv, 29);
        if (hg > 0.78) {
            int freq = 1 + (int) (rnd(cu, cv, 47) * 3);
            double tw = Math.sin(freq * phase + rnd(cu, cv, 11) * 2 * Math.PI);
            if (tw > 0) {
                double spark = Math.pow(tw, 24) * (0.40 + 0.60 * L);
                if (spark > 0.05) {
                    out = lerp(out, C_STAR, Math.min(1.0, spark));
                }
            }
        }

        double gain = 1.0 + 0.04 * Math.sin(2 * phase + 0.6);
        return scaleRgb(out, gain);
    }

    /**
     * 轮廓外的一层泛光。alpha 随时间轻微呼吸，并跟着剑身上的彗星头一起明暗，
     * 这样看起来是"这把剑在发光"，而不是贴了一圈固定的光晕。
     */
    static int glowPixel(int x, int y, double phase, int px, double glowV) {
        double u = (x - MARGIN + 0.5) / SCALE;
        double v = (y - MARGIN + 0.5) / SCALE;
        double s = (u - v) * 0.70710678;
        double s01 = clamp01((s + 11.0) / 22.0);
        double p = s01 - phase / (2 * Math.PI);
        p = p - Math.floor(p);
        double head = Math.exp(-Math.pow((p - 0.5) / 0.20, 2));

        double a = glowV * (0.42 + 0.22 * head) * (0.88 + 0.12 * Math.sin(2 * phase + s));
        int col = lerp(C_GLOW, 0xe8feff, clamp01(glowV * 0.7 + 0.3 * head));
        return argb(a, col);
    }

    static void writePng(Path p, BufferedImage img) throws Exception {
        ImageIO.write(img, "PNG", p.toFile());
        System.out.println("wrote " + p.getFileName() + " " + img.getWidth() + "x" + img.getHeight()
                + " " + Files.size(p) + " bytes");
    }

    static final int BH = 256;
    static final double CX = 127.5, CY = 127.5;

    /* accretion disk：内缘 70 .. 外缘 126，多普勒让左侧远亮于右侧，叠湍流细丝 */
    static BufferedImage disk() {
        BufferedImage img = new BufferedImage(BH, BH, BufferedImage.TYPE_INT_ARGB);
        int cIn = 0x06384f, cMid = 0x2fd8f5, cHot = 0xffffff;
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BH; x++) {
                double dx = x + 0.5 - CX, dy = y + 0.5 - CY;
                double r = Math.hypot(dx, dy);
                if (r < 70 || r > 126) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                double th = Math.atan2(dy, dx);
                double rr = (r - 70) / 56.0;           // 0 内 .. 1 外

                double radial = Math.pow(1 - rr, 1.6);
                double rim = Math.exp(-Math.pow((r - 76.0) / 6.0, 2));   // 内缘最热
                double base = 0.12 + 0.80 * radial + 0.65 * rim;

                // 差速旋转：角相位随 log(r) 变化，形成螺旋细丝
                double spin = 9.0 * Math.log(r / 70.0);
                double turb = 0.5
                        + 0.22 * Math.sin(3 * th + spin + 0.7)
                        + 0.18 * Math.sin(7 * th - 1.6 * spin + 2.1)
                        + 0.15 * Math.sin(13 * th + 2.4 * spin - 1.3)
                        + 0.12 * Math.sin(23 * th - 3.2 * spin + 0.4)
                        + 0.10 * Math.sin(37 * th + 5.1 * spin)
                        + 0.08 * Math.sin(61 * th - 7.3 * spin)
                        + 0.06 * Math.sin(97 * th + 11.0 * spin);
                turb = clamp01(0.5 + (turb - 0.5) * 1.9);
                // 高温丝：把亮的部分再压出细线，暗的部分留出空隙
                turb = 0.22 + 0.95 * Math.pow(turb, 2.3);

                double grain = 0.82 + 0.36 * rnd(x, y, 101);
                grain *= 0.90 + 0.20 * rnd(x / 2, y / 2, 202);

                // 多普勒：左侧 ~1.0，右侧 ~0.10
                double dop = 0.10 + 0.90 * (0.5 * (1 - Math.cos(th)));

                double b = clamp01(base * turb * dop * grain) * 0.95;

                int col = b < 0.45 ? lerp(cIn, cMid, b / 0.45)
                        : lerp(cMid, cHot, (b - 0.45) / 0.55);

                double aIn = smooth(70, 76, r);
                double aOut = 1 - smooth(104, 126, r);
                double a = clamp01(aIn * aOut * (0.08 + 1.05 * b)
                        * (0.86 + 0.28 * rnd(x, y, 303)));
                img.setRGB(x, y, argb(a, col));
            }
        }
        return img;
    }

    /* radial glow, cyan, alpha 0.55 -> 0 at r=128 */
    static BufferedImage halo() {
        BufferedImage img = new BufferedImage(BH, BH, BufferedImage.TYPE_INT_ARGB);
        int cIn = 0x7ff0ff, cOut = 0x0a4f6e;
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BH; x++) {
                double r = Math.hypot(x + 0.5 - CX, y + 0.5 - CY);
                if (r > 128) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                double k = r / 128.0;
                double a = 0.55 * Math.pow(1 - k, 1.85);
                img.setRGB(x, y, argb(a, lerp(cIn, cOut, Math.pow(k, 0.75))));
            }
        }
        return img;
    }

    /* photon ring: r=76, thin bright band + side glow */
    static BufferedImage ring() {
        BufferedImage img = new BufferedImage(BH, BH, BufferedImage.TYPE_INT_ARGB);
        int cCore = 0xeaffff, cGlow = 0x7ff0ff;
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BH; x++) {
                double r = Math.hypot(x + 0.5 - CX, y + 0.5 - CY);
                double t = r - 76.0;
                double ct = t / 1.15;
                double coreA = Math.exp(-ct * ct * 1.25);
                double glowA = 0.22 * Math.exp(-Math.abs(t) / 5.5);
                double a = clamp01(0.85 * coreA + glowA);
                if (a < 0.004) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                int col = lerp(cGlow, cCore, clamp01(coreA));
                img.setRGB(x, y, argb(a, col));
            }
        }
        return img;
    }

    /* event horizon: solid black r<=67, soft edge */
    static BufferedImage core() {
        BufferedImage img = new BufferedImage(BH, BH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BH; x++) {
                double r = Math.hypot(x + 0.5 - CX, y + 0.5 - CY);
                double a = 1 - smooth(67.0, 70.0, r);
                img.setRGB(x, y, a <= 0.002 ? 0 : argb(a, 0x000000));
            }
        }
        return img;
    }

    /* stretched star arcs, low alpha */
    static BufferedImage streak() {
        BufferedImage img = new BufferedImage(BH, BH, BufferedImage.TYPE_INT_ARGB);
        int c = 0xbfefff;
        double[] radii = {84, 93, 101, 112, 121};
        double[] start = {0.4, 2.3, 4.1, 1.2, 5.4};
        double[] span = {1.9, 1.3, 2.4, 1.0, 1.7};
        double[] thick = {1.35, 1.05, 1.6, 0.9, 1.4};
        double[] peak = {0.35, 0.26, 0.32, 0.20, 0.24};
        for (int y = 0; y < BH; y++) {
            for (int x = 0; x < BH; x++) {
                double dx = x + 0.5 - CX, dy = y + 0.5 - CY;
                double r = Math.hypot(dx, dy);
                if (r < 78 || r > 128) {
                    img.setRGB(x, y, 0);
                    continue;
                }
                double th = Math.atan2(dy, dx);
                double best = 0;
                for (int i = 0; i < radii.length; i++) {
                    double dr = (r - radii[i]) / thick[i];
                    if (Math.abs(dr) > 3) continue;
                    double d = th - start[i];
                    while (d > Math.PI) d -= 2 * Math.PI;
                    while (d < -Math.PI) d += 2 * Math.PI;
                    if (d < 0 || d > span[i]) continue;
                    double taper = Math.pow(Math.sin(Math.PI * d / span[i]), 0.8);
                    double a = peak[i] * Math.exp(-dr * dr) * taper;
                    a *= 0.55 + 0.45 * rnd((int) (th * 26), (int) (r * 3), 7);
                    if (a > best) best = a;
                }
                img.setRGB(x, y, best < 0.01 ? 0 : argb(Math.min(best, 0.35), c));
            }
        }
        return img;
    }
}
