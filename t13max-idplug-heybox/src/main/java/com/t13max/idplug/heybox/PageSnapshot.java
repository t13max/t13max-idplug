package com.t13max.idplug.heybox;

import com.google.gson.Gson;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** 校验从网页传入的有界内容，不信任网页提供的地址或字段。 */
public final class PageSnapshot {
    public String url = "";
    public boolean signedIn;
    public boolean signedOut;
    public List<Item> items = new ArrayList<>();
    public List<String> communities = new ArrayList<>();

    /** 创建页面数据。 */
    public PageSnapshot() { }

    /** 校验可以由插件主动打开的官方阅读地址。 */
    public static boolean allowed(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && "www.xiaoheihe.cn".equals(uri.getHost()) && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getPath().matches("/app/(bbs/home|bbs/link/[0-9]+|topic/link/[0-9]+)");
        } catch (RuntimeException error) { return false; }
    }

    /** 将受限 JSON 解码为用于显示的数据。 */
    public static PageSnapshot parse(String json) {
        if (json == null || json.length() > 3_000_000) throw new IllegalArgumentException("Invalid page size");
        PageSnapshot page = new Gson().fromJson(json, PageSnapshot.class);
        if (page == null || !allowed(page.url) || page.items == null || page.communities == null) throw new IllegalArgumentException("Unsupported page");
        page.items = page.items.stream().filter(item -> item != null && item.text != null && item.kind != null && List.of("Post", "Title", "Body", "Comment").contains(item.kind) && ("Post".equals(item.kind) ? allowed(item.url) && item.url.matches(".*/bbs/link/[0-9]+") : "".equals(item.url))).limit(200).map(PageSnapshot::sanitize).toList();
        page.communities = page.communities.stream().filter(value -> value != null && !value.isBlank() && value.length() <= 60).distinct().limit(40).toList();
        return page;
    }

    /** 压缩文本并限制单条内容长度。 */
    private static Item sanitize(Item item) {
        item.text = item.text.replaceAll("\\s+", " ").trim();
        if (item.text.length() > 12000) item.text = item.text.substring(0, 12000);
        return item;
    }

    /** 保存一条标题、正文段落或评论。 */
    public static final class Item {
        public String text;
        public String url;
        public String kind;

        /** 创建网页条目。 */
        public Item() { }
    }
}
