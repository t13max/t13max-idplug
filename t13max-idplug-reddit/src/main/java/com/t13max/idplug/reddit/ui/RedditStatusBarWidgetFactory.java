package com.t13max.idplug.reddit.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Reddit 状态栏组件工厂。
 */
public final class RedditStatusBarWidgetFactory implements StatusBarWidgetFactory {
    /**
     * 获取组件唯一标识。
     */
    @Override
    public @NotNull String getId() {
        return RedditStatusBarWidget.ID;
    }

    /**
     * 获取状态栏设置中的显示名称。
     */
    @Override
    public @NotNull String getDisplayName() {
        return "Reddit Line Reader";
    }

    /**
     * 创建项目状态栏组件。
     */
    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new RedditStatusBarWidget(project);
    }

    /**
     * 默认启用状态栏组件。
     */
    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
