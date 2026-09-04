package com.t13max.idplug.heybox;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** 创建固定宽度的隐蔽状态栏阅读器。 */
public final class HeyboxWidgetFactory implements StatusBarWidgetFactory {
    /** 返回唯一标识。 */
    @Override
    public @NotNull String getId() { return "T13maxHeyboxLine"; }

    /** 返回组件设置名称。 */
    @Override
    public @NotNull String getDisplayName() { return UiLanguage.text("Heybox Line Reader"); }

    /** 为项目创建展示组件。 */
    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new Widget(); }

    /** 默认显示紧凑入口。 */
    @Override
    public boolean isEnabledByDefault() { return true; }

    /** 只负责显示，共用应用级账号与浏览器。 */
    private static final class Widget implements CustomStatusBarWidget {
        private final HeyboxReader reader = HeyboxReader.get();
        private final JPanel panel = new JPanel(new BorderLayout());
        private final JLabel label = new LineLabel();
        private final Runnable listener = this::update;

        /** 注册点击和滚轮，同时覆盖标签和面板。 */
        private Widget() {
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            panel.add(label, BorderLayout.CENTER);
            label.putClientProperty("html.disable", true);
            MouseAdapter mouse = new MouseAdapter() {
                /** 点击文字打开操作菜单。 */
                @Override
                public void mouseClicked(MouseEvent event) { if (SwingUtilities.isLeftMouseButton(event)) menu(); }
            };
            for (JComponent target : new JComponent[]{panel, label}) {
                target.addMouseListener(mouse);
                target.addMouseWheelListener(event -> { reader.scroll(event.getWheelRotation(), label.getFontMetrics(label.getFont()), label.getWidth()); event.consume(); });
            }
            reader.addListener(listener);
            reader.startSession();
            update();
        }

        /** 更新固定宽度及可见状态，不显示长文本提示框。 */
        private void update() {
            label.setText(reader.displayText());
            panel.setPreferredSize(new Dimension(310, label.getPreferredSize().height));
            panel.setMaximumSize(panel.getPreferredSize());
            panel.setVisible(reader.isVisible());
            panel.revalidate();
            panel.repaint();
        }

        /** 只保留账号、列表及界面设置，阅读导航使用快捷键。 */
        private void menu() {
            JPopupMenu menu = new JPopupMenu();
            if (reader.isSignedIn()) add(menu, "Sign out", reader::signOut);
            else add(menu, "Scan QR to sign in", reader::signIn);
            add(menu, "Official Page", reader::showBrowser);
            menu.addSeparator();
            if (!reader.insidePost()) {
                add(menu, "Home Feed", reader::home);
                add(menu, "Choose Community...", reader::chooseCommunity);
                add(menu, "Refresh List", reader::refresh);
            }
            if (!reader.refreshResult().isBlank()) { JMenuItem result = new JMenuItem(UiLanguage.text(reader.refreshResult())); result.setEnabled(false); menu.add(result); }
            if (!reader.sessionNotice().isBlank()) { JMenuItem result = new JMenuItem(UiLanguage.text(reader.sessionNotice())); result.setEnabled(false); menu.add(result); }
            JMenu language = new JMenu("语言 / Language");
            ButtonGroup group = new ButtonGroup();
            JRadioButtonMenuItem chinese = new JRadioButtonMenuItem("中文", !UiLanguage.get().english());
            JRadioButtonMenuItem english = new JRadioButtonMenuItem("English", UiLanguage.get().english());
            chinese.addActionListener(event -> reader.setEnglish(false));
            english.addActionListener(event -> reader.setEnglish(true));
            group.add(chinese); group.add(english); language.add(chinese); language.add(english); menu.add(language);
            add(menu, "Hide", reader::toggle);
            menu.show(panel, 0, -menu.getPreferredSize().height);
        }

        /** 增加一个命令菜单项。 */
        private void add(JPopupMenu menu, String text, Runnable command) { JMenuItem item = new JMenuItem(UiLanguage.text(text)); item.addActionListener(event -> command.run()); menu.add(item); }

        /** 直接裁剪绘制完整文字，避免 JLabel 省略号及按字符滚动造成空白。 */
        private final class LineLabel extends JLabel {
            /** 使用实际视口宽度在每次绘制时重新限制滚动。 */
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D canvas = (Graphics2D) graphics.create();
                try {
                    canvas.setFont(getFont());
                    canvas.setColor(getForeground());
                    canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    FontMetrics metrics = canvas.getFontMetrics();
                    int shift = TextScroll.clamp(reader.scrollOffset(), metrics.stringWidth(getText()), getWidth());
                    canvas.clipRect(0, 0, getWidth(), getHeight());
                    canvas.drawString(getText(), -shift, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                } finally { canvas.dispose(); }
            }
        }

        /** 返回状态栏标识。 */
        @Override
        public @NotNull String ID() { return "T13maxHeyboxLine"; }

        /** 返回展示组件。 */
        @Override
        public @NotNull JComponent getComponent() { return panel; }

        /** 关闭项目时只移除监听，不影响其他项目登录。 */
        @Override
        public void dispose() { reader.removeListener(listener); }
    }
}
