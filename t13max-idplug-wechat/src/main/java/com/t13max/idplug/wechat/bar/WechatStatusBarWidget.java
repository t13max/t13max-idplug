package com.t13max.idplug.wechat.bar;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Consumer;
import com.t13max.idplug.wechat.service.WechatService;
import com.t13max.idplug.wechat.windows.WechatToolWindowFactory;
import java.awt.event.MouseEvent;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** 状态栏仅显示微信图标和未读红点，任何提示都不包含消息正文。 */
public final class WechatStatusBarWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {
    public static final String ID = "WechatStatusBar";
    private static final Icon NORMAL = IconLoader.getIcon("/icons/wechat.svg", WechatStatusBarWidget.class);
    private static final Icon UNREAD = IconLoader.getIcon("/icons/wechatUnread.svg", WechatStatusBarWidget.class);
    private final Project project;
    private final WechatService service = WechatService.getInstance();
    private StatusBar statusBar;

    /** 绑定状态栏所属项目。 */
    public WechatStatusBarWidget(Project project) { this.project = project; }

    /** 返回与工厂一致的组件标识。 */
    @Override
    public @NotNull String ID() { return ID; }

    /** 安装状态栏并订阅未读变化。 */
    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        service.subscribe(this, () -> { if (this.statusBar != null && !project.isDisposed()) { this.statusBar.updateWidget(ID); } });
        service.start();
    }

    /** 使用平台标准图标展示接口。 */
    @Override
    public WidgetPresentation getPresentation() { return this; }

    /** 有未读时显示红点，不展示联系人或消息内容。 */
    @Override
    public @NotNull Icon getIcon() { return service.history().unreadTotal() > 0 ? UNREAD : NORMAL; }

    /** 悬浮提示只包含是否有新消息和快捷键。 */
    @Override
    public @NotNull String getTooltipText() { return service.history().unreadTotal() > 0 ? "微信有新消息 · Alt+W 打开" : "微信 · Alt+W 显示或隐藏"; }

    /** 点击图标打开微信窗口，不自动清除其他会话的未读。 */
    @Override
    public Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            if (project.isDisposed()) { return; }
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(WechatToolWindowFactory.ID);
            if (window != null) { window.activate(null); }
        };
    }

    /** 释放状态栏引用，监听由平台销毁链移除。 */
    @Override
    public void dispose() { statusBar = null; }
}
