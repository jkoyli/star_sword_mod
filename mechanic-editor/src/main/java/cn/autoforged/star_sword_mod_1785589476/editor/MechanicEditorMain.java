package cn.autoforged.star_sword_mod_1785589476.editor;

import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 独立配置编辑器的入口（独立进程，早于游戏加载任何类）。
 *
 * <p>用法：{@code javaw -jar star_sword_mechanic_editor-java17.jar [--root <工程/游戏目录>]}；
 * 不传 {@code --root} 时用当前工作目录。所有 Swing 操作都在 EDT 上执行。
 *
 * <p><b>必须用 Java 17+ 运行</b>：本 jar 的 class 版本是 61。若双击 jar 而系统把 {@code .jar}
 * 关联到 Java 8，会在进入 {@code main} 之前就抛 {@code UnsupportedClassVersionError}，
 * 弹出系统通用的「A Java Exception has occurred.」——这种情况本类无法兜底，
 * 请改用工程根的 {@code start-editor.bat}（它会自己挑一个 17+ 的 JDK）。
 */
public final class MechanicEditorMain {

    private MechanicEditorMain() {
    }

    public static void main(String[] args) {
        try {
            uiTestArgs = args == null ? new String[0] : args;
            if (args != null) {
                for (String arg : args) {
                    if ("--selftest".equals(arg)) {
                        selfTest();
                        return;
                    }
                    if ("--uitest".equals(arg)) {
                        uiTest();
                        return;
                    }
                    if ("--dump".equals(arg)) {
                        dumpLayout();
                        return;
                    }
                }
            }
            run(args);
        } catch (Throwable throwable) {
            // 走到这里说明已经进入了我们的代码（类版本没问题），把真实原因写盘并弹出来，
            // 而不是让用户面对一个空白的 JVM 报错框。
            reportFatal(throwable);
        }
    }

