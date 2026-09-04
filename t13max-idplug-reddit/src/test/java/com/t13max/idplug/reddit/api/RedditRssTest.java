package com.t13max.idplug.reddit.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用离线样本验证 RSS 解析、安全配置、缓存和限流。
 */
final class RedditRssTest {
    private static final String POST_FEED = "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>t3_ab12</id><title>A &amp; B 😀</title><author><name>/u/alice</name></author><category term=\"java\"/><link href=\"https://www.reddit.com/r/java/comments/ab12/title/\"/></entry></feed>";
    private static final String COMMENT_FEED = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><id>t3_ab12</id><title>Parent post</title></entry><entry><id>t1_cd34</id><author><name>/u/bob</name></author><content type=\"html\">&lt;p&gt;Hello &amp;#x1f600;&lt;/p&gt;&lt;p&gt;A &amp;amp; B &lt;a href='https://example.com'&gt;link&lt;/a&gt;&lt;br/&gt;next&lt;/p&gt;</content></entry><entry><id>more_xyz</id></entry></feed>";

    /**
     * 解析帖子标题、作者、专区和永久链接。
     */
    @Test
    void parsesPostFeed() {
        var items = RedditRssParser.parse(POST_FEED, false, "fallback");
        assertEquals(1, items.size());
        assertEquals("ab12", items.getFirst().getId());
        assertEquals("A & B 😀", items.getFirst().getText());
        assertEquals("alice", items.getFirst().getAuthor());
        assertEquals("java", items.getFirst().getSubreddit());
        assertEquals("/r/java/comments/ab12/title/", items.getFirst().getPermalink());
    }

    /**
     * 评论源排除父帖子，解码 HTML 与表情并转换为单行。
     */
    @Test
    void parsesCommentsWithoutParentPost() {
        var items = RedditRssParser.parse(COMMENT_FEED, true, "java");
        assertEquals(1, items.size());
        assertEquals("cd34", items.getFirst().getId());
        assertEquals("bob", items.getFirst().getAuthor());
        assertEquals("Hello 😀 A & B link next", items.getFirst().getText());
        assertEquals("java", items.getFirst().getSubreddit());
    }

    /**
     * 空评论源不产生伪造条目。
     */
    @Test
    void acceptsEmptyCommentFeed() {
        assertTrue(RedditRssParser.parse(POST_FEED, true, "java").isEmpty());
    }

    /**
     * 普通标题保留代码中的尖括号，不能误当成 HTML 标签。
     */
    @Test
    void preservesCodeInPlainTitles() {
        String xml = POST_FEED.replace("A &amp; B 😀", "中文 C++ &lt;vector&gt; &amp; templates");
        assertEquals("中文 C++ <vector> & templates", RedditRssParser.parse(xml, false, "java").getFirst().getText());
    }

    /**
     * 拒绝 HTML 拦截页面和外部实体声明。
     */
    @Test
    void rejectsUnsafeAndInvalidXml() {
        assertThrows(IllegalStateException.class, () -> RedditRssParser.parse("<html><body>Denied</body></html>", false, "java"));
        String unsafe = "<!DOCTYPE feed [<!ENTITY external SYSTEM 'file:///not-allowed'>]><feed xmlns='http://www.w3.org/2005/Atom'>&external;</feed>";
        assertThrows(IllegalStateException.class, () -> RedditRssParser.parse(unsafe, false, "java"));
    }

    /**
     * 五分钟内命中缓存，过期后重新读取。
     */
    @Test
    void cachesSuccessfulFeeds() {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        try (var client = new RedditRssClient(uri -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new RedditRssClient.FeedResponse(200, POST_FEED, ""));
        }, now::get)) {
            assertEquals(1, client.loadPosts("java", "hot").join().items.size());
            assertEquals(1, client.loadPosts("JAVA", "hot").join().items.size());
            assertEquals(1, calls.get());
            now.addAndGet(300_001);
            client.loadPosts("java", "hot").join();
            assertEquals(2, calls.get());
        }
    }

    /**
     * 同一订阅源的并发读取共用单个请求。
     */
    @Test
    void deduplicatesPendingRequests() {
        AtomicLong now = new AtomicLong(1_000_000);
        CompletableFuture<RedditRssClient.FeedResponse> response = new CompletableFuture<>();
        try (var client = new RedditRssClient(uri -> response, now::get)) {
            var first = client.loadPosts("java", "hot");
            assertSame(first, client.loadPosts("java", "hot"));
            response.complete(new RedditRssClient.FeedResponse(200, POST_FEED, ""));
            assertEquals(1, first.join().items.size());
        }
    }

    /**
     * 429 后遵循 Retry-After，冷却期间不访问其他订阅源。
     */
    @Test
    void honorsRateLimitCooldown() {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        try (var client = new RedditRssClient(uri -> CompletableFuture.completedFuture(calls.incrementAndGet() == 1 ? new RedditRssClient.FeedResponse(429, "", "600") : new RedditRssClient.FeedResponse(200, POST_FEED, "")), now::get)) {
            assertThrows(CompletionException.class, () -> client.loadPosts("java", "hot").join());
            now.addAndGet(300_001);
            assertThrows(CompletionException.class, () -> client.loadPosts("programming", "new").join());
            assertEquals(1, calls.get());
            now.addAndGet(300_001);
            assertEquals(1, client.loadPosts("java", "hot").join().items.size());
            assertEquals(2, calls.get());
        }
    }

    /**
     * 网络故障时保留旧缓存，并阻止重复刷新形成请求风暴。
     */
    @Test
    void servesStaleCacheOnFailure() {
        AtomicLong now = new AtomicLong(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        try (var client = new RedditRssClient(uri -> CompletableFuture.completedFuture(calls.incrementAndGet() == 1 ? new RedditRssClient.FeedResponse(200, POST_FEED, "") : new RedditRssClient.FeedResponse(503, "", "")), now::get)) {
            client.loadPosts("java", "hot").join();
            now.addAndGet(300_001);
            var stale = client.loadPosts("java", "hot").join();
            assertEquals(1, stale.items.size());
            assertTrue(stale.notice.startsWith("Cached RSS"));
            assertTrue(client.loadPosts("java", "hot").join().notice.contains("cooldown"));
            assertEquals(2, calls.get());
        }
    }

    /**
     * 拒绝不合法的路径片段，避免向任意地址发送请求。
     */
    @Test
    void rejectsInvalidRoutes() {
        try (var client = new RedditRssClient(uri -> {
            fail("Invalid paths must not issue HTTP requests");
            return null;
        }, () -> 1_000_000)) {
            assertThrows(CompletionException.class, () -> client.loadPosts("../private", "hot").join());
            assertThrows(CompletionException.class, () -> client.loadPosts("java", "../../other").join());
            assertThrows(CompletionException.class, () -> client.loadComments("../other", "java").join());
        }
    }
}
