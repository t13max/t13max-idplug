package com.t13max.idplug.reddit.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.t13max.idplug.reddit.api.RedditApiClient;
import com.t13max.idplug.reddit.api.RedditRssClient;
import com.t13max.idplug.reddit.auth.RedditAuthService;
import com.t13max.idplug.reddit.model.RedditItem;
import com.t13max.idplug.reddit.settings.RedditSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reddit 单行阅读器的项目级状态与业务服务。
 */
@Service(Service.Level.PROJECT)
public final class RedditReaderService implements Disposable {
    private final Project project;
    private final RedditApiClient apiClient = new RedditApiClient();
    private final RedditAuthService authService = new RedditAuthService(apiClient);
    private final RedditRssClient rssClient = ApplicationManager.getApplication().getService(RedditPublicFeedService.class).getClient();
    private final List<Runnable> listeners = new ArrayList<>();
    private List<RedditItem> posts = new ArrayList<>();
    private List<RedditItem> comments = new ArrayList<>();
    private int index;
    private int horizontalOffset;
    private ViewMode viewMode = ViewMode.POSTS;
    private String statusText = "Ready";
    private boolean loading;
    private String feedNotice = "";
    private int postIndex;
    private RedditItem openedPost;
    private long requestVersion;
    private boolean disposed;

    /**
     * 创建项目级阅读服务。
     */
    public RedditReaderService(Project project) {
        this.project = project;
        refresh();
    }

    /**
     * 获取项目级阅读服务。
     */
    public static RedditReaderService getInstance(Project project) {
        return project.getService(RedditReaderService.class);
    }

    /**
     * 添加界面刷新监听器。
     */
    public synchronized void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * 移除界面刷新监听器。
     */
    public synchronized void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * 获取完整状态栏文本。
     */
    public synchronized String getFullText() {
        String content = currentContent();
        int codePointCount = content.codePointCount(0, content.length());
        int safeOffset = Math.min(horizontalOffset, Math.max(0, codePointCount - 1));
        return "\\ " + content.substring(content.offsetByCodePoints(0, safeOffset));
    }

    /**
     * 获取未滚动的完整文本作为提示。
     */
    public synchronized String getTooltipText() {
        return "\\ " + currentContent();
    }

    /**
     * 判断状态栏组件是否显示。
     */
    public boolean isVisible() {
        return RedditSettings.getInstance().getState().visible;
    }

    /**
     * 切换状态栏组件显隐。
     */
    public void toggleVisible() {
        RedditSettings.Data settings = RedditSettings.getInstance().getState();
        settings.visible = !settings.visible;
        fireChanged();
    }

    /**
     * 使用滚轮横向移动当前文本。
     */
    public synchronized void scrollHorizontally(int wheelRotation) {
        String content = currentContent();
        int length = content.codePointCount(0, content.length());
        horizontalOffset = Math.max(0, Math.min(Math.max(0, length - 1), horizontalOffset + wheelRotation * 6));
        fireChanged();
    }

    /**
     * 显示下一条内容。
     */
    public synchronized void next() {
        if (loading) {
            return;
        }
        int size = currentItems().size();
        if (size > 0) {
            statusText = "";
            index = (index + 1) % size;
            horizontalOffset = 0;
            fireChanged();
        }
    }

    /**
     * 显示上一条内容。
     */
    public synchronized void previous() {
        if (loading) {
            return;
        }
        int size = currentItems().size();
        if (size > 0) {
            statusText = "";
            index = (index - 1 + size) % size;
            horizontalOffset = 0;
            fireChanged();
        }
    }

    /**
     * 从帖子列表进入当前帖子的评论列表。
     */
    public void openCurrentPost() {
        RedditItem post;
        synchronized (this) {
            if (viewMode != ViewMode.POSTS || posts.isEmpty() || loading) {
                return;
            }
            post = posts.get(index);
            postIndex = index;
        }
        loadComments(post);
    }

    /**
     * 返回帖子列表。
     */
    public synchronized void backToPosts() {
        requestVersion++;
        loading = false;
        if (viewMode == ViewMode.COMMENTS || openedPost != null) {
            viewMode = ViewMode.POSTS;
            index = Math.min(postIndex, Math.max(0, posts.size() - 1));
            openedPost = null;
            horizontalOffset = 0;
            feedNotice = "";
            statusText = posts.isEmpty() ? "No posts" : "";
        }
        statusText = posts.isEmpty() ? "No posts; choose a community and refresh" : "";
        fireChanged();
    }

