package com.t13max.idplug.wechat.service;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/** 项目打开后在后台恢复微信，使隐藏工具窗口时也能接收提醒。 */
public final class WechatStartupActivity implements StartupActivity.DumbAware {
    /** 启动共享服务，多个项目不会重复登录。 */
    @Override
    public void runActivity(@NotNull Project project) { WechatService.getInstance().start(); }
}
