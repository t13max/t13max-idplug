package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 离线校验网页桥接边界，不读取真实账号。 */
final class PageSnapshotTest {
    /** 仅允许三个官方阅读路由。 */
    @Test
    void acceptsReadingRoutes() { assertTrue(PageSnapshot.allowed(HOME)); assertTrue(PageSnapshot.allowed("https://www.xiaoheihe.cn/app/bbs/link/123")); assertTrue(PageSnapshot.allowed("https://www.xiaoheihe.cn/app/topic/link/456")); }
    private static final String HOME = "https://www.xiaoheihe.cn/app/bbs/home";

    /** 拒绝域名伪装、端口、查询参数和可执行地址。 */
    @Test
    void rejectsUnsafeRoutes() {
        for (String url : new String[]{"javascript:alert(1)", "https://www.xiaoheihe.cn.evil.com/app/bbs/home", "https://evil@www.xiaoheihe.cn/app/bbs/home", HOME + "?token=secret", HOME + "#fragment", "https://www.xiaoheihe.cn:443/app/bbs/home", "http://www.xiaoheihe.cn/app/bbs/home", "https://www.xiaoheihe.cn/app/bbs/link/../home"}) assertFalse(PageSnapshot.allowed(url), url);
    }

    /** 拒绝空数据和过大消息。 */
    @Test
    void rejectsMalformedPayloads() { assertThrows(RuntimeException.class, () -> PageSnapshot.parse("null")); assertThrows(RuntimeException.class, () -> PageSnapshot.parse("x".repeat(3_000_001))); assertThrows(RuntimeException.class, () -> PageSnapshot.parse("{}")); }

    /** 过滤外部链接、未知类型和空条目。 */
    @Test
    void filtersUntrustedItems() {
        String json = "{\"url\":\"" + HOME + "\",\"items\":[null,{\"text\":\"bad\",\"kind\":\"Post\",\"url\":\"https://evil.com\"},{\"text\":\"ok\\n😀\",\"kind\":\"Comment\",\"url\":\"\"}],\"communities\":[null,\"Steam\",\"Steam\"]}";
        PageSnapshot result = PageSnapshot.parse(json);
        assertEquals(1, result.items.size()); assertEquals("ok 😀", result.items.getFirst().text); assertEquals(1, result.communities.size());
    }
}
