package com.t13max.idplug.wechat.windows;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** 在 IDE 右侧注册与 Maven 同级的微信工具窗口。 */
public final class WechatToolWindowFactory implements ToolWindowFactory, DumbAware {
    public static final String ID = "WeChat";

    /** 使用内容管理器安装面板并绑定销毁生命周期。 */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        WechatPanel panel = new WechatPanel(project, toolWindow);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
