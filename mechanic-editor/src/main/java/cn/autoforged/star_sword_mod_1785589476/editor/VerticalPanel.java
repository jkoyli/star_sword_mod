package cn.autoforged.star_sword_mod_1785589476.editor;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;

/**
 * 放进 {@link javax.swing.JScrollPane} 的纵向面板：**宽度跟随视口，高度自己长**。
 *
 * <p>踩过的坑：普通 {@code JPanel} + {@code BoxLayout} 塞进 JScrollPane 时，BoxLayout 会按子组件的
 * {@code maximumSize} 去算自身首选宽度，而我们的行为了横向铺满都写了
 * {@code maximumSize.width = Integer.MAX_VALUE}，于是内容面板宽度被算成 2147483647，
 * 所有子组件被布局到屏幕外——窗口看起来就是**一片空白**（只剩顶部标题和底部状态条）。
 *
 * <p>修法就是隔壁项目 {@code PagePanel} 的做法：实现 {@link Scrollable}，让
 * {@link #getScrollableTracksViewportWidth()} 返回 {@code true}，视口就会把宽度钉成自己的宽度，
 * 横向滚动条也就不可能出现。
 */
final class VerticalPanel extends JPanel implements Scrollable {

    VerticalPanel() {
        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
        setBackground(Color.WHITE);
        setOpaque(true);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(32, visibleRect.height - 32);
    }

    /** 宽度永远等于视口宽度：这是修「空白窗口」的关键一行。 */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** 高度不跟随视口，否则内容永远不用滚动。 */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
