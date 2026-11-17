import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;

/**
 * 从标注图里提取"被红框框住的像素"。
 *
 * <p>输入是用户手绘的 16×16 剑图（放大到 837×836，每个像素约 52×52），
 * 红框画在像素边界上。做法：把红色像素做连通域，取每个连通域的包围盒，
 * 再把包围盒按格子大小对齐成 16×16 的格子范围。
 *
 * <p>输出直接是一张 16×16 的 0/1 图，可以贴进 GenFx 当常量。
 */
public final class ExtractMask {

    public static void main(String[] args) throws Exception {
        File file = new File(args.length > 0 ? args[0] : "tools/ref/sword_annotated.png");
        BufferedImage img = ImageIO.read(file);
        int w = img.getWidth();
        int h = img.getHeight();

        boolean[] red = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int r = p >> 16 & 0xFF;
                int g = p >> 8 & 0xFF;
                int b = p & 0xFF;
                red[y * w + x] = r > 160 && g < 110 && b < 110;
            }
        }

        double cw = w / 16.0;
        double ch = h / 16.0;
        boolean[][] cells = new boolean[16][16];

        // 从画布四条边出发，对"非红"像素做泛洪，标记外部；
        // 剩下的非红像素就是被红框围住的内部。这比连通域靠谱得多——
        // 相邻的框会连成一片，但它们的**内部**依然是被同一圈红色包围的。
        // 膨胀 1 像素：手画的框在角上可能有 1px 缺口，不补的话泛洪会漏进去
        boolean[] solid = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean hit = false;
                for (int dy = -1; dy <= 1 && !hit; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && red[ny * w + nx]) {
                            hit = true;
                            break;
                        }
                    }
                }
                solid[y * w + x] = hit;
            }
        }

        boolean[] outside = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            queue.add(x);
            queue.add((h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            queue.add(y * w);
            queue.add(y * w + w - 1);
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            if (outside[idx] || solid[idx]) {
                continue;
            }
            outside[idx] = true;
            int x = idx % w;
            int y = idx / w;
            if (x > 0) queue.add(idx - 1);
            if (x < w - 1) queue.add(idx + 1);
            if (y > 0) queue.add(idx - w);
            if (y < h - 1) queue.add(idx + w);
        }

        for (int v = 0; v < 16; v++) {
            for (int u = 0; u < 16; u++) {
                int cx = (int) ((u + 0.5) * cw);
                int cy = (int) ((v + 0.5) * ch);
                int idx = cy * w + cx;
                cells[v][u] = !solid[idx] && !outside[idx];
            }
        }

        System.out.println("框住的格子：");
        for (int v = 0; v < 16; v++) {
            StringBuilder sb = new StringBuilder();
            for (int u = 0; u < 16; u++) {
                sb.append(cells[v][u] ? '#' : '.');
            }
            System.out.println(sb);
        }
    }
}
