package com.t13max.idplug.reddit.api;

import com.t13max.idplug.reddit.model.RedditItem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 免登录 RSS 客户端，提供有界内存缓存、并发去重和请求冷却。
 */
public final class RedditRssClient implements AutoCloseable {
    private static final long CACHE_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final long MIN_REQUEST_INTERVAL = 2_000;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private final Map<URI, CacheEntry> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<URI, CompletableFuture<FeedResult>> pending = new LinkedHashMap<>();
    private final Function<URI, CompletableFuture<FeedResponse>> transport;
    private final LongSupplier clock;
    private final HttpClient httpClient;
    private long nextRequestAt;
    private long blockedUntil;
    private boolean closed;

    /**
     * 创建使用真实 HTTP 传输的客户端。
     */
    public RedditRssClient() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        transport = this::download;
        clock = System::currentTimeMillis;
    }

    /**
     * 注入传输和时钟，以便离线验证缓存与限流行为。
     */
    RedditRssClient(Function<URI, CompletableFuture<FeedResponse>> transport, LongSupplier clock) {
        this.httpClient = null;
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * 读取公开专区的指定排序订阅源。
     */
    public CompletableFuture<FeedResult> loadPosts(String subreddit, String sort) {
        if (subreddit == null || !subreddit.matches("[A-Za-z0-9_]+") || !List.of("hot", "new", "top").contains(sort)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid community or sort order"));
        }
        URI uri = URI.create("https://www.reddit.com/r/" + subreddit.toLowerCase(java.util.Locale.ROOT) + "/" + sort + "/.rss?limit=50");
        return load(uri, false, subreddit);
    }

    /**
     * 尝试读取公开帖子的有限评论订阅源。
     */
    public CompletableFuture<FeedResult> loadComments(String postId, String subreddit) {
        if (postId == null || !postId.matches("[A-Za-z0-9]+")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid post ID"));
        }
        return load(URI.create("https://www.reddit.com/comments/" + postId + "/.rss?limit=100"), true, subreddit);
    }

    /**
     * 优先返回缓存，并限制未缓存请求的频率。
     */
    private synchronized CompletableFuture<FeedResult> load(URI uri, boolean comments, String subreddit) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("RSS reader is closed"));
        }
        long now = clock.getAsLong();
        CacheEntry existing = cache.get(uri);
        if (existing != null && now - existing.createdAt < CACHE_MILLIS) {
            return CompletableFuture.completedFuture(new FeedResult(existing.items, ""));
        }
        if (pending.containsKey(uri)) {
            return pending.get(uri);
        }
        long availableAt = Math.max(blockedUntil, nextRequestAt);
        if (now < availableAt) {
            String message = "RSS cooldown: retry in " + Math.max(1, (availableAt - now + 999) / 1000) + "s";
            return existing == null ? CompletableFuture.failedFuture(new IllegalStateException(message)) : CompletableFuture.completedFuture(new FeedResult(existing.items, "Cached RSS; " + message));
        }
        nextRequestAt = now + MIN_REQUEST_INTERVAL;
        CompletableFuture<FeedResult> result = new CompletableFuture<>();
        pending.put(uri, result);
        try {
            transport.apply(uri).thenApply(response -> parseResponse(response, comments, subreddit)).whenComplete((items, error) -> finish(uri, items, error, result));
        } catch (Exception exception) {
            finish(uri, null, exception, result);
        }
        return result;
    }

    /**
     * 下载订阅源并限制响应大小，避免无限占用内存。
     */
    private CompletableFuture<FeedResponse> download(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", "T13maxRedditReader/1.0.2").header("Accept", "application/atom+xml, application/xml;q=0.9").GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            try (var stream = response.body()) {
                byte[] bytes = response.statusCode() == 200 ? stream.readNBytes(MAX_BODY_BYTES + 1) : new byte[0];
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new IllegalStateException("Reddit RSS exceeds the 2 MB size limit");
                }
                return new FeedResponse(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), response.headers().firstValue("Retry-After").orElse(""));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to read Reddit RSS; check your network", exception);
            }
        });
    }

    /**
     * 区分服务端限流、访问限制和有效的 Atom 响应。
     */
    private List<RedditItem> parseResponse(FeedResponse response, boolean comments, String subreddit) {
        if (response.status == 429) {
            synchronized (this) {
                blockedUntil = Math.max(blockedUntil, clock.getAsLong() + retryMillis(response.retryAfter));
            }
            throw new IllegalStateException("Reddit RSS rate limited (429); wait at least 5 minutes before refreshing");
        }
        if (response.status != 200) {
            throw new IllegalStateException("Public RSS unavailable (HTTP " + response.status + "); this feed may be restricted");
        }
        return RedditRssParser.parse(response.body, comments, subreddit);
    }

    /**
     * 处理请求结果，失败时可继续返回过期的本地缓存。
     */
    private synchronized void finish(URI uri, List<RedditItem> items, Throwable error, CompletableFuture<FeedResult> result) {
        pending.remove(uri);
        if (closed) {
            result.completeExceptionally(new IllegalStateException("RSS reader is closed"));
        } else if (error == null) {
            cache.put(uri, new CacheEntry(items, clock.getAsLong()));
            while (cache.size() > 32) {
                cache.remove(cache.keySet().iterator().next());
            }
            result.complete(new FeedResult(items, ""));
        } else {
            nextRequestAt = Math.max(nextRequestAt, clock.getAsLong() + 30_000);
            CacheEntry existing = cache.get(uri);
            if (existing != null) {
                result.complete(new FeedResult(existing.items, "Cached RSS; live feed unavailable"));
            } else {
                result.completeExceptionally(error);
            }
        }
    }

    /**
     * 尊重秒数或日期格式的 Retry-After，并至少暂停五分钟。
     */
    private long retryMillis(String value) {
        long delay = CACHE_MILLIS;
        try {
            delay = Long.parseLong(value) * 1000;
        } catch (NumberFormatException ignored) {
            try {
                delay = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock.getAsLong();
            } catch (java.time.format.DateTimeParseException ignoredDate) {
                delay = CACHE_MILLIS;
            }
        }
        return Math.max(CACHE_MILLIS, Math.min(Duration.ofDays(1).toMillis(), delay));
    }

    /**
     * 关闭 HTTP 客户端并清除内存缓存。
     */
    @Override
    public synchronized void close() {
        closed = true;
        cache.clear();
        for (CompletableFuture<FeedResult> result : pending.values()) {
            result.cancel(false);
        }
        pending.clear();
        if (httpClient != null) {
            httpClient.shutdownNow();
        }
    }

    /**
     * 订阅源读取结果及可选的缓存提示。
     */
    public static final class FeedResult {
        public final List<RedditItem> items;
        public final String notice;

        /**
         * 创建不可变的订阅源读取结果。
         */
        public FeedResult(List<RedditItem> items, String notice) {
            this.items = List.copyOf(items);
            this.notice = notice;
        }
    }

    /**
     * 用于 HTTP 适配与离线测试的订阅源响应。
     */
    static final class FeedResponse {
        final int status;
        final String body;
        final String retryAfter;

        /**
         * 创建 HTTP 响应数据。
         */
        FeedResponse(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    /**
     * 单个订阅源的有界缓存条目。
     */
    private static final class CacheEntry {
        final List<RedditItem> items;
        final long createdAt;

        /**
         * 保存成功解析的内容及时间。
         */
        CacheEntry(List<RedditItem> items, long createdAt) {
            this.items = List.copyOf(items);
            this.createdAt = createdAt;
        }
    }
}