    /**
     * 刷新当前专区的帖子。
     */
    public void refresh() {
        if (!isPublicReadOnly() && !hasClientId()) {
            setStatus("Set your Reddit Client ID first");
            return;
        }
        if (viewMode == ViewMode.COMMENTS && openedPost != null) {
            loadComments(openedPost);
            return;
        }
        setLoading("Loading r/" + settings().subreddit + "...");
        long version = ++requestVersion;
        String community = settings().subreddit;
        String sort = settings().sort;
        CompletableFuture<RedditRssClient.FeedResult> future = isPublicReadOnly() ? rssClient.loadPosts(community, sort) : tokenRequest(token -> apiClient.loadPosts(token, community, sort)).thenApply(items -> new RedditRssClient.FeedResult(items, ""));
        observe(future, version, values -> {
                posts = values.items;
                comments = new ArrayList<>();
                viewMode = ViewMode.POSTS;
                index = 0;
                openedPost = null;
                postIndex = 0;
                horizontalOffset = 0;
                feedNotice = values.notice;
                statusText = posts.isEmpty() ? "No posts in r/" + settings().subreddit : "";
        });
    }

    /**
     * 打开输入框选择 Reddit 专区。
     */
    public void chooseCommunity() {
        if (isPublicReadOnly() || !hasClientId()) {
            showCommunityDialog(List.of());
            return;
        }
        setLoading("Loading subscribed communities...");
        withAccessToken(apiClient::loadSubscribedCommunities, this::showCommunityDialog);
    }

    /**
     * 切换帖子排序方式。
     */
    public void setSort(String sort) {
        if (!List.of("hot", "new", "top").contains(sort)) {
            return;
        }
        settings().sort = sort;
        backToPosts();
        refresh();
    }

    /**
     * 判断当前是否为不需要账号或 Client ID 的公开只读模式。
     */
    public boolean isPublicReadOnly() {
        return settings().publicReadOnly;
    }

    /**
     * 切换数据源并使旧模式的未完成请求失效。
     */
    public synchronized void setPublicReadOnly(boolean enabled) {
        settings().publicReadOnly = enabled;
        requestVersion++;
        posts = new ArrayList<>();
        comments = new ArrayList<>();
        viewMode = ViewMode.POSTS;
        openedPost = null;
        index = 0;
        postIndex = 0;
        feedNotice = "";
        refresh();
    }

    /**
     * 根据当前模式加载评论，并明确区分空 RSS 和真正的空评论。
     */
    private void loadComments(RedditItem post) {
        openedPost = post;
        setLoading("Loading comments...");
        long version = ++requestVersion;
        boolean publicMode = isPublicReadOnly();
        CompletableFuture<RedditRssClient.FeedResult> future = publicMode ? rssClient.loadComments(post.getId(), post.getSubreddit()) : tokenRequest(token -> apiClient.loadComments(token, post.getId())).thenApply(items -> new RedditRssClient.FeedResult(items, ""));
        observe(future, version, values -> {
            comments = values.items;
            viewMode = ViewMode.COMMENTS;
            index = 0;
            horizontalOffset = 0;
            feedNotice = values.notice;
            statusText = comments.isEmpty() ? (publicMode ? "No comments exposed by this RSS feed; use Back to Posts" : "This post has no comments") : "";
        });
    }

    /**
     * 设置 OAuth Client ID。
     */
    public void configureClientId() {
        ApplicationManager.getApplication().invokeLater(() -> {
            String value = Messages.showInputDialog(project, "Enter the Client ID of your Reddit installed app.\nIts redirect URI must be:\n" + RedditAuthService.REDIRECT_URI, "Set Reddit Client ID", Messages.getQuestionIcon(), settings().clientId, null);
            if (value != null) {
                settings().clientId = value.trim();
                setStatus(settings().clientId.isBlank() ? "Client ID is not configured" : "Client ID saved");
            }
        });
    }

