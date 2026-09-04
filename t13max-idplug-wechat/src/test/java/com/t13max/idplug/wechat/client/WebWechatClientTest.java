package com.t13max.idplug.wechat.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.t13max.idplug.wechat.model.ChatMessage;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用固定协议响应验证收发和会话恢复，不连接真实微信账号。 */
class WebWechatClientTest {
    private static final String SESSION = "{\"baseUrl\":\"https://wx.qq.com/cgi-bin/mmwebwx-bin\",\"skey\":\"test-key\",\"sid\":\"test-sid\",\"uin\":\"123\",\"ticket\":\"test-ticket\",\"device\":\"e123456789012345\",\"userId\":\"@me\",\"nickname\":\"自己\",\"syncKey\":{\"Count\":1,\"List\":[{\"Key\":1,\"Val\":2}]},\"cookies\":[]}";
    private static final String CONTACTS = "{\"BaseResponse\":{\"Ret\":0},\"Seq\":0,\"MemberList\":[{\"UserName\":\"@friend\",\"NickName\":\"昵称\",\"RemarkName\":\"备注\"},{\"UserName\":\"@@group\",\"NickName\":\"群聊\",\"MemberList\":[{\"UserName\":\"@member\",\"NickName\":\"成员昵称\",\"DisplayName\":\"群内备注\"}]}]}";

