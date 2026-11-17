package cn.autoforged.star_sword_mod_1785589476.editor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立配置编辑器主窗口：白底、单窗口、分组卡片；上半部选「真实游戏实例」，下半部是开关。
 *
 * <p>与游戏的关系（这是它区别于「开发环境自嗨」的地方）：
 * <ol>
 *   <li>配置写到**所选 HMCL 实例**的 {@code config/} —— 与 mod 的默认读取路径完全一致，
 *       不需要任何 {@code -D}；</li>
 *   <li>「装到实例」把 {@code build/libs/star_sword_mod-*.jar} 复制进该实例的 {@code mods/}；</li>
 *   <li>「保存并启动」打开 HMCL（HMCL 没有「命令行启动某版本」的开关），玩家点一次启动后，
 *       编辑器靠心跳文件确认游戏真的跑起来了。</li>
 * </ol>
 */
final class EditorFrame extends JFrame {

    private static final Color BG = Color.WHITE;
    private static final Color MUTED = new Color(0x6E6E6E);
    private static final Color ACCENT = new Color(0x2F6FD0);
    private static final Color WARN = new Color(0x9A6A00);

    private final Path root;
    /** 当前写入的配置文件；随所选实例变化。 */
    private Path settingsFile;

    private HmclDetector.Hmcl hmcl;
    private final List<Path> gameDirs = new ArrayList<>();
    private final List<Instance> instances = new ArrayList<>();
    private Instance selectedInstance;

    private final JComboBox<Path> gameDirCombo = new JComboBox<>();
    private final JComboBox<Instance> instanceCombo = new JComboBox<>();
    private final JLabel instanceInfo = new JLabel(" ");
    private final JLabel hmclInfo = new JLabel(" ");

    private final List<SettingRow> rows = new ArrayList<>();
    private final JLabel status = new JLabel(" ");
    private final JButton launchButton = new JButton("保存并启动");
    private final JButton installButton = new JButton("装到实例");

    private final JLabel gameStateLabel = new JLabel("游戏：未启动");
    private final JLabel gameStateDetailLabel = new JLabel(" ");

    private volatile Process gameProcess;

    EditorFrame(Path root) {
        super("星辉剑 · 机制配置");
        this.root = root;
        this.settingsFile = SettingsFile.resolve(root);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        detectTargets();

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        setSize(new Dimension(640, 640));
        setMinimumSize(new Dimension(540, 480));
        setLocationRelativeTo(null);

        new javax.swing.Timer(1_000, e -> refreshGameState()).start();
        refreshGameState();
    }

    // ===================== 目标探测 =====================

    private void detectTargets() {
        this.hmcl = HmclDetector.find();
        List<Path> dirs = this.hmcl == null ? new ArrayList<>() : HmclDetector.gameDirectories(this.hmcl);
        if (dirs.isEmpty()) {
            dirs = HmclDetector.fallbackGameDirectories();
        }
        this.gameDirs.clear();
        this.gameDirs.addAll(dirs);

        this.gameDirCombo.removeAllItems();
        for (Path dir : this.gameDirs) {
            this.gameDirCombo.addItem(dir);
        }

        // 默认选一个已经装了星辉剑的实例（说明玩家就在用它），否则选 mods 最多的。
        Path chosen = null;
        Instance best = null;
        for (Path dir : this.gameDirs) {
            for (Instance instance : Instance.scan(dir)) {
                if (best == null
                        || (best.installedStarSword == null && instance.installedStarSword != null)
                        || (best.installedStarSword == null && instance.modCount > best.modCount)) {
                    best = instance;
                    chosen = dir;
                }
            }
        }
        if (chosen == null && !this.gameDirs.isEmpty()) {
            chosen = this.gameDirs.get(0);
        }
        selectGameDir(chosen);
    }

