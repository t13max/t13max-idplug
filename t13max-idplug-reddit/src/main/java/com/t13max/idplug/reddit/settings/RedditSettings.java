package com.t13max.idplug.reddit.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * Reddit 阅读器的非敏感设置。
 */
@Service(Service.Level.APP)
@State(name = "T13maxRedditSettings", storages = @Storage("t13maxReddit.xml"))
public final class RedditSettings implements PersistentStateComponent<RedditSettings.Data> {
    private Data data = new Data();

    /**
     * 获取应用级设置实例。
     */
    public static RedditSettings getInstance() {
        return ApplicationManager.getApplication().getService(RedditSettings.class);
    }

    /**
     * 获取需要持久化的数据。
     */
    @Override
    public @NotNull Data getState() {
        return data;
    }

    /**
     * 载入持久化数据。
     */
    @Override
    public void loadState(@NotNull Data state) {
        data = state;
    }

    /**
     * Reddit 阅读器设置数据。
     */
    public static final class Data {
        public String clientId = "";
        public String subreddit = "programming";
        public String sort = "hot";
        public boolean visible = true;
        public boolean publicReadOnly = true;

        /**
         * 创建默认设置数据。
         */
        public Data() {
        }
    }
}
