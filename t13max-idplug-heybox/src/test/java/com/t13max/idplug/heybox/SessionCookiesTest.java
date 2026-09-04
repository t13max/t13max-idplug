package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 使用虚构凭据验证会话边界，不读取真实安全存储。 */
final class SessionCookiesTest {
    /** 构造仅供离线测试的 Cookie。 */
    private SessionCookies.Entry cookie(String domain, boolean expires, long until) { return new SessionCookies.Entry("test", "synthetic", domain, "/", true, true, 100, expires, until); }

    /** 往返保留域名、路径和安全属性。 */
    @Test void roundTrip() { var entry = cookie(".xiaoheihe.cn", true, 3000); assertEquals(List.of(entry), SessionCookies.decode(SessionCookies.encode(List.of(entry), 1000), 2000)); }

    /** 域名白名单不接受后缀伪造或其他网站。 */
    @Test void rejectForeignDomains() { assertFalse(SessionCookies.valid(cookie("xiaoheihe.cn.evil.test", false, 0), 1000)); assertFalse(SessionCookies.valid(cookie("example.com", false, 0), 1000)); assertFalse(SessionCookies.valid(cookie(null, false, 0), 1000)); }

    /** 官网过期与本地快照过期均不能继续恢复。 */
    @Test void expire() { String json = SessionCookies.encode(List.of(cookie("www.xiaoheihe.cn", true, 2000)), 1000); assertTrue(SessionCookies.decode(json, 2000).isEmpty()); String session = SessionCookies.encode(List.of(cookie(".xiaoheihe.cn", false, 0)), 1000); assertTrue(SessionCookies.decode(session, 31L * 24 * 60 * 60 * 1000).isEmpty()); }

    /** 拒绝损坏、未知版本和过大的快照。 */
    @Test void rejectMalformed() { assertThrows(RuntimeException.class, () -> SessionCookies.decode("{", 1000)); assertThrows(IllegalArgumentException.class, () -> SessionCookies.decode("x".repeat(SessionCookies.LIMIT + 1), 1000)); assertThrows(IllegalArgumentException.class, () -> SessionCookies.decode("{\"version\":2,\"cookies\":[]}", 1000)); }

    /** 空安全存储与未来时间戳不应恢复任何凭据。 */
    @Test void emptyAndFuture() { assertTrue(SessionCookies.decode(null, 1000).isEmpty()); assertTrue(SessionCookies.decode(SessionCookies.encode(List.of(cookie(".xiaoheihe.cn", false, 0)), 2000), 1000).isEmpty()); }

    /** 超量数据不能进入安全存储，路径异常也不能恢复。 */
    @Test void countAndPathLimits() { assertThrows(IllegalArgumentException.class, () -> SessionCookies.encode(java.util.Collections.nCopies(129, cookie(".xiaoheihe.cn", false, 0)), 1000)); assertFalse(SessionCookies.valid(new SessionCookies.Entry("test", "synthetic", ".xiaoheihe.cn", "/?redirect=other", true, true, 100, false, 0), 1000)); }
}
