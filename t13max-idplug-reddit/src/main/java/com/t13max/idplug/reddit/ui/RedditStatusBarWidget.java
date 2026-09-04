package com.t13max.idplug.reddit.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.t13max.idplug.reddit.service.RedditReaderService;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * 可使用滚轮横向浏览内容的固定宽度状态栏组件。
 */
public final class RedditStatusBarWidget implements CustomStatusBarWidget {
    public static final String ID = "T13maxRedditStatusBar";
    private static final int WIDTH = 310;
    private final RedditReaderService service;
    private final JPanel panel = new JPanel(new BorderLayout());
    private final JLabel label = new JLabel();
    private final Runnable refreshListener = this::refreshView;

    /**
     * 创建 Reddit 状态栏组件。
     */
    public RedditStatusBarWidget(Project project) {
        service = RedditReaderService.getInstance(project);
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.add(label, BorderLayout.CENTER);
        MouseAdapter clickHandler = new MouseAdapter() {
            /**
             * 点击状态栏时打开紧凑菜单。
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    showMenu();
                }
            }
        };
        panel.addMouseWheelListener(this::handleWheel);
        label.addMouseWheelListener(this::handleWheel);
        panel.addMouseListener(clickHandler);
        label.addMouseListener(clickHandler);
        service.addListener(refreshListener);
        refreshView();
    }

    /**
     * 获取组件唯一标识。
     */
    @Override
    public @NotNull String ID() {
        return ID;
    }

    /**
     * 获取 Swing 状态栏组件。
     */
    @Override
    public @NotNull JPanel getComponent() {
        return panel;
    }

    /**
     * 处理鼠标滚轮并横向移动文本。
     */
    private void handleWheel(MouseWheelEvent event) {
        service.scrollHorizontally(event.getWheelRotation());
        event.consume();
    }

    /**
     * 刷新状态栏文本、提示和显隐状态。
     */
    private void refreshView() {
        label.setText(service.getFullText());
        label.setToolTipText(service.getTooltipText());
        panel.setPreferredSize(new Dimension(WIDTH, label.getPreferredSize().height));
        panel.setMaximumSize(new Dimension(WIDTH, label.getPreferredSize().height));
        panel.setVisible(service.isVisible());
        panel.revalidate();
        panel.repaint();
    }

    /**
     * 显示登录、专区和排序菜单。
     */
    private void showMenu() {
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem publicMode = new JCheckBoxMenuItem("Public RSS (no sign-in)", service.isPublicReadOnly());
        publicMode.addActionListener(event -> service.setPublicReadOnly(publicMode.isSelected()));
        menu.add(publicMode);
        menu.addSeparator();
        addItem(menu, "Previous Item", service::previous);
        addItem(menu, "Next Item", service::next);
        addItem(menu, "Open Post", service::openCurrentPost);
        addItem(menu, "Back to Posts", service::backToPosts);
        menu.addSeparator();
        addItem(menu, "Choose Community... (r/" + service.getCommunity() + ")", service::chooseCommunity);
        if (!service.isPublicReadOnly()) {
            addItem(menu, "Show Subscribed Communities", service::showSubscribedCommunities);
        }
        addItem(menu, "Hot" + selected("hot"), () -> service.setSort("hot"));
        addItem(menu, "New" + selected("new"), () -> service.setSort("new"));
        addItem(menu, "Top" + selected("top"), () -> service.setSort("top"));
        addItem(menu, "Refresh", service::refresh);
        menu.addSeparator();
        JMenu advanced = new JMenu("Optional OAuth...");
        addItem(advanced.getPopupMenu(), "Set Client ID...", service::configureClientId);
        addItem(advanced.getPopupMenu(), "Sign In to Reddit", service::login);
        addItem(advanced.getPopupMenu(), "Use OAuth Mode", () -> service.setPublicReadOnly(false));
        addItem(advanced.getPopupMenu(), "Sign Out and Use Public RSS", service::logout);
        menu.add(advanced);
        menu.show(panel, 0, -menu.getPreferredSize().height);
    }

    /**
     * 返回排序项的选中标记。
     */
    private String selected(String sort) {
        return sort.equals(service.getSort()) ? " ✓" : "";
    }

    /**
     * 向弹出菜单添加命令项。
     */
    private void addItem(JPopupMenu menu, String text, Runnable command) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> command.run());
        menu.add(item);
    }

    /**
     * 释放界面监听器。
     */
    @Override
    public void dispose() {
        service.removeListener(refreshListener);
    }
}
