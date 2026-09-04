package com.t13max.idplug.heybox;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ui.jcef.JBCefCookie;
import com.intellij.ui.jcef.JBCefCookieManager;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 在后台线程读写 PasswordSafe，仅访问明确限定的小黑盒 Cookie。 */
final class SessionStore {
    private static final CredentialAttributes KEY = new CredentialAttributes("T13max Heybox Session", "heybox-session");
    private static final List<String> URLS = List.of("https://www.xiaoheihe.cn/app/bbs/home", "https://login.xiaoheihe.cn/");

    /** 读取安全存储中的会话，无内容时不初始化浏览器。 */
    static List<SessionCookies.Entry> read() {
        Credentials credentials = PasswordSafe.getInstance().get(KEY);
        return SessionCookies.decode(credentials == null ? null : credentials.getPasswordAsString(), System.currentTimeMillis());
    }

    /** 将校验后的条目恢复到浏览器，保留原始安全属性与有效期。 */
    static void restore(List<SessionCookies.Entry> entries) throws Exception {
        JBCefCookieManager manager = new JBCefCookieManager();
        for (SessionCookies.Entry entry : entries) {
            if (!SessionCookies.valid(entry, System.currentTimeMillis())) continue;
            String host = entry.domain().startsWith(".") ? entry.domain().substring(1) : entry.domain();
            JBCefCookie cookie = new JBCefCookie(entry.name(), entry.value(), entry.domain(), entry.path(), entry.secure(), entry.httpOnly(), new Date(entry.creation()), new Date(), entry.hasExpires(), new Date(entry.expires()));
            if (!manager.setCookie("https://" + host + entry.path(), cookie).get(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cookie restore failed");
        }
    }

    /** 获取限定 URL 的 Cookie，包括 HttpOnly，并由 PasswordSafe 安全保存。 */
    static void save() throws Exception {
        JBCefCookieManager manager = new JBCefCookieManager();
        List<SessionCookies.Entry> entries = new ArrayList<>();
        for (String url : URLS) for (JBCefCookie cookie : manager.getCookies(url, true).get(5, TimeUnit.SECONDS)) {
            SessionCookies.Entry entry = new SessionCookies.Entry(cookie.getName(), cookie.getValue(), cookie.getDomain(), cookie.getPath(), cookie.isSecure(), cookie.isHttpOnly(), cookie.getCreation().getTime(), cookie.hasExpires(), cookie.getExpires() == null ? 0 : cookie.getExpires().getTime());
            if (SessionCookies.valid(entry, System.currentTimeMillis()) && !entries.contains(entry)) entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalStateException("No session cookies");
        PasswordSafe.getInstance().set(KEY, new Credentials("heybox-session", SessionCookies.encode(entries, System.currentTimeMillis())));
    }

    /** 仅删除本插件保存的会话，不删除全局 Cookie 或缓存。 */
    static void clear() { PasswordSafe.getInstance().set(KEY, null); }
}
