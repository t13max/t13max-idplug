package com.t13max.idplug.reddit.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.t13max.idplug.reddit.model.RedditItem;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reddit OAuth 与内容接口客户端。
 */
public final class RedditApiClient {
    private static final String USER_AGENT = "windows:com.t13max.idplug.reddit:1.0.0";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /**
     * 创建 Reddit 接口客户端。
     */
    public RedditApiClient() {
    }

    /**
     * 使用授权码换取永久令牌。
     */
    public CompletableFuture<TokenPair> exchangeCode(String clientId, String code, String redirectUri) {
        String body = form("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri);
        return sendTokenRequest(clientId, body);
    }

    /**
     * 使用刷新令牌获取新的访问令牌。
     */
    public CompletableFuture<TokenPair> refreshToken(String clientId, String refreshToken) {
        String body = form("grant_type", "refresh_token", "refresh_token", refreshToken);
        return sendTokenRequest(clientId, body);
    }

    /**
     * 获取指定专区的帖子。
     */
    public CompletableFuture<List<RedditItem>> loadPosts(String accessToken, String subreddit, String sort) {
        String encodedSubreddit = encodePath(subreddit);
        URI uri = URI.create("https://oauth.reddit.com/r/" + encodedSubreddit + "/" + sort + "?limit=50&raw_json=1");
        return sendGet(accessToken, uri).thenApply(this::parsePosts);
    }

    /**
     * 获取指定帖子的评论。
     */
    public CompletableFuture<List<RedditItem>> loadComments(String accessToken, String postId) {
        URI uri = URI.create("https://oauth.reddit.com/comments/" + encodePath(postId) + "?limit=100&depth=8&raw_json=1");
        return sendGet(accessToken, uri).thenApply(this::parseComments);
    }

    /**
     * 获取当前账号订阅的专区。
     */
    public CompletableFuture<List<String>> loadSubscribedCommunities(String accessToken) {
        URI uri = URI.create("https://oauth.reddit.com/subreddits/mine/subscriber?limit=100&raw_json=1");
        return sendGet(accessToken, uri).thenApply(this::parseCommunities);
    }

    /**
     * 获取当前登录账号名称。
     */
    public CompletableFuture<String> loadCurrentUser(String accessToken) {
        return sendGet(accessToken, URI.create("https://oauth.reddit.com/api/v1/me")).thenApply(body -> JsonParser.parseString(body).getAsJsonObject().get("name").getAsString());
    }

    /**
     * 发送令牌请求并解析结果。
     */
    private CompletableFuture<TokenPair> sendTokenRequest(String clientId, String body) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.reddit.com/api/v1/access_token")).timeout(Duration.ofSeconds(30)).header("Authorization", "Basic " + basic).header("User-Agent", USER_AGENT).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(this::parseTokenResponse);
    }

    /**
     * 发送带 OAuth 访问令牌的读取请求。
     */
    private CompletableFuture<String> sendGet(String accessToken, URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + accessToken).header("User-Agent", USER_AGENT).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(this::requireSuccess);
    }

    /**
     * 解析令牌响应。
     */
    private TokenPair parseTokenResponse(HttpResponse<String> response) {
        String body = requireSuccess(response);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (json.has("error")) {
            throw new IllegalStateException("Reddit authorization failed: " + json.get("error").getAsString());
        }
        String refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
        return new TokenPair(json.get("access_token").getAsString(), refreshToken, json.get("expires_in").getAsLong());
    }

    /**
     * 校验 HTTP 响应状态。
     */
    private String requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Reddit request failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 解析帖子列表。
     */
    private List<RedditItem> parsePosts(String body) {
        List<RedditItem> result = new ArrayList<>();
        JsonArray children = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("children");
        for (JsonElement child : children) {
            JsonObject data = child.getAsJsonObject().getAsJsonObject("data");
            result.add(new RedditItem(string(data, "id"), clean(string(data, "title")), string(data, "author"), string(data, "subreddit"), string(data, "permalink")));
        }
        return result;
    }

    /**
     * 解析评论列表。
     */
    private List<RedditItem> parseComments(String body) {
        List<RedditItem> result = new ArrayList<>();
        JsonArray root = JsonParser.parseString(body).getAsJsonArray();
        if (root.size() > 1) {
            flattenComments(root.get(1).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("children"), result, 0);
        }
        return result;
    }

    /**
     * 递归展开嵌套评论。
     */
    private void flattenComments(JsonArray children, List<RedditItem> result, int depth) {
        for (JsonElement child : children) {
            JsonObject wrapper = child.getAsJsonObject();
            if (!"t1".equals(string(wrapper, "kind"))) {
                continue;
            }
            JsonObject data = wrapper.getAsJsonObject("data");
            String indentation = "  ".repeat(Math.min(depth, 6));
            result.add(new RedditItem(string(data, "id"), indentation + clean(string(data, "body")), string(data, "author"), string(data, "subreddit"), string(data, "permalink")));
            JsonElement replies = data.get("replies");
            if (replies != null && replies.isJsonObject()) {
                flattenComments(replies.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("children"), result, depth + 1);
            }
        }
    }

    /**
     * 解析订阅专区列表。
     */
    private List<String> parseCommunities(String body) {
        List<String> result = new ArrayList<>();
        JsonArray children = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("children");
        for (JsonElement child : children) {
            result.add(string(child.getAsJsonObject().getAsJsonObject("data"), "display_name"));
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * 读取可缺省的字符串字段。
     */
    private String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /**
     * 将内容压缩成状态栏单行文本。
     */
    private String clean(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * 编码表单字段。
     */
    private String form(String... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("The form field list must contain key-value pairs");
        }
        List<String> pairs = new ArrayList<>();
        for (int index = 0; index < values.length; index += 2) {
            pairs.add(URLEncoder.encode(values[index], StandardCharsets.UTF_8) + "=" + URLEncoder.encode(values[index + 1], StandardCharsets.UTF_8));
        }
        return String.join("&", pairs);
    }

    /**
     * 校验并编码 Reddit 路径片段。
     */
    private String encodePath(String value) {
        if (!value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid community or content ID");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * OAuth 令牌结果。
     */
    public static final class TokenPair {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresInSeconds;

        /**
         * 创建 OAuth 令牌结果。
         */
        public TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSeconds = expiresInSeconds;
        }

        /**
         * 获取访问令牌。
         */
        public String getAccessToken() {
            return accessToken;
        }

        /**
         * 获取刷新令牌。
         */
        public String getRefreshToken() {
            return refreshToken;
        }

        /**
         * 获取访问令牌有效秒数。
         */
        public long getExpiresInSeconds() {
            return expiresInSeconds;
        }
    }
}
