package com.t13max.idplug.heybox;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 离线验证登录检查流程，不创建浏览器或读取真实凭据。 */
final class LoginFlowTest {
    /** 设置流程状态以模拟异步回调。 */
    private void set(HeyboxReader reader, String name, Object value) throws Exception { var field = HeyboxReader.class.getDeclaredField(name); field.setAccessible(true); field.set(reader, value); }

    /** 读取内部状态用于验证窗口显示之前的流程。 */
    private Object get(HeyboxReader reader, String name) throws Exception { var field = HeyboxReader.class.getDeclaredField(name); field.setAccessible(true); return field.get(reader); }

    /** 调用单个数据处理入口，不启动原生浏览器。 */
    private void call(HeyboxReader reader, String name, Class<?> type, Object value) throws Exception { var method = HeyboxReader.class.getDeclaredMethod(name, type); method.setAccessible(true); method.invoke(reader, value); }

    /** 二维码回调已登录时，即使窗口尚未创建也应结束检查。 */
    @Test void qrSignedInCompletesBeforeWindowExists() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        try {
            set(reader, "loginRequested", true); set(reader, "loginChecking", true);
            call(reader, "acceptQr", com.google.gson.JsonObject.class, JsonParser.parseString("{\"state\":\"signedIn\"}").getAsJsonObject());
            assertTrue(reader.isSignedIn()); assertEquals(false, get(reader, "loginChecking")); assertEquals(false, get(reader, "loginRequested")); assertNull(get(reader, "qrWindow"));
        } finally { reader.dispose(); }
    }

    /** 阅读提取确认旧会话后无需扫码，直接进入推荐加载。 */
    @Test void restoredSessionDoesNotOpenQr() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        try {
            set(reader, "loginChecking", true);
            PageSnapshot page = new PageSnapshot(); page.signedIn = true;
            call(reader, "checkLogin", PageSnapshot.class, page);
            assertTrue(reader.isSignedIn()); assertNull(get(reader, "qrWindow")); assertEquals("Signed in; loading your feed...", reader.text());
        } finally { reader.dispose(); }
    }

    /** 匿名内容和不足三次的登录按钮不能提前弹二维码。 */
    @Test void unknownAndTransientSignedOutKeepChecking() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        try {
            set(reader, "loginChecking", true); set(reader, "loginRequested", true);
            call(reader, "checkLogin", PageSnapshot.class, new PageSnapshot());
            PageSnapshot page = new PageSnapshot(); page.signedOut = true;
            for (int i = 1; i <= 2; i++) { set(reader, "loggedOutSamples", i); call(reader, "checkLogin", PageSnapshot.class, page); }
            assertEquals(true, get(reader, "loginChecking")); assertNull(get(reader, "qrWindow")); assertFalse(reader.isSignedIn());
        } finally { reader.dispose(); }
    }

    /** 自动恢复确认失效后只显示登录入口，不自行弹窗。 */
    @Test void expiredRestoreDoesNotOpenQr() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        try {
            set(reader, "loginChecking", true); set(reader, "loggedOutSamples", 3);
            PageSnapshot page = new PageSnapshot(); page.signedOut = true;
            call(reader, "checkLogin", PageSnapshot.class, page);
            assertEquals(false, get(reader, "loginChecking")); assertNull(get(reader, "qrWindow")); assertEquals("Click to sign in", reader.text());
        } finally { reader.dispose(); }
    }

    /** 用户取消后迟到的二维码成功回调不能重新触发登录流程。 */
    @Test void cancelledQrCallbackIsIgnored() throws Exception {
        HeyboxReader reader = new HeyboxReader();
        try { call(reader, "acceptQr", com.google.gson.JsonObject.class, JsonParser.parseString("{\"state\":\"signedIn\"}").getAsJsonObject()); assertFalse(reader.isSignedIn()); }
        finally { reader.dispose(); }
    }
}
