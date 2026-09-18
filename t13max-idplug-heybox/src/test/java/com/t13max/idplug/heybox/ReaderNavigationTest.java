package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 不启动浏览器，验证帖子退出时的缓存恢复和显示规则。 */
final class ReaderNavigationTest {
    /** 设置测试状态，避免联网或创建浏览器。 */
    private void set(HeyboxReader reader, String name, Object value) throws Exception { var field = HeyboxReader.class.getDeclaredField(name); field.setAccessible(true); field.set(reader, value); }

    /** 创建两条标题的离线列表。 */
    private PageSnapshot list() { return PageSnapshot.parse("{\"url\":\"https://www.xiaoheihe.cn/app/bbs/home\",\"items\":[{\"text\":\"First\",\"kind\":\"Post\",\"url\":\"https://www.xiaoheihe.cn/app/bbs/link/1\"},{\"text\":\"Second\",\"kind\":\"Post\",\"url\":\"https://www.xiaoheihe.cn/app/bbs/link/2\"}],\"communities\":[]}"); }

    /** Alt+S 在帖子内恢复原列表及阅读位置，不启动网络。 */
    @Test
    void exitsToCachedListPosition() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        set(reader, "savedList", list());
        set(reader, "savedIndex", 1);
        set(reader, "expectedUrl", "https://www.xiaoheihe.cn/app/bbs/link/2");
        assertTrue(reader.insidePost());
        reader.enterOrExit();
        assertFalse(reader.insidePost());
        assertEquals("P [2/2] Second", reader.text());
        reader.move(-1);
        assertEquals("P [1/2] First", reader.text());
        reader.move(1);
        assertEquals("P [2/2] Second", reader.text());
    }

    /** 刷新首次显示第一帖，后台确认无论是否更新都保留用户翻页和横向滚动位置。 */
    @Test
    void refreshPreservesReadingPositionWhenConfirmationArrives() throws Exception {
        for (boolean updated : new boolean[]{false, true}) for (boolean navigated : new boolean[]{false, true}) {
            HeyboxReader reader = new HeyboxReader();
            try {
                PageSnapshot incoming = list();
                set(reader, "refreshBaseline", list());
                set(reader, "savedIndex", 1);
                set(reader, "index", 1);
                set(reader, "listUrl", incoming.url);
                set(reader, "loadCompletedAt", System.currentTimeMillis() - 9000);
                set(reader, "offset", 80);
                if (updated) incoming.items.getFirst().text = "Updated";
                var method = HeyboxReader.class.getDeclaredMethod("acceptReadingItems", PageSnapshot.class, String.class);
                method.setAccessible(true);
                String first = "P [1/2] " + (updated ? "Updated" : "First");
                method.invoke(reader, incoming, "stable");
                assertEquals(first, reader.text());
                assertNotNull(get(reader, "refreshBaseline"));
                assertEquals(0, get(reader, "offset"));
                if (navigated) { reader.move(1); set(reader, "offset", 36); }
                String expected = navigated ? "P [2/2] Second" : first;
                assertEquals(expected, reader.text());
                for (int sample = 0; sample < 3; sample++) method.invoke(reader, incoming, "stable");
                assertEquals(expected, reader.text());
                assertEquals(updated ? "Refreshed; page content updated" : "Refreshed; page content unchanged", reader.refreshResult());
                assertEquals(0, get(reader, "savedIndex"));
                assertEquals(navigated ? 36 : 0, get(reader, "offset"));
                assertNull(get(reader, "refreshBaseline"));
                method.invoke(reader, incoming, "stable");
                assertEquals(expected, reader.text());
                assertEquals(navigated ? 36 : 0, get(reader, "offset"));
                reader.move(1);
                assertEquals(navigated ? first : "P [2/2] Second", reader.text());
            } finally { reader.dispose(); }
        }
    }

    /** 无内容时保留可点击的提示，不显示反斜杠。 */
    @Test
    void initialHintHasNoMarker() { assertEquals("Click to sign in", new HeyboxReader().text()); }

    /** 明确登录按钮连续出现才完成登出并清除缓存。 */
    @Test
    void confirmsLogoutBeforeClearingAccountState() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        set(reader, "signedIn", true);
        set(reader, "logoutRequested", true);
        set(reader, "savedList", list());
        var method = HeyboxReader.class.getDeclaredMethod("updateAuthentication", PageSnapshot.class);
        method.setAccessible(true);
        PageSnapshot loggedOut = new PageSnapshot(); loggedOut.signedOut = true;
        assertEquals(false, method.invoke(reader, new PageSnapshot())); assertTrue(reader.isSignedIn());
        assertEquals(false, method.invoke(reader, loggedOut)); assertTrue(reader.isSignedIn());
        assertEquals(false, method.invoke(reader, loggedOut)); assertTrue(reader.isSignedIn());
        assertEquals(true, method.invoke(reader, loggedOut)); assertFalse(reader.isSignedIn());
        assertEquals("Signed out; click to sign in", reader.text());
    }

    /** 读取测试状态，验证发送结果不会提前丢弃草稿。 */
    private Object get(HeyboxReader reader, String name) throws Exception { var field = HeyboxReader.class.getDeclaredField(name); field.setAccessible(true); return field.get(reader); }
}
