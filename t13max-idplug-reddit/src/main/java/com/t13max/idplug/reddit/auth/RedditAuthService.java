package com.t13max.idplug.reddit.auth;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.t13max.idplug.reddit.api.RedditApiClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reddit 外部浏览器 OAuth 登录服务。
 */
public final class RedditAuthService implements Disposable {
    public static final String REDIRECT_URI = "http://127.0.0.1:18931/reddit-callback";
    private final RedditApiClient apiClient;
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;
    private volatile HttpServer callbackServer;
    private volatile CompletableFuture<String> pendingLogin;

    /**
     * 创建登录服务。
     */
    public RedditAuthService(RedditApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * 在系统浏览器中发起 Reddit 登录。
     */
    public synchronized CompletableFuture<String> login(String clientId) {
        if (pendingLogin != null && !pendingLogin.isDone()) {
            pendingLogin.completeExceptionally(new IllegalStateException("A new Reddit sign-in has started"));
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        pendingLogin = result;
        result.whenComplete((username, error) -> clearPendingLogin(result));
        stopCallbackServer();
        try {
            String state = randomState();
            callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 18931), 0);
            callbackServer.createContext("/reddit-callback", exchange -> handleCallback(exchange, clientId, state, result));
            callbackServer.start();
            BrowserUtil.browse(buildAuthorizationUri(clientId, state));
            CompletableFuture.delayedExecutor(5, TimeUnit.MINUTES).execute(() -> expireLogin(result));
        } catch (IOException exception) {
            result.completeExceptionally(new IllegalStateException("Unable to start the local OAuth callback on port 18931", exception));
        }
        return result;
    }

    /**
     * 返回可用的访问令牌，必要时自动刷新。
     */
    public CompletableFuture<String> requireAccessToken(String clientId) {
        if (accessToken != null && Instant.now().getEpochSecond() < accessTokenExpiresAt - 60) {
            return CompletableFuture.completedFuture(accessToken);
        }
        String refreshToken = RedditCredentialStore.loadRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Sign in to Reddit first"));
        }
        return apiClient.refreshToken(clientId, refreshToken).thenApply(tokens -> rememberTokens(tokens, false));
    }

    /**
     * 判断本机是否保存了登录信息。
     */
    public boolean hasSavedLogin() {
        String refreshToken = RedditCredentialStore.loadRefreshToken();
        return refreshToken != null && !refreshToken.isBlank();
    }

    /**
     * 退出 Reddit 并清除本机令牌。
     */
    public void logout() {
        accessToken = null;
        accessTokenExpiresAt = 0;
        RedditCredentialStore.clearRefreshToken();
    }

    /**
     * 处理浏览器回调并交换令牌。
     */
    private void handleCallback(HttpExchange exchange, String clientId, String expectedState, CompletableFuture<String> result) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        boolean valid = expectedState.equals(query.get("state")) && query.containsKey("code");
        String message = valid ? "Reddit authorization is complete. You can close this page and return to IntelliJ IDEA." : "Reddit authorization failed or was cancelled. You can close this page.";
        byte[] response = ("<!doctype html><meta charset=\"utf-8\"><title>Reddit Sign-In</title><p>" + message + "</p>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(valid ? 200 : 400, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
        stopCallbackServer();
        if (!valid) {
            result.completeExceptionally(new IllegalStateException(query.getOrDefault("error", "Reddit authorization validation failed")));
            return;
        }
        apiClient.exchangeCode(clientId, query.get("code"), REDIRECT_URI).thenCompose(tokens -> {
            rememberTokens(tokens, true);
            return apiClient.loadCurrentUser(tokens.getAccessToken());
        }).whenComplete((username, error) -> complete(result, username, error));
    }

    /**
     * 记录访问令牌及可选刷新令牌。
     */
    private String rememberTokens(RedditApiClient.TokenPair tokens, boolean requireRefreshToken) {
        accessToken = tokens.getAccessToken();
        accessTokenExpiresAt = Instant.now().getEpochSecond() + tokens.getExpiresInSeconds();
        if (tokens.getRefreshToken() != null && !tokens.getRefreshToken().isBlank()) {
            RedditCredentialStore.saveRefreshToken(tokens.getRefreshToken());
        } else if (requireRefreshToken) {
            throw new IllegalStateException("Reddit did not return a permanent refresh token; authorize the app again");
        }
        return accessToken;
    }

    /**
     * 完成登录异步结果。
     */
    private void complete(CompletableFuture<String> result, String username, Throwable error) {
        if (error == null) {
            result.complete(username);
        } else {
            result.completeExceptionally(error);
        }
    }

    /**
     * 超时结束尚未完成的登录。
     */
    private synchronized void expireLogin(CompletableFuture<String> result) {
        if (pendingLogin == result && !result.isDone()) {
            stopCallbackServer();
            result.completeExceptionally(new IllegalStateException("Reddit sign-in timed out"));
        }
    }

    /**
     * 清理已经结束的登录引用。
     */
    private synchronized void clearPendingLogin(CompletableFuture<String> result) {
        if (pendingLogin == result) {
            pendingLogin = null;
        }
    }

    /**
     * 停止本地回调服务。
     */
    private synchronized void stopCallbackServer() {
        if (callbackServer != null) {
            callbackServer.stop(0);
            callbackServer = null;
        }
    }

    /**
     * 构建 Reddit 授权地址。
     */
    private URI buildAuthorizationUri(String clientId, String state) {
        String query = "client_id=" + encode(clientId) + "&response_type=code&state=" + encode(state) + "&redirect_uri=" + encode(REDIRECT_URI) + "&duration=permanent&scope=" + encode("identity read mysubreddits");
        return URI.create("https://www.reddit.com/api/v1/authorize?" + query);
    }

    /**
     * 生成 OAuth 防伪随机状态。
     */
    private String randomState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 解析回调查询参数。
     */
    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return result;
    }

    /**
     * 编码查询参数。
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 解码查询参数。
     */
    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * 释放本地回调服务。
     */
    @Override
    public void dispose() {
        stopCallbackServer();
    }
}
