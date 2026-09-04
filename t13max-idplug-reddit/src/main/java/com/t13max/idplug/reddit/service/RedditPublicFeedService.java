package com.t13max.idplug.reddit.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.t13max.idplug.reddit.api.RedditRssClient;

/**
 * 在多个项目之间共享公开 RSS 缓存和限流状态。
 */
@Service(Service.Level.APP)
public final class RedditPublicFeedService implements Disposable {
    private final RedditRssClient client = new RedditRssClient();

    /**
     * 获取当前 IDE 进程共用的免登录客户端。
     */
    public RedditRssClient getClient() {
        return client;
    }

    /**
     * 卸载插件时释放 HTTP 客户端和缓存。
     */
    @Override
    public void dispose() {
        client.close();
    }
}
