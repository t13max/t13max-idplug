package com.t13max.idplug.heybox;

import com.google.gson.Gson;
import java.util.List;
import java.util.Set;

/** 校验会话快照，只接受小黑盒域名且不延长官网有效期。 */
final class SessionCookies {
    static final int LIMIT = 262144;
    private static final Set<String> DOMAINS = Set.of("xiaoheihe.cn", ".xiaoheihe.cn", "www.xiaoheihe.cn", ".www.xiaoheihe.cn", "login.xiaoheihe.cn", ".login.xiaoheihe.cn");

    /** 保存不依赖浏览器原生对象的 Cookie 属性。 */
    record Entry(String name, String value, String domain, String path, boolean secure, boolean httpOnly, long creation, boolean hasExpires, long expires) { }

    /** 记录格式版本和本地保存时间。 */
    record Snapshot(int version, long savedAt, List<Entry> cookies) { }

    /** 校验单条属性与真实过期时间，拒绝异常路径和过大字段。 */
    static boolean valid(Entry entry, long now) {
        return entry != null && DOMAINS.contains(entry.domain == null ? "" : entry.domain) && entry.name != null && !entry.name.isBlank() && entry.name.length() <= 1024 && entry.value != null && entry.value.length() <= 16384 && entry.path != null && entry.path.startsWith("/") && entry.path.length() <= 2048 && !entry.path.contains("?") && !entry.path.contains("#") && (!entry.hasExpires || entry.expires > now);
    }

    /** 编码有界快照，不将凭据写入日志或普通配置。 */
    static String encode(List<Entry> cookies, long now) {
        if (cookies.size() > 128) throw new IllegalArgumentException("Too many cookies");
        String json = new Gson().toJson(new Snapshot(1, now, cookies.stream().filter(entry -> valid(entry, now)).toList()));
        if (json.length() > LIMIT) throw new IllegalArgumentException("Session too large");
        return json;
    }

    /** 恢复最近三十天的快照，丢弃官网已过期的条目。 */
    static List<Entry> decode(String json, long now) {
        if (json == null || json.isBlank()) return List.of();
        if (json.length() > LIMIT) throw new IllegalArgumentException("Session too large");
        Snapshot snapshot = new Gson().fromJson(json, Snapshot.class);
        if (snapshot == null || snapshot.version != 1 || snapshot.cookies == null || snapshot.cookies.size() > 128) throw new IllegalArgumentException("Invalid session");
        if (snapshot.savedAt > now || now - snapshot.savedAt > 30L * 24 * 60 * 60 * 1000) return List.of();
        return snapshot.cookies.stream().filter(entry -> valid(entry, now)).toList();
    }
}