    /** 验证获取二维码、扫码确认、初始化和联系人加载的完整协议顺序。 */
    @Test
    void scansQrAndInitializesSession() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("jslogin", "window.QRLogin.code = 200; window.QRLogin.uuid = \"test-uuid\";");
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", image);
        transport.paths.add("test-uuid");
        transport.responses.add(image.toByteArray());
        transport.add("login", "window.code=201;");
        transport.add("login", "window.code=200; window.redirect_uri=\"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxnewloginpage?ticket=test\";");
        transport.add("webwxnewloginpage", "<error><ret>0</ret><skey>test</skey><wxsid>test</wxsid><wxuin>123</wxuin><pass_ticket>test</pass_ticket></error>");
        transport.add("webwxinit", "{\"BaseResponse\":{\"Ret\":0},\"User\":{\"UserName\":\"@me\",\"NickName\":\"自己\"},\"SyncKey\":{\"Count\":1,\"List\":[{\"Key\":1,\"Val\":2}]},\"ContactList\":[]}");
        transport.add("webwxgetcontact", CONTACTS);
        transport.add("webwxstatusnotify", "{\"BaseResponse\":{\"Ret\":0}}");
        List<BufferedImage> images = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.login(images::add, statuses::add);
            assertEquals(1, images.size());
            assertEquals(4, images.getFirst().getWidth());
            assertTrue(statuses.getFirst().contains("手机"));
            assertEquals("123", client.accountId());
            assertEquals("@me", client.userId());
            assertTrue(transport.responses.isEmpty());
        }
    }

    /** 恢复会话保持账号和游标，不重新调用初始化重置消息进度。 */
    @Test
    void restoresAndPollsWithoutReinitializing() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("synccheck", "window.synccheck={retcode:\"0\",selector:\"0\"}");
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.restore(SESSION);
            assertTrue(client.poll().isEmpty());
            assertEquals("123", client.accountId());
            assertEquals("@me", client.userId());
            assertTrue(transport.urls.getFirst().contains("synckey=1_2"));
            assertEquals(1, transport.urls.size());
        }
    }

    /** 群消息去掉发送者前缀，使用群备注，手机发送消息归入接收方会话。 */
    @Test
    void parsesGroupAndPhoneSentMessages() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("webwxgetcontact", CONTACTS);
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.restore(SESSION);
            client.fetchContacts();
            ChatMessage group = client.parseMessage(json("{\"MsgType\":1,\"MsgId\":\"10\",\"FromUserName\":\"@@group\",\"ToUserName\":\"@me\",\"Content\":\"@member:<br/>你好&lt;代码&gt;\",\"CreateTime\":12}"));
            assertEquals("群内备注", group.sender());
            assertEquals("你好<代码>", group.text());
            assertEquals("@@group", group.conversationId());
            assertFalse(group.outgoing());
            ChatMessage phone = client.parseMessage(json("{\"MsgType\":1,\"MsgId\":\"11\",\"FromUserName\":\"@me\",\"ToUserName\":\"@friend\",\"Content\":\"手机消息\"}"));
            assertEquals("@friend", phone.conversationId());
            assertTrue(phone.outgoing());
            assertEquals("备注", client.contacts().stream().filter(contact -> contact.id().equals("@friend")).findFirst().orElseThrow().displayName());
        }
    }

    /** 发送必须检查微信返回码，失败不能产生成功消息。 */
    @Test
    void rejectsFailedSendAndKeepsServerIdOnSuccess() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("webwxgetcontact", CONTACTS);
        transport.add("webwxsendmsg", "{\"BaseResponse\":{\"Ret\":1205}}");
        transport.add("webwxsendmsg", "{\"BaseResponse\":{\"Ret\":0},\"MsgID\":\"server-id\"}");
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.restore(SESSION);
            client.fetchContacts();
            assertThrows(IOException.class, () -> client.send("@friend", "失败消息"));
            ChatMessage sent = client.send("@friend", "成功消息");
            assertEquals("server-id", sent.id());
            assertFalse(sent.clientId().isBlank());
            JsonObject request = json(transport.bodies.getLast());
            assertEquals("@friend", request.getAsJsonObject("Msg").get("ToUserName").getAsString());
            assertEquals(sent.clientId(), request.getAsJsonObject("Msg").get("ClientMsgId").getAsString());
        }
    }

    /** 分页之间断网仍保留已交付页，恢复快照后从剩余页继续。 */
    @Test
    void persistsPendingSyncPages() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("webwxgetcontact", CONTACTS);
        transport.add("synccheck", "window.synccheck={retcode:\"0\",selector:\"2\"}");
        transport.add("webwxsync", syncPage("first", 1, 3));
        String saved;
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.restore(SESSION);
            client.fetchContacts();
            assertEquals("first", client.poll().getFirst().id());
            saved = client.snapshot();
        }
        FakeTransport resumed = new FakeTransport();
        resumed.add("webwxgetcontact", CONTACTS);
        resumed.add("webwxsync", syncPage("second", 0, 4));
        try (WebWechatClient client = new WebWechatClient(resumed)) {
            client.restore(saved);
            client.fetchContacts();
            assertEquals("second", client.poll().getFirst().id());
            assertEquals(3, json(resumed.bodies.getLast()).getAsJsonObject("SyncKey").getAsJsonArray("List").get(0).getAsJsonObject().get("Val").getAsInt());
        }
    }

    /** 微信明确退出时报告失效，不伪装为已恢复登录。 */
    @Test
    void reportsExpiredSession() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add("synccheck", "window.synccheck={retcode:\"1101\",selector:\"0\"}");
        try (WebWechatClient client = new WebWechatClient(transport)) {
            client.restore(SESSION);
            assertThrows(WebWechatClient.SessionExpiredException.class, client::poll);
        }
    }

    /** 恢复文件里的任意远端地址不能接收微信登录凭据。 */
    @Test
    void rejectsUntrustedSessionEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> WebWechatClient.validateEndpoint(URI.create("https://wx.qq.com.attacker.test/cgi-bin/mmwebwx-bin")));
        assertThrows(IllegalArgumentException.class, () -> WebWechatClient.validateEndpoint(URI.create("http://wx.qq.com/cgi-bin/mmwebwx-bin")));
        assertThrows(IllegalArgumentException.class, () -> WebWechatClient.validateEndpoint(URI.create("https://user@wx.qq.com/cgi-bin/mmwebwx-bin")));
    }

    /** 二次恢复不会延长 Cookie 有效期，过期 Cookie 会被丢弃。 */
    @Test
    void cookieExpirySurvivesRestart() throws Exception {
        JsonObject session = json(SESSION);
        long expiry = System.currentTimeMillis() + 60000;
        session.add("cookies", JsonParser.parseString("[{\"name\":\"wxsid\",\"value\":\"test\",\"domain\":\".wx.qq.com\",\"path\":\"/\",\"secure\":true,\"httpOnly\":true,\"expiresAt\":" + expiry + "},{\"name\":\"old\",\"value\":\"old\",\"domain\":\".wx.qq.com\",\"path\":\"/\",\"expiresAt\":1}]"));
        try (WebWechatClient client = new WebWechatClient(new FakeTransport())) {
            client.restore(session.toString());
            var cookies = json(client.snapshot()).getAsJsonArray("cookies");
            assertEquals(1, cookies.size());
            assertEquals(expiry, cookies.get(0).getAsJsonObject().get("expiresAt").getAsLong());
        }
    }

    /** 构造一页同步消息响应。 */
    private static String syncPage(String id, int more, int key) {
        return "{\"BaseResponse\":{\"Ret\":0},\"ContinueFlag\":" + more + ",\"SyncKey\":{\"Count\":1,\"List\":[{\"Key\":1,\"Val\":" + key + "}]},\"AddMsgList\":[{\"MsgType\":1,\"MsgId\":\"" + id + "\",\"FromUserName\":\"@friend\",\"ToUserName\":\"@me\",\"Content\":\"消息\"}]}";
    }

    /** 解析测试协议对象。 */
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    /** 依次返回预设响应，并验证调用接口及请求正文。 */
    private static final class FakeTransport implements WebWechatClient.Transport {
        final Queue<String> paths = new ArrayDeque<>();
        final Queue<byte[]> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        /** 添加期望请求路径和对应响应。 */
        void add(String path, String response) { paths.add(path); responses.add(response.getBytes(StandardCharsets.UTF_8)); }

        /** 返回下一项预设响应，不允许额外网络访问。 */
        @Override
        public byte[] call(String url, String body) {
            assertFalse(paths.isEmpty(), "出现未预期的协议请求");
            assertTrue(URI.create(url).getPath().endsWith(paths.remove()));
            urls.add(url);
            bodies.add(body);
            return responses.remove();
        }
    }
}
