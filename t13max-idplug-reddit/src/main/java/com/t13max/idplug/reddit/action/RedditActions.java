package com.t13max.idplug.reddit.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.t13max.idplug.reddit.service.RedditReaderService;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Reddit 阅读器的全局快捷键动作集合。
 */
public final class RedditActions {
    /**
     * 禁止创建动作集合实例。
     */
    private RedditActions() {
    }

    /**
     * 快捷键动作公共基类。
     */
    private abstract static class ReaderAction extends AnAction {
        /**
         * 执行项目级阅读器命令。
         */
        @Override
        public final void actionPerformed(@NotNull AnActionEvent event) {
            Project project = event.getProject();
            if (project != null) {
                command().accept(RedditReaderService.getInstance(project));
            }
        }

        /**
         * 获取具体阅读器命令。
         */
        protected abstract Consumer<RedditReaderService> command();
    }

    /**
     * 显示或隐藏状态栏组件。
     */
    public static final class ToggleAction extends ReaderAction {
        /**
         * 获取显隐命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::toggleVisible;
        }
    }

    /**
     * 切换到下一条内容。
     */
    public static final class NextAction extends ReaderAction {
        /**
         * 获取下一条命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::next;
        }
    }

    /**
     * 切换到上一条内容。
     */
    public static final class PreviousAction extends ReaderAction {
        /**
         * 获取上一条命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::previous;
        }
    }

    /**
     * 进入当前帖子的评论列表。
     */
    public static final class OpenAction extends ReaderAction {
        /**
         * 获取进入帖子命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::openCurrentPost;
        }
    }

    /**
     * 返回帖子列表。
     */
    public static final class BackAction extends ReaderAction {
        /**
         * 获取返回命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::backToPosts;
        }
    }

    /**
     * 刷新当前专区。
     */
    public static final class RefreshAction extends ReaderAction {
        /**
         * 获取刷新命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::refresh;
        }
    }

    /**
     * 选择 Reddit 专区。
     */
    public static final class CommunityAction extends ReaderAction {
        /**
         * 获取选择专区命令。
         */
        @Override
        protected Consumer<RedditReaderService> command() {
            return RedditReaderService::chooseCommunity;
        }
    }
}
