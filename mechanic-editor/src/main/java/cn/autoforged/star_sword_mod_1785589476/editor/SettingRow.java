package cn.autoforged.star_sword_mod_1785589476.editor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;

/**
 * 一行开关：左侧勾选框，右侧两行文字（名字 + 人话说明），整行悬停都显示详细说明。
 *
 * <p>照搬自 stardome-editor 的 {@code FieldRow} 的三条经验（美术部分不要）：
 * <ul>
 *   <li>两行文字：第一行是技术名字，第二行是给普通玩家的人话说明；详细原理放悬停气泡，
 *       不占版面；</li>
 *   <li>行高由字体算，不写死像素（写死会把汉字下半截裁掉）；</li>
 *   <li>把气泡热区扩到整行（递归给缺 tooltip 的子组件补上），而不是只有那个小圈。</li>
 * </ul>
 */
final class SettingRow extends JPanel {

    private final SettingsCatalog.Def def;
    private final JCheckBox box;

    SettingRow(SettingsCatalog.Def def, boolean selected) {
        this.def = def;
        setLayout(new BorderLayout(8, 0));
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        box = new JCheckBox();
        box.setOpaque(false);
        box.setSelected(selected);
        box.setFont(box.getFont().deriveFont(Font.PLAIN, 12f));
        add(box, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel title = new JLabel(def.title + (def.needsOp ? "　（需 op2）" : ""));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));

        JLabel plain = new JLabel(def.plain);
        plain.setFont(plain.getFont().deriveFont(Font.PLAIN, 11f));
        plain.setForeground(new java.awt.Color(0x6E6E6E));
        plain.setAlignmentX(Component.LEFT_ALIGNMENT);

        text.add(title);
        text.add(plain);
        text.add(Box.createVerticalStrut(1));
        add(text, BorderLayout.CENTER);

        Dimension size = new Dimension(Integer.MAX_VALUE,
                title.getPreferredSize().height + plain.getPreferredSize().height + 12);
        setMaximumSize(size);
        setPreferredSize(size);

        // 整行热区：把详细说明补到所有还没有 tooltip 的子组件上。
        applyTooltip(this, def.detail);
    }

    private static void applyTooltip(Component component, String html) {
        if (component == null) {
            return;
        }
        if (component instanceof JComponent jc && jc.getToolTipText() == null) {
            jc.setToolTipText(html);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyTooltip(child, html);
            }
        }
    }

    SettingsCatalog.Def def() {
        return def;
    }

    boolean isSelected() {
        return box.isSelected();
    }

    void setSelected(boolean value) {
        box.setSelected(value);
    }

    /** 全服项在编辑器里也允许先调（真正的 op 校验在进服后由服务端做）。 */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        box.setEnabled(enabled);
    }
}