    private void selectGameDir(Path gameDir) {
        this.gameDirCombo.setSelectedItem(gameDir);
        this.instances.clear();
        this.instances.addAll(Instance.scan(gameDir));
        this.instanceCombo.removeAllItems();
        Instance pick = null;
        for (Instance instance : this.instances) {
            this.instanceCombo.addItem(instance);
            if (pick == null || (pick.installedStarSword == null && instance.installedStarSword != null)) {
                pick = instance;
            }
        }
        if (pick != null) {
            this.instanceCombo.setSelectedItem(pick);
        }
        applyInstance(pick);
    }

    private void applyInstance(Instance instance) {
        this.selectedInstance = instance;
        if (instance != null) {
            // 关键：写进实例自己的 config/，mod 默认就在那儿找。
            this.settingsFile = SettingsFile.forGameDir(instance.dir);
            this.instanceInfo.setText("配置：" + instance.name + " \\ config\\" + SettingsFile.FILE_NAME);
            this.instanceInfo.setToolTipText(this.settingsFile.toString());
        } else {
            this.settingsFile = SettingsFile.resolve(this.root);
            this.instanceInfo.setText("未选实例；配置将写到工程根：" + shorten(this.settingsFile.toString()));
            this.instanceInfo.setToolTipText(this.settingsFile.toString());
        }
        this.instanceInfo.setForeground(instance != null ? MUTED : WARN);
        reloadValues();
        refreshGameState();
    }

    /** 太长的路径只显示尾部，完整路径放 tooltip（窗口宽度有限，硬截断很难看）。 */
    private static String shorten(String path) {
        if (path == null) {
            return "";
        }
        if (path.length() <= 68) {
            return path;
        }
        return "…" + path.substring(path.length() - 64);
    }

    /** 状态行前缀：带实例名，避免只看到一截路径不知道是哪个实例。 */
    private String instanceLabel() {
        return this.selectedInstance == null ? "" : this.selectedInstance.name + " · ";
    }

    private void reloadValues() {
        Map<String, Boolean> values = SettingsFile.loadOrDefaults(this.settingsFile);
        for (SettingRow row : this.rows) {
            row.setSelected(values.getOrDefault(row.def().id, row.def().defaultValue));
        }
        this.status.setText("配置文件：" + instanceLabel() + shorten(this.settingsFile.toString()));
        this.status.setToolTipText(this.settingsFile.toString());
    }

    private Map<String, Boolean> valuesNow() {
        return SettingsFile.loadOrDefaults(this.settingsFile);
    }

    // ===================== 构建界面 =====================