    /**
     * 在系统浏览器中登录 Reddit。
     */
    public void login() {
        if (!hasClientId()) {
            configureClientId();
            return;
        }
        setStatus("Complete Reddit sign-in in your browser...");
        authService.login(settings().clientId).whenComplete((username, error) -> {
            if (error != null) {
                setStatus(errorMessage(error));
                return;
            }
            setStatus("Signed in as u/" + username);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed) {
                    setPublicReadOnly(false);
                }
            });
        });
    }

    /**
     * 退出 Reddit 登录。
     */
    public void logout() {
        authService.logout();
        setPublicReadOnly(true);
    }

    /**
     * 获取订阅专区并将结果写入提示文本。
     */
    public void showSubscribedCommunities() {
        if (isPublicReadOnly()) {
            setStatus("Subscriptions require optional OAuth mode; use Choose Community instead");
            return;
        }
        if (!hasClientId()) {
            setStatus("Set your Client ID first");
            return;
        }
        setLoading("Loading subscribed communities...");
        withAccessToken(apiClient::loadSubscribedCommunities, values -> {
            synchronized (this) {
                loading = false;
                statusText = values.isEmpty() ? "No subscribed communities found" : "Subscribed: " + String.join(" · ", values);
                horizontalOffset = 0;
            }
            fireChanged();
        });
    }

    /**
     * 判断是否已保存 OAuth Client ID。
     */
    public boolean hasClientId() {
        return !settings().clientId.isBlank();
    }

    /**
     * 判断是否存在持久登录。
     */
    public boolean hasSavedLogin() {
        return authService.hasSavedLogin();
    }

    /**
     * 获取当前专区名称。
     */
    public String getCommunity() {
        return settings().subreddit;
    }

    /**
     * 获取当前排序方式。
     */
    public String getSort() {
        return settings().sort;
    }

    /**
     * 执行需要访问令牌的异步请求。
     */
    private <T> void withAccessToken(Function<String, CompletableFuture<T>> request, Consumer<T> success) {
        observe(tokenRequest(request), ++requestVersion, success);
    }

    /**
     * 在后台访问密码库，公开 RSS 路径不会调用此方法。
     */
    private <T> CompletableFuture<T> tokenRequest(Function<String, CompletableFuture<T>> request) {
        String clientId = settings().clientId;
        return CompletableFuture.supplyAsync(() -> authService.requireAccessToken(clientId)).thenCompose(Function.identity()).thenCompose(request);
    }

    /**
     * 在界面线程提交最新请求的结果，忽略切换专区或退出后的旧响应。
     */
    private <T> void observe(CompletableFuture<T> future, long version, Consumer<T> success) {
        future.whenComplete((value, error) -> ApplicationManager.getApplication().invokeLater(() -> {
            synchronized (this) {
                if (disposed || project.isDisposed() || version != requestVersion) {
                    return;
                }
                loading = false;
                if (error != null) {
                    setStatus(errorMessage(error));
                } else {
                    success.accept(value);
                    fireChanged();
                }
            }
        }));
    }

    /**
     * 设置加载状态。
     */
    private synchronized void setLoading(String text) {
        loading = true;
        statusText = text;
        horizontalOffset = 0;
        fireChanged();
    }

    /**
     * 设置普通状态文本。
     */
    private synchronized void setStatus(String text) {
        loading = false;
        statusText = text;
        horizontalOffset = 0;
        fireChanged();
    }

    /**
     * 获取当前视图中的条目。
     */
    private List<RedditItem> currentItems() {
        return viewMode == ViewMode.POSTS ? posts : comments;
    }

    /**
     * 组合当前状态栏内容。
     */
    private String currentContent() {
        List<RedditItem> items = currentItems();
        if (loading || !statusText.isBlank() || items.isEmpty()) {
            return statusText;
        }
        RedditItem item = items.get(Math.min(index, items.size() - 1));
        String position = "[" + (index + 1) + "/" + items.size() + "] ";
        String notice = feedNotice.isBlank() ? "" : feedNotice + " | ";
        return notice + (viewMode == ViewMode.POSTS ? "r/" + item.getSubreddit() + " " + position + item.getText() : "u/" + item.getAuthor() + " " + position + item.getText());
    }

    /**
     * 获取可变设置数据。
     */
    private RedditSettings.Data settings() {
        return RedditSettings.getInstance().getState();
    }

    /**
     * 显示可编辑的专区选择对话框。
     */
    private void showCommunityDialog(List<String> communities) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) {
                return;
            }
            synchronized (this) {
                loading = false;
                statusText = "";
            }
            String[] choices = communities.toArray(String[]::new);
            String prompt = isPublicReadOnly() ? "Enter a public community name (no sign-in required):" : "Choose a subscribed community or enter a community name:";
            String value = Messages.showEditableChooseDialog(prompt, "Choose Reddit Community", Messages.getQuestionIcon(), choices, settings().subreddit, null);
            if (value == null) {
                fireChanged();
                return;
            }
            String normalized = value.trim().replaceFirst("^(?i)r/", "");
            if (!normalized.matches("[A-Za-z0-9_]+")) {
                Messages.showErrorDialog(project, "A community name may contain only letters, numbers, and underscores.", "Reddit Reader");
                fireChanged();
                return;
            }
            settings().subreddit = normalized;
            backToPosts();
            refresh();
        });
    }

    /**
     * 提取异步异常中的易读信息。
     */
    private String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof IllegalStateException || cause instanceof IllegalArgumentException) {
            return cause.getMessage() == null ? "Reddit request failed" : cause.getMessage();
        }
        return "Reddit connection failed; check your network and retry later";
    }

    /**
     * 通知全部状态栏界面刷新。
     */
    private void fireChanged() {
        List<Runnable> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(listeners);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) {
                snapshot.forEach(Runnable::run);
            }
        });
    }

    /**
     * 释放登录回调与监听器。
     */
    @Override
    public synchronized void dispose() {
        disposed = true;
        requestVersion++;
        authService.dispose();
        listeners.clear();
    }

    /**
     * 阅读器当前视图。
     */
    private enum ViewMode {
        POSTS,
        COMMENTS
    }
}
