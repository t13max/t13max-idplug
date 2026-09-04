package com.t13max.idplug.reddit.auth;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;

/**
 * Reddit 刷新令牌的安全存储。
 */
public final class RedditCredentialStore {
    private static final CredentialAttributes ATTRIBUTES = new CredentialAttributes("T13max Reddit OAuth", "reddit-refresh-token");

    /**
     * 禁止创建工具类实例。
     */
    private RedditCredentialStore() {
    }

    /**
     * 读取刷新令牌。
     */
    public static String loadRefreshToken() {
        Credentials credentials = PasswordSafe.getInstance().get(ATTRIBUTES);
        return credentials == null ? null : credentials.getPasswordAsString();
    }

    /**
     * 保存刷新令牌。
     */
    public static void saveRefreshToken(String refreshToken) {
        PasswordSafe.getInstance().set(ATTRIBUTES, new Credentials("reddit-refresh-token", refreshToken));
    }

    /**
     * 删除刷新令牌。
     */
    public static void clearRefreshToken() {
        PasswordSafe.getInstance().set(ATTRIBUTES, null);
    }
}
