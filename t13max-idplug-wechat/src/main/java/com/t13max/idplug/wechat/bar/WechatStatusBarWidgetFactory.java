package com.t13max.idplug.wechat.bar;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/** 创建 UTF-8 编码组件附近的微信状态栏提醒。 */
public final class WechatStatusBarWidgetFactory implements StatusBarWidgetFactory {
    /** 工厂和组件使用相同标识。 */
    @Override
    public @NotNull String getId() { return WechatStatusBarWidget.ID; }

    /** 返回状态栏设置中的名称。 */
    @Override
    public @NotNull String getDisplayName() { return "微信新消息提醒"; }

    /** 为当前项目创建提醒图标。 */
    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new WechatStatusBarWidget(project); }
}
