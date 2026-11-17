import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把用户手涂的放大图转回 16×16 的星空格子清单。
 *
 * <p>约定：在 {@code tools/original/star_sword_16_x32.png}（16 像素 ×32 倍 = 512×512）
 * 上，把要变成星空的格子涂成**纯黑或接近黑**，其余像素保持不变。
 * 每个 32×32 的方块里只要有超过 25% 的像素是黑的，这一格就算"星空格"。
 *
 * <p>输出 {@code tools/sword_star_cells.txt}（16 行，'#' = 星空），
 * 之后跑 {@code java tools/GenFx.java} 就会按它重新生成贴图。
 *
 * <p>用法：{@code java tools/ref/PaintToCells.java [涂过的图]}
 */
public final class PaintToCells {

    private static final int CELLS = 16;

    public static void main(String[] args) throws Exception {
        File file = new File(args.length > 0 ? args[0] : "tools/original/star_sword_16_x32.png");
        BufferedImage img = ImageIO.read(file);
        // 形状以原始 16×16 为准：涂过的图很可能被编辑器压成了不透明黑底，
        // 不加这层门控，整片背景都会被判成"星空格"。
        BufferedImage shape = ImageIO.read(new File("tools/original/star_sword_git_16.png"));
        int size = Math.min(img.getWidth(), img.getHeight());
        double cell = size / (double) CELLS;

        List<String> lines = new ArrayList<>();
        int marked = 0;
        for (int v = 0; v < CELLS; v++) {
            StringBuilder sb = new StringBuilder();
            for (int u = 0; u < CELLS; u++) {
                int x0 = (int) (u * cell);
                int y0 = (int) (v * cell);
                int x1 = (int) ((u + 1) * cell);
                int y1 = (int) ((v + 1) * cell);
                int dark = 0;
                int total = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = img.getRGB(x, y);
                        int a = p >>> 24;
                        if (a < 24) {
                            continue;       // 透明处不算
                        }
                        total++;
                        int r = p >> 16 & 0xFF;
                        int g = p >> 8 & 0xFF;
                        int b = p & 0xFF;
                        if (r + g + b < 150) {
                            dark++;
                        }
                    }
                }
                boolean inside = (shape.getRGB(u, v) >>> 24) > 0;
                boolean star = inside && total > 0 && dark > total * 0.25;
                sb.append(star ? '#' : '.');
                if (star) {
                    marked++;
                }
            }
            lines.add(sb.toString());
        }

        Path out = Path.of("tools/sword_star_cells.txt");
        Files.write(out, lines, StandardCharsets.UTF_8);
        System.out.println("读到 " + file.getPath() + "  ->  " + out);
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println("合计 " + marked + " 格星空");
    }
}
