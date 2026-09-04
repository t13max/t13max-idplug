package com.t13max.idplug.wechat.windows;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/** 使用 Alt+W 切换微信工具窗口显隐，索引期间也可使用。 */
public final class ToggleWechatAction extends DumbAwareAction {
    /** 执行当前项目的微信窗口显隐切换。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) { return; }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(WechatToolWindowFactory.ID);
        if (window == null) { return; }
        if (window.isVisible()) { window.hide(); } else { window.activate(null); }
    }

    /** 无项目时禁用快捷动作。 */
    @Override
    public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }

    /** 在后台更新不涉及 Swing 的动作状态。 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