    private JPanel buildHeader() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 6, 16));

        JLabel title = new JLabel("机制配置");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("选好实例 → 调开关 → 保存并启动；预设会在进入世界时应用（需持剑与对应权限）");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 11f));
        subtitle.setForeground(MUTED);
        subtitle.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(title);
        panel.add(Box.createVerticalStrut(3));
        panel.add(subtitle);
        return panel;
    }

    private JScrollPane buildBody() {
        // 关键：必须用 VerticalPanel（宽度跟随视口），否则 BoxLayout 会把内容宽度算成
        // Integer.MAX_VALUE，所有组件被推到屏幕外，窗口看起来是一片空白。
        VerticalPanel content = new VerticalPanel();
        content.setBorder(BorderFactory.createEmptyBorder(2, 12, 8, 12));

        content.add(buildInstanceCard());
        content.add(Box.createVerticalStrut(8));
        content.add(buildGroup(SettingsCatalog.GROUP_PLAYER,
                "只影响自己；与符咒/面板是同一批开关", valuesNow()));
        content.add(Box.createVerticalStrut(8));
        content.add(buildGroup(SettingsCatalog.GROUP_ATTACK,
                "影响剑打出去的那一下；服务端全局生效，需要 op2", valuesNow()));
        content.add(Box.createVerticalStrut(8));
        content.add(buildGroup(SettingsCatalog.GROUP_GLOBAL,
                "全服共享，进服后需要 op2 才会被接受", valuesNow()));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        return scroll;
    }

    /** 「真实游戏实例」卡片：游戏目录 / 实例 / HMCL 路径。 */
    private JPanel buildInstanceCard() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        // BoxLayout 的子组件默认 0.5（居中）对齐：不钉成 LEFT 就会出现「左边空一大片」。
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(0xD8DCE3)),
                        "游戏实例（HMCL）"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setBackground(BG);
        row1.add(label("游戏目录"), BorderLayout.WEST);
        row1.add(this.gameDirCombo, BorderLayout.CENTER);
        JButton rescan = new JButton("刷新");
        rescan.addActionListener(e -> {
            detectTargets();
            reloadValues();
        });
        row1.add(rescan, BorderLayout.EAST);
        row1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        panel.add(row1);

        panel.add(Box.createVerticalStrut(4));

        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.setBackground(BG);
        row2.add(label("实例"), BorderLayout.WEST);
        row2.add(this.instanceCombo, BorderLayout.CENTER);
        row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        panel.add(row2);

        this.gameDirCombo.addActionListener(e -> {
            Path dir = (Path) this.gameDirCombo.getSelectedItem();
            if (dir != null) {
                this.instances.clear();
                this.instances.addAll(Instance.scan(dir));
                this.instanceCombo.removeAllItems();
                for (Instance instance : this.instances) {
                    this.instanceCombo.addItem(instance);
                }
                applyInstance(this.instances.isEmpty() ? null : this.instances.get(0));
            }
        });
        this.instanceCombo.addActionListener(e -> applyInstance((Instance) this.instanceCombo.getSelectedItem()));

        this.instanceInfo.setFont(this.instanceInfo.getFont().deriveFont(Font.PLAIN, 11f));
        this.instanceInfo.setForeground(MUTED);
        this.instanceInfo.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(4));
        panel.add(this.instanceInfo);

        this.hmclInfo.setFont(this.hmclInfo.getFont().deriveFont(Font.PLAIN, 11f));
        this.hmclInfo.setForeground(MUTED);
        this.hmclInfo.setAlignmentX(LEFT_ALIGNMENT);
        this.hmclInfo.setText(this.hmcl == null
                ? "未找到 HMCL（把 HMCL-*.exe 放到桌面/下载，或用「开发启动」）"
                : "HMCL：" + this.hmcl.exe);
        panel.add(this.hmclInfo);
        return panel;
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setPreferredSize(new Dimension(64, 20));
        return label;
    }

    /** 一个分组卡片：TitledBorder 标题 + 该组下的若干行。 */
    private JPanel buildGroup(String group, String purpose, Map<String, Boolean> values) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(0xD8DCE3)),
                        group + "　" + purpose),
                BorderFactory.createEmptyBorder(2, 2, 4, 2)));

        for (SettingsCatalog.Def def : SettingsCatalog.ofGroup(group)) {
            SettingRow row = new SettingRow(def, values.getOrDefault(def.id, def.defaultValue));
            rows.add(row);
            panel.add(row);
        }
        return panel;
    }

    private JPanel buildFooter() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 16, 12, 16));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(BG);

        this.gameStateLabel.setFont(this.gameStateLabel.getFont().deriveFont(Font.BOLD, 12f));
        this.gameStateLabel.setAlignmentX(LEFT_ALIGNMENT);
        this.gameStateDetailLabel.setFont(this.gameStateDetailLabel.getFont().deriveFont(Font.PLAIN, 11f));
        this.gameStateDetailLabel.setForeground(MUTED);
        this.gameStateDetailLabel.setAlignmentX(LEFT_ALIGNMENT);

        top.add(this.gameStateLabel);
        top.add(this.gameStateDetailLabel);
        panel.add(top, BorderLayout.NORTH);

        this.status.setFont(this.status.getFont().deriveFont(Font.PLAIN, 11f));
        this.status.setForeground(MUTED);
        panel.add(this.status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.setBackground(BG);

        this.installButton.addActionListener(e -> installToInstance());
        buttons.add(this.installButton);

        JButton saveButton = new JButton("保存");
        saveButton.addActionListener(e -> saveQuietly());
        buttons.add(saveButton);

        JButton devButton = new JButton("开发启动");
        devButton.setToolTipText("gradlew runClient（开发环境，不是你的 HMCL 实例）");
        devButton.addActionListener(e -> launchDev());
        buttons.add(devButton);

        this.launchButton.setFont(this.launchButton.getFont().deriveFont(Font.BOLD, 12f));
        this.launchButton.setToolTipText("保存配置 → 打开 HMCL（游戏起来后我会用心跳确认）");
        this.launchButton.addActionListener(e -> launchGame());
        buttons.add(this.launchButton);

        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    // ===================== 与游戏进程的通信 =====================

    private void refreshGameState() {
        Path stateFile = GameStateReader.resolve(this.settingsFile);
        GameStateReader.State state = GameStateReader.read(stateFile);
        long now = System.currentTimeMillis();
        boolean processAlive = this.gameProcess != null && this.gameProcess.isAlive();

        if (state == null) {
            this.gameStateLabel.setForeground(WARN);
            if (processAlive) {
                this.gameStateLabel.setText("游戏：正在启动…");
                this.gameStateDetailLabel.setText("进程已拉起，等待模组写心跳（进世界后才有）");
            } else if (this.gameProcess != null) {
                this.gameStateLabel.setForeground(new Color(0xB03A2E));
                this.gameStateLabel.setText("游戏：进程已退出");
                this.gameStateDetailLabel.setText("没有读到心跳文件，请确认模组已装进该实例；状态文件：" + stateFile);
            } else {
                this.gameStateLabel.setText("游戏：未启动");
                this.gameStateDetailLabel.setText("状态文件：" + stateFile);
            }
            return;
        }

        if (!state.fresh(now)) {
            this.gameStateLabel.setForeground(new Color(0xB03A2E));
            this.gameStateLabel.setText("游戏：心跳已停止（约 " + ((now - state.heartbeat()) / 1000) + " 秒前）");
            this.gameStateDetailLabel.setText("通常是已退出；若游戏仍在，说明模组被禁用或卡死。模组版本 "
                    + state.modVersion());
            return;
        }

        this.gameStateLabel.setForeground(new Color(0x1E7B34));
        if (!state.inWorld()) {
            this.gameStateLabel.setText("游戏：运行中（尚未进入世界）");
            this.gameStateDetailLabel.setText("模组 " + state.modVersion()
                    + " 已加载；进世界并手持星辉剑后才接受开关写入");
            return;
        }

        Map<String, Boolean> presets = collect();
        int matched = 0;
        StringBuilder mismatch = new StringBuilder();
        for (Map.Entry<String, Boolean> entry : presets.entrySet()) {
            Boolean applied = state.values().get(entry.getKey());
            if (applied != null && applied.equals(entry.getValue())) {
                matched++;
            } else {
                if (mismatch.length() > 0) {
                    mismatch.append('、');
                }
                SettingsCatalog.Def def = SettingsCatalog.byId(entry.getKey());
                mismatch.append(def == null ? entry.getKey() : def.title);
            }
        }
        int total = presets.size();

        this.gameStateLabel.setText("游戏：运行中 · " + state.player() + " @ " + state.dimension());
        if (matched == total) {
            this.gameStateDetailLabel.setText("预设已全部生效（" + matched + "/" + total + "）"
                    + (state.presetsPushed() ? " · 已在本次登录下发" : ""));
        } else {
            this.gameStateDetailLabel.setText("预设未完全生效（" + matched + "/" + total + "）"
                    + (mismatch.length() > 0 ? "：" + mismatch : "")
                    + "　—— 全服项需要 op2，且写入要求手持星辉剑");
        }
    }

    // ===================== 动作 =====================

    private Map<String, Boolean> collect() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (SettingRow row : this.rows) {
            out.put(row.def().id, row.isSelected());
        }
        return out;
    }

    private boolean saveQuietly() {
        try {
            Path parent = this.settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SettingsFile.save(this.settingsFile, collect());
            this.status.setText("已保存：" + this.settingsFile);
            return true;
        } catch (IOException e) {
            this.status.setText("保存失败：" + e.getMessage());
            return false;
        }
    }

    private void installToInstance() {
        if (this.selectedInstance == null) {
            this.status.setText("先选一个实例，再点「装到实例」");
            return;
        }
        ModInstaller.Result result = ModInstaller.install(this.root, this.selectedInstance, null);
        this.status.setText(result.message());
        if (result.ok()) {
            // 重新扫一遍，让下拉框里的「已装版本」立刻更新。
            Path gameDir = (Path) this.gameDirCombo.getSelectedItem();
            String keep = this.selectedInstance.name;
            this.instances.clear();
            this.instances.addAll(Instance.scan(gameDir));
            this.instanceCombo.removeAllItems();
            Instance again = null;
            for (Instance instance : this.instances) {
                this.instanceCombo.addItem(instance);
                if (instance.name.equals(keep)) {
                    again = instance;
                }
            }
            this.instanceCombo.setSelectedItem(again);
            applyInstance(again);
        }
    }

    private void launchGame() {
        if (!saveQuietly()) {
            return;
        }
        this.launchButton.setEnabled(false);
        this.launchButton.setText("启动中…");
        this.status.setText("正在启动…");

        Thread worker = new Thread(() -> {
            String message = "正在启动…";
            Process process = null;
            try {
                if (this.hmcl != null) {
                    // 真实游戏：先把 mod 装进所选实例（装没装、版本对不对都要管），再开 HMCL。
                    // 只在「未装」时安装是不够的——实例里装着 2.3.4、这里构建出 2.3.5 时，
                    // 不比对版本就会让玩家以为在测新版，其实跑的是旧的。
                    Path source = ModInstaller.findSourceJar(this.root);
                    String sourceName = source == null ? null : source.getFileName().toString();
                    boolean needsInstall = this.selectedInstance != null && sourceName != null
                            && !sourceName.equals(this.selectedInstance.installedStarSword);
                    boolean installFailed = false;
                    if (needsInstall) {
                        ModInstaller.Result installed =
                                ModInstaller.install(this.root, this.selectedInstance, source);
                        if (!installed.ok()) {
                            message = installed.message();
                            installFailed = true;
                        }
                    }
                    if (installFailed) {
                        // 装不上就不要启动旧版，否则玩家以为在测新版。
                        process = null;
                    } else {
                        GameLauncher.Result result = GameLauncher.launchHmcl(this.hmcl, this.selectedInstance);
                        process = result.process();
                        message = result.message()
                                + (needsInstall ? "（已升级实例里的 mod 到 " + sourceName + "）" : "");
                    }
                } else {
                    GameLauncher.Result result = GameLauncher.launchDev(this.root, this.settingsFile);
                    process = result.process();
                    message = result.message();
                }
            } catch (Throwable throwable) {
                message = "启动失败：" + throwable;
            }
            String finalMessage = message;
            Process finalProcess = process;
            SwingUtilities.invokeLater(() -> {
                this.gameProcess = finalProcess;
                this.launchButton.setEnabled(true);
                this.launchButton.setText("保存并启动");
                this.status.setText(finalMessage);
                this.refreshGameState();
            });
        }, "star-sword-editor-launch");
        worker.setDaemon(true);
        worker.start();
    }

    private void launchDev() {
        if (!saveQuietly()) {
            return;
        }
        this.status.setText("正在启动开发环境…");
        Thread worker = new Thread(() -> {
            String message = "正在启动…";
            Process process = null;
            try {
                GameLauncher.Result result = GameLauncher.launchDev(this.root, this.settingsFile);
                process = result.process();
                message = result.message();
            } catch (Throwable throwable) {
                message = "开发启动失败：" + throwable;
            }
            String finalMessage = message;
            Process finalProcess = process;
            SwingUtilities.invokeLater(() -> {
                this.gameProcess = finalProcess;
                this.status.setText(finalMessage);
                this.refreshGameState();
            });
        }, "star-sword-editor-dev-launch");
        worker.setDaemon(true);
        worker.start();
    }
}
