package com.t13max.idplug.heybox;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/** 按注册标识分派全局快捷键，不与 Reddit 默认按键冲突。 */
public final class HeyboxAction extends AnAction {
    /** 快捷键动作名称随界面语言切换。 */
    @Override
    public void update(@NotNull AnActionEvent event) {
        String id = com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(this);
        String name = switch (id) { case "Heybox.Toggle" -> "Show or Hide"; case "Heybox.Next" -> "Next Item"; case "Heybox.Previous" -> "Previous Item"; case "Heybox.Open" -> "Enter or Exit Post"; case "Heybox.Back" -> "Back to List"; default -> "Scan QR to sign in"; };
        event.getPresentation().setText(UiLanguage.text("Heybox Line Reader") + ": " + UiLanguage.text(name));
    }

    /** 执行对应的阅读器命令。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        String id = com.intellij.openapi.actionSystem.ActionManager.getInstance().getId(this);
        HeyboxReader reader = HeyboxReader.get();
        switch (id) {
            case "Heybox.Toggle" -> reader.toggle();
            case "Heybox.Next" -> reader.move(1);
            case "Heybox.Previous" -> reader.move(-1);
            case "Heybox.Open" -> reader.enterOrExit();
            case "Heybox.Back" -> reader.back();
            case "Heybox.Login" -> reader.signIn();
            default -> { }
        }
    }
}