    /**
     * 界面自测：真正把窗口显示出来，用 {@link java.awt.Robot} 截一张图再退出。
     *
     * <p>用途：只有截图能回答「为什么窗口里是空的」。用法：
     * {@code java -jar ... --uitest [输出路径]}，默认写 {@code build/editor-uitest.png}。
     */
    private static void uiTest() {
        Path out = java.nio.file.Paths.get("build", "editor-uitest.png").toAbsolutePath();
        if (uiTestArgs != null) {
            for (String arg : uiTestArgs) {
                if (arg != null && !arg.startsWith("--")) {
                    out = java.nio.file.Paths.get(arg).toAbsolutePath();
                    break;
                }
            }
        }
        final Path target = out;
        try {
            final javax.swing.JFrame[] holder = new javax.swing.JFrame[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                installLookAndFeel();
                EditorFrame frame = new EditorFrame(resolveRoot(new String[0]));
                frame.setVisible(true);
                frame.toFront();
                holder[0] = frame;
            });
            Thread.sleep(1500);
            javax.swing.JFrame frame = holder[0];
            if (frame == null) {
                System.out.println("[uitest] 没找到打开的窗口");
                return;
            }
            // 用 paint 抓窗口自身，而不是 Robot 抓屏幕：窗口被别的窗口挡住时也能拿到真实内容。
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    Math.max(1, frame.getWidth()), Math.max(1, frame.getHeight()),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                java.awt.Graphics2D g = image.createGraphics();
                try {
                    frame.paint(g);
                } finally {
                    g.dispose();
                }
            });
            java.nio.file.Files.createDirectories(target.getParent());
            javax.imageio.ImageIO.write(image, "png", target.toFile());
            System.out.println("[uitest] 截图已保存：" + target
                    + "  窗口大小=" + frame.getWidth() + "x" + frame.getHeight());
            javax.swing.SwingUtilities.invokeAndWait(frame::dispose);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        System.exit(0);
    }

    /** {@code --uitest} 的参数（main 里不便二次解析，简单存一份）。 */
    private static String[] uiTestArgs = new String[0];

    /**
     * 只做布局计算、不显示窗口：把组件树的类名/位置/大小打印出来。
     *
     * <p>用来区分「没加进去」和「加进去了但显示成 0 高度」。
     */
    private static void dumpLayout() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                installLookAndFeel();
                EditorFrame frame = new EditorFrame(resolveRoot(new String[0]));
                frame.pack();
                frame.setSize(640, 640);
                dump(frame.getContentPane(), 0);
            });
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        System.exit(0);
    }

    private static void dump(java.awt.Container container, int depth) {
        String indent = "  ".repeat(depth);
        for (java.awt.Component child : container.getComponents()) {
            System.out.println(indent + child.getClass().getSimpleName()
                    + " bounds=" + child.getBounds()
                    + " visible=" + child.isVisible()
                    + (child instanceof javax.swing.JLabel label ? " text=" + truncate(label.getText()) : ""));
            if (child instanceof java.awt.Container nested && depth < 6) {
                dump(nested, depth + 1);
            }
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "null";
        }
        String one = text.replace('\n', ' ');
        return one.length() > 48 ? one.substring(0, 48) + "…" : one;
    }

    /**
     * 无界面自检：打印「找到哪个 HMCL、哪些游戏目录、哪些实例、默认写到哪个配置」。
     *
     * <p>用途：怀疑「编辑器连的不是我的游戏」时，先跑
     * {@code java -jar star_sword_mechanic_editor-java17.jar --selftest}，
     * 一眼能看出它认的是哪一份实例，而不是靠猜。
     */
    private static void selfTest() {
        HmclDetector.Hmcl hmcl = HmclDetector.find();
        System.out.println("[selftest] HMCL = " + (hmcl == null ? "未找到" : hmcl.exe));
        java.util.List<Path> dirs = hmcl == null
                ? HmclDetector.fallbackGameDirectories()
                : HmclDetector.gameDirectories(hmcl);
        if (dirs.isEmpty()) {
            dirs = HmclDetector.fallbackGameDirectories();
        }
        System.out.println("[selftest] 游戏目录 = " + dirs.size() + " 个");
        Instance best = null;
        Path bestDir = null;
        for (Path dir : dirs) {
            System.out.println("  - " + dir);
            for (Instance instance : Instance.scan(dir)) {
                System.out.println("      * " + instance);
                if (best == null
                        || (best.installedStarSword == null && instance.installedStarSword != null)
                        || (best.installedStarSword == null && instance.modCount > best.modCount)) {
                    best = instance;
                    bestDir = dir;
                }
            }
        }
        if (best == null) {
            System.out.println("[selftest] 没有识别到任何实例");
            return;
        }
        System.out.println("[selftest] 默认实例 = " + best.name + "  (" + best.dir + ")");
        System.out.println("[selftest] 配置文件 = " + SettingsFile.forGameDir(best.dir));
        System.out.println("[selftest] 状态文件 = " + GameStateReader.resolve(SettingsFile.forGameDir(best.dir)));
        System.out.println("[selftest] 已装 mod = " + best.installedStarSword);
        System.out.println("[selftest] 游戏目录候选 = " + bestDir);
    }

    private static void run(String[] args) {
        Path root = resolveRoot(args);
        SwingUtilities.invokeLater(() -> {
            try {
                installLookAndFeel();
                ToolTipManager tips = ToolTipManager.sharedInstance();
                tips.setInitialDelay(350);
                tips.setDismissDelay(20_000);
                tips.setReshowDelay(80);
                new EditorFrame(root).setVisible(true);
            } catch (Throwable throwable) {
                reportFatal(throwable);
            }
        });
    }

    /** 把异常写日志并弹窗；弹窗失败就退化成 stderr。 */
    private static void reportFatal(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        String detail = writer.toString();
        Path log = Paths.get(System.getProperty("user.home", "."), ".star-sword-editor-error.log");
        try {
            Files.writeString(log, "Java " + System.getProperty("java.version")
                    + "\n" + detail, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 日志写不出去也不能再抛。
        }
        System.err.println(detail);
        try {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "编辑器启动失败：" + throwable + "\n\n"
                            + "当前 Java：" + System.getProperty("java.version") + "（需要 17+）\n"
                            + "日志：" + log,
                    "星辉剑 · 机制配置", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // 无图形环境时只保留 stderr 与日志。
        }
    }

    /**
     * 解析 {@code --root}：非法或缺失时回退当前工作目录；若工作目录看起来是子工程
     * （例如从 mechanic-editor 里启动），再往上找一级含 {@code gradlew.bat} 的目录。
     */
    static Path resolveRoot(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length - 1; i++) {
                if ("--root".equals(args[i])) {
                    String value = args[i + 1];
                    if (value != null && !value.isBlank()) {
                        try {
                            Path candidate = Paths.get(value).toAbsolutePath().normalize();
                            if (Files.isDirectory(candidate)) {
                                return candidate;
                            }
                        } catch (RuntimeException ignored) {
                            // 非法路径继续走回退逻辑。
                        }
                    }
                }
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("gradlew.bat")) || Files.isRegularFile(cwd.resolve("gradlew"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null
                && (Files.isRegularFile(parent.resolve("gradlew.bat"))
                || Files.isRegularFile(parent.resolve("gradlew")))) {
            return parent;
        }
        return cwd;
    }

    /**
     * 外观：优先「系统外观」，而不是 Nimbus。
     *
     * <p>隔壁项目用的是 Nimbus，但它的观感偏灰蓝、控件圆角与我们的白底卡片不搭；
     * 这里统一用系统 LAF（Windows 下是浅色的 Windows LAF），所有面板再显式刷白底，
     * 这样换机器/换 JDK 也不会突然变成深色或金属灰。
     */
    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable systemFailure) {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Throwable fallbackFailure) {
                System.err.println("[star-sword-editor] 设置外观失败，使用默认：" + fallbackFailure);
            }
        }
        // 让 JCheckBox / JButton 等默认背景接近白，避免系统 LAF 的灰底压在白卡片上。
        UIManager.put("Panel.background", java.awt.Color.WHITE);
        UIManager.put("CheckBox.background", java.awt.Color.WHITE);
        UIManager.put("Label.background", java.awt.Color.WHITE);
    }
}
