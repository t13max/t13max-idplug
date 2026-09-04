package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 离线验证类型标记、语言偏好和刷新结果比较。 */
final class ReadingUiTest {
    /** 四种类型始终使用一个字母，不受语言选择影响。 */
    @Test
    void mapsSingleLetterKinds() { assertEquals("P", ReadingText.marker("Post")); assertEquals("T", ReadingText.marker("Title")); assertEquals("B", ReadingText.marker("Body")); assertEquals("C", ReadingText.marker("Comment")); }

    /** 界面文字支持双向切换，不翻译网页内容。 */
    @Test
    void switchesLanguages() {
        assertEquals("扫码登录", UiLanguage.translate("Scan QR to sign in", false));
        assertEquals("Sign out", UiLanguage.translate("登出", true));
        assertEquals("官网帖子原文", UiLanguage.translate("官网帖子原文", true));
        assertEquals("", UiLanguage.translate(null, false));
    }

    /** 默认中文并可通过持久化数据恢复英文设置。 */
    @Test
    void persistsLanguageChoice() {
        UiLanguage first = new UiLanguage();
        assertFalse(first.english()); first.setEnglish(true);
        UiLanguage second = new UiLanguage(); second.loadState(first.getState());
        assertTrue(second.english());
    }

    /** 创建不含真实账号信息的列表条目。 */
    private PageSnapshot.Item item(String text, String url) { var item = new PageSnapshot.Item(); item.kind = "Post"; item.text = text; item.url = url; return item; }

    /** 同样的推荐必须报告未变化，文字、地址或顺序变化才算更新。 */
    @Test
    void comparesRefreshContents() {
        var first = item("A", "https://www.xiaoheihe.cn/app/bbs/link/1");
        var second = item("B", "https://www.xiaoheihe.cn/app/bbs/link/2");
        assertTrue(ReadingText.sameItems(List.of(first), List.of(item(first.text, first.url))));
        assertFalse(ReadingText.sameItems(List.of(first), List.of(second)));
        assertFalse(ReadingText.sameItems(List.of(first, second), List.of(second, first)));
        assertFalse(ReadingText.sameItems(List.of(first), List.of()));
    }

    /** 明确未登录标记能够被桥接解析。 */
    @Test
    void parsesSignedOutState() {
        var page = PageSnapshot.parse("{\"url\":\"https://www.xiaoheihe.cn/app/bbs/home\",\"signedOut\":true,\"items\":[],\"communities\":[]}");
        assertTrue(page.signedOut); assertFalse(page.signedIn);
    }
}
