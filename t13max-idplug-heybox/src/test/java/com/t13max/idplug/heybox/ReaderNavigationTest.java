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
}
