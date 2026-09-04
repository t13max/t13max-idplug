package com.t13max.idplug.wechat.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.t13max.idplug.wechat.model.ChatMessage;
import com.t13max.idplug.wechat.model.Contact;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** 使用原插件的微信网页版协议，隔离会话并显式处理服务端错误。 */
public final class WebWechatClient implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static final String LOGIN = "https://login.weixin.qq.com";
    private static final Set<String> HOSTS = Set.of("wx.qq.com", "wx2.qq.com", "wx8.qq.com", "web.wechat.com", "web2.wechat.com");
    private static final AtomicLong MESSAGE_ID = new AtomicLong();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final Map<String, Long> cookieExpiry = new HashMap<>();
    private final Map<String, JsonObject> contacts = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final Transport transport;
    private Session session = new Session();
    private volatile boolean closed;

    /** 创建有超时限制且不自动跨域跳转的独立 HTTP 客户端。 */
    public WebWechatClient() {
        http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        transport = this::request;
    }

    /** 注入传输实现，用于无需登录真实账号的协议回归测试。 */
    WebWechatClient(Transport transport) {
        this.http = null;
        this.transport = transport;
    }

    /** 获取并显示二维码，等待手机确认后初始化会话。 */
    public void login(Consumer<BufferedImage> onQr, Consumer<String> onStatus) throws Exception {
        String response = get(LOGIN + "/jslogin?appid=wx782c26e4c19acffb&fun=new&lang=zh_CN&_=" + System.currentTimeMillis());
        String uuid = capture("window\\.QRLogin\\.uuid\\s*=\\s*\"([^\"]+)\"", response);
        if (!response.matches("(?s).*QRLogin\\.code\\s*=\\s*200\\s*;.*")) { throw new IOException("获取登录二维码失败"); }
        BufferedImage qr = ImageIO.read(new ByteArrayInputStream(transport.call(LOGIN + "/qrcode/" + encode(uuid), null)));
        if (qr == null) { throw new IOException("二维码图片无效，请重试"); }
        onQr.accept(qr);
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
        int tip = 1;
        while (!closed && System.currentTimeMillis() < deadline) {
            String result = get(LOGIN + "/cgi-bin/mmwebwx-bin/login?loginicon=true&uuid=" + encode(uuid) + "&tip=" + tip + "&_=" + System.currentTimeMillis());
            String code = capture("window\\.code\\s*=\\s*(\\d+)", result);
            if ("200".equals(code)) {
                String redirect = capture("window\\.redirect_uri\\s*=\\s*\"([^\"]+)\"", result);
                completeLogin(redirect);
                initialize();
                return;
            }
            if ("201".equals(code)) { tip = 0; onStatus.accept("已扫码，请在手机上确认登录"); }
            else if (!"408".equals(code)) { throw new IOException("二维码已失效，请重新扫码"); }
            Thread.sleep(300);
        }
        throw new IOException("二维码已过期，请重新扫码");
    }

    /** 校验微信登录跳转并安全解析登录 XML，不记录票据内容。 */
    private void completeLogin(String redirect) throws Exception {
        URI uri = URI.create(redirect);
        validateEndpoint(uri);
        String xml = get(redirect + "&fun=new&version=v2&mod=desktop&lang=zh_CN");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        if (!"0".equals(xmlText(doc, "ret"))) { throw new SessionExpiredException("微信拒绝网页版登录，请确认账号允许使用网页版微信"); }
        synchronized (this) {
            session.baseUrl = "https://" + uri.getHost() + "/cgi-bin/mmwebwx-bin";
            session.skey = xmlText(doc, "skey");
            session.sid = xmlText(doc, "wxsid");
            session.uin = xmlText(doc, "wxuin");
            session.ticket = xmlText(doc, "pass_ticket");
            session.device = "e" + (100000000000000L + new SecureRandom().nextLong(900000000000000L));
            if (session.sid.isBlank() || session.skey.isBlank() || session.uin.isBlank()) { throw new IOException("微信登录响应缺少会话信息"); }
        }
    }

    /** 首次登录时加载自己、最近联系人及同步游标。 */
    private void initialize() throws Exception {
        JsonObject result = post(api("webwxinit"), body());
        JsonObject user = result.getAsJsonObject("User");
        synchronized (this) {
            session.userId = string(user, "UserName");
            session.nickname = plainText(string(user, "NickName"));
            session.syncKey = result.getAsJsonObject("SyncKey");
        }
        if (userId().isBlank() || session.syncKey == null) { throw new IOException("微信初始化响应无效"); }
        mergeContacts(array(result, "ContactList"));
        fetchContacts();
        JsonObject notify = body();
        notify.addProperty("Code", 3);
        notify.addProperty("FromUserName", userId());
        notify.addProperty("ToUserName", userId());
        notify.addProperty("ClientMsgId", System.currentTimeMillis());
        post(api("webwxstatusnotify"), notify);
    }

    /** 恢复完整会话，先由同步接口验证，失效时交给界面重新扫码。 */
    public synchronized void restore(String saved) throws IOException {
        try {
            Session restored = JSON.fromJson(saved, Session.class);
            if (restored == null || restored.baseUrl == null || restored.sid == null || restored.skey == null || restored.uin == null || restored.userId == null || restored.syncKey == null) { throw new IllegalArgumentException(); }
            validateEndpoint(URI.create(restored.baseUrl));
            session = restored;
            long now = System.currentTimeMillis();
            for (SavedCookie savedCookie : restored.cookies) {
                if (savedCookie.expiresAt != -1 && savedCookie.expiresAt <= now) { continue; }
                String domain = savedCookie.domain == null ? "" : savedCookie.domain.replaceFirst("^\\.", "");
                if (!(domain.equals("qq.com") || domain.endsWith(".qq.com") || domain.equals("wechat.com") || domain.endsWith(".wechat.com"))) { continue; }
                HttpCookie cookie = new HttpCookie(savedCookie.name, savedCookie.value);
                cookie.setDomain(savedCookie.domain);
                cookie.setPath(savedCookie.path);
                cookie.setSecure(savedCookie.secure);
                cookie.setHttpOnly(savedCookie.httpOnly);
                cookie.setVersion(0);
                cookie.setMaxAge(savedCookie.expiresAt == -1 ? -1 : Math.max(1, (savedCookie.expiresAt - now) / 1000));
                cookies.getCookieStore().add(URI.create(session.baseUrl), cookie);
                cookieExpiry.put(cookieKey(cookie), savedCookie.expiresAt);
            }
        } catch (RuntimeException error) {
            throw new IOException("保存的微信会话无法恢复，请重新扫码");
        }
    }

    /** 导出当前同步游标和 Cookie，仅允许写入 IDE 的安全凭据存储。 */
    public synchronized String snapshot() {
        session.cookies = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            long expiry = cookieExpiry.computeIfAbsent(cookieKey(cookie), key -> cookie.getMaxAge() < 0 ? -1 : now + cookie.getMaxAge() * 1000);
            session.cookies.add(new SavedCookie(cookie.getName(), cookie.getValue(), cookie.getDomain(), cookie.getPath(), cookie.getSecure(), cookie.isHttpOnly(), expiry));
        }
        return JSON.toJson(session);
    }

    /** 使用 Cookie 值区分服务端续期，避免不断延长旧 Cookie 的有效期。 */
    private static String cookieKey(HttpCookie cookie) {
        return cookie.getName() + "\n" + cookie.getDomain() + "\n" + cookie.getPath() + "\n" + cookie.getValue();
    }

    /** 长轮询检查新消息，仅在服务端通知变化后拉取消息。 */
    public List<ChatMessage> poll() throws Exception {
        Session current;
        synchronized (this) { current = JSON.fromJson(JSON.toJson(session), Session.class); }
        if (current.moreMessages) { return sync(); }
        String host = URI.create(current.baseUrl).getHost();
        String key = syncKey(current.syncCheckKey == null ? current.syncKey : current.syncCheckKey);
        String url = "https://webpush." + host + "/cgi-bin/mmwebwx-bin/synccheck?r=" + System.currentTimeMillis() + "&skey=" + encode(current.skey) + "&sid=" + encode(current.sid) + "&uin=" + encode(current.uin) + "&deviceid=" + encode(current.device) + "&synckey=" + encode(key) + "&_=" + System.currentTimeMillis();
        String result = get(url);
        String code = capture("retcode\\s*:\\s*\"(\\d+)\"", result);
        if (!"0".equals(code)) { throw new SessionExpiredException("微信登录已失效，请重新扫码（" + code + "）"); }
        String selector = capture("selector\\s*:\\s*\"(\\d+)\"", result);
        return "0".equals(selector) ? List.of() : sync();
    }

    /** 拉取一批消息后更新同步游标，交给服务先保存历史再保存会话。 */
    private List<ChatMessage> sync() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        {
            JsonObject request = body();
            synchronized (this) { request.add("SyncKey", session.syncKey.deepCopy()); }
            request.addProperty("rr", ~System.currentTimeMillis());
            JsonObject response = post(api("webwxsync") + "&sid=" + encode(session.sid) + "&skey=" + encode(session.skey), request);
            mergeContacts(array(response, "ModContactList"));
            for (JsonElement deleted : array(response, "DelContactList")) { contacts.remove(string(deleted.getAsJsonObject(), "UserName")); }
            for (JsonElement item : array(response, "AddMsgList")) {
                ChatMessage message = parseMessage(item.getAsJsonObject());
                if (message != null) { messages.add(message); }
            }
            synchronized (this) {
                if (response.has("SyncKey")) { session.syncKey = response.getAsJsonObject("SyncKey"); }
                if (response.has("SyncCheckKey")) { session.syncCheckKey = response.getAsJsonObject("SyncCheckKey"); }
                session.moreMessages = number(response, "ContinueFlag", 0) != 0;
            }
        }
        return messages;
    }

    /** 按分页读取联系人，避免大通讯录只显示第一页。 */
    public void fetchContacts() throws Exception {
        int sequence = 0;
        do {
            JsonObject response = parse(get(api("webwxgetcontact") + "&skey=" + encode(session.skey) + "&seq=" + sequence + "&r=" + System.currentTimeMillis()));
            check(response);
            mergeContacts(array(response, "MemberList"));
            sequence = number(response, "Seq", 0);
        } while (sequence != 0 && !closed);
    }

    /** 合并微信联系人和群成员资料。 */
    private void mergeContacts(JsonArray list) {
        for (JsonElement element : list) {
            JsonObject contact = element.getAsJsonObject();
            String id = string(contact, "UserName");
            if (!id.isBlank()) { contacts.put(id, contact); }
        }
    }

    /** 返回可展示联系人，排除自己和系统通知账号。 */
    public List<Contact> contacts() {
        return contacts.values().stream().filter(item -> !string(item, "UserName").equals(userId())).map(item -> new Contact(string(item, "UserName"), plainText(string(item, "NickName")), plainText(string(item, "RemarkName")))).toList();
    }

    /** 判断联系人是否属于本次会话，禁止给旧登录标识误发消息。 */
    public boolean canSend(String id) {
        return contacts.containsKey(id);
    }

    /** 同步缺失的联系人或群资料，使新会话也能显示备注和成员昵称。 */
    private void ensureContact(String id) throws Exception {
        JsonObject known = contacts.get(id);
        if (known != null && (!id.startsWith("@@") || !array(known, "MemberList").isEmpty())) { return; }
        JsonObject item = new JsonObject();
        item.addProperty("UserName", id);
        item.addProperty("EncryChatRoomId", "");
        JsonArray list = new JsonArray();
        list.add(item);
        JsonObject request = body();
        request.addProperty("Count", 1);
        request.add("List", list);
        JsonObject result = post(api("webwxbatchgetcontact") + "&type=ex&r=" + System.currentTimeMillis(), request);
        mergeContacts(array(result, "ContactList"));
    }

    /** 解析私聊、群聊和从手机发出的消息，非文本消息显示类型占位。 */
    ChatMessage parseMessage(JsonObject raw) throws Exception {
        int type = number(raw, "MsgType", 0);
        if (type == 51) { return null; }
        String from = string(raw, "FromUserName");
        String to = string(raw, "ToUserName");
        boolean outgoing = from.equals(userId());
        String conversation = outgoing ? to : from;
        if (conversation.isBlank()) { return null; }
        ensureContact(conversation);
        String content = string(raw, "Content");
        String sender = outgoing ? nickname() : displayName(from);
        if (!outgoing && conversation.startsWith("@@")) {
            int separator = content.indexOf(":<br/>");
            if (separator >= 0) {
                String memberId = content.substring(0, separator);
                sender = memberName(conversation, memberId);
                content = content.substring(separator + 6);
            }
        }
        String text = switch (type) {
            case 1, 10000 -> plainText(content);
            case 3 -> "[图片，请在手机查看]";
            case 34 -> "[语音，请在手机查看]";
            case 43, 62 -> "[视频，请在手机查看]";
            case 47 -> "[表情，请在手机查看]";
            case 49 -> "[分享或文件，请在手机查看]";
            case 42 -> "[联系人名片]";
            case 10002 -> "[消息已撤回]";
            default -> "[非文本消息，请在手机查看]";
        };
        long time = raw.has("CreateTime") ? raw.get("CreateTime").getAsLong() * 1000 : System.currentTimeMillis();
        return new ChatMessage(string(raw, "MsgId"), string(raw, "ClientMsgId"), conversation, sender, text, time, outgoing);
    }

    /** 显示群成员备注、群昵称或普通昵称。 */
    private String memberName(String groupId, String memberId) {
        JsonObject friend = contacts.get(memberId);
        if (friend != null && !string(friend, "RemarkName").isBlank()) { return plainText(string(friend, "RemarkName")); }
        for (JsonElement member : array(contacts.get(groupId), "MemberList")) {
            JsonObject item = member.getAsJsonObject();
            if (memberId.equals(string(item, "UserName"))) {
                String name = string(item, "DisplayName");
                return plainText(name.isBlank() ? string(item, "NickName") : name);
            }
        }
        return displayName(memberId);
    }

    /** 获取备注优先的联系人名称。 */
    private String displayName(String id) {
        JsonObject contact = contacts.get(id);
        return contact == null ? id : new Contact(id, plainText(string(contact, "NickName")), plainText(string(contact, "RemarkName"))).displayName();
    }

    /** 发送文本并检查微信返回码，成功后才生成本地消息。 */
    public ChatMessage send(String recipient, String text) throws Exception {
        if (!canSend(recipient)) { throw new IOException("此历史联系人的标识已失效，请在当前联系人列表重新选择"); }
        String id = Long.toString(MESSAGE_ID.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis() * 10000)));
        JsonObject message = new JsonObject();
        message.addProperty("Type", 1);
        message.addProperty("Content", text);
        message.addProperty("FromUserName", userId());
        message.addProperty("ToUserName", recipient);
        message.addProperty("LocalID", id);
        message.addProperty("ClientMsgId", id);
        JsonObject request = body();
        request.add("Msg", message);
        request.addProperty("Scene", 0);
        JsonObject response = post(api("webwxsendmsg"), request);
        return new ChatMessage(string(response, "MsgID"), id, recipient, nickname(), text, System.currentTimeMillis(), true);
    }

    /** 显式退出服务端会话，正常关闭 IDE 不调用此接口。 */
    public void logout() throws Exception {
        if (session.baseUrl == null) { return; }
        get(api("webwxlogout") + "&redirect=1&type=0&skey=" + encode(session.skey) + "&sid=" + encode(session.sid) + "&uin=" + encode(session.uin));
    }

    /** 返回稳定账号标识，用于隔离本地历史。 */
    public synchronized String accountId() { return session.uin; }

    /** 返回本次登录的自己标识。 */
    public synchronized String userId() { return session.userId == null ? "" : session.userId; }

    /** 返回当前登录昵称。 */
    public synchronized String nickname() { return session.nickname == null ? "我" : session.nickname; }

    /** 构建微信基础请求字段。 */
    private synchronized JsonObject body() {
        JsonObject base = new JsonObject();
        base.addProperty("Uin", session.uin);
        base.addProperty("Sid", session.sid);
        base.addProperty("Skey", session.skey);
        base.addProperty("DeviceID", session.device);
        JsonObject request = new JsonObject();
        request.add("BaseRequest", base);
        return request;
    }

    /** 构建当前会话下的接口 URL，所有凭据只发送给微信服务器。 */
    private synchronized String api(String method) {
        return session.baseUrl + "/" + method + "?lang=zh_CN&pass_ticket=" + encode(session.ticket);
    }

    /** 发送 JSON 并校验协议返回码。 */
    private JsonObject post(String url, JsonObject body) throws Exception {
        JsonObject result = parse(new String(transport.call(url, JSON.toJson(body)), StandardCharsets.UTF_8));
        check(result);
        return result;
    }

    /** 获取 UTF-8 响应。 */
    private String get(String url) throws Exception {
        return new String(transport.call(url, null), StandardCharsets.UTF_8);
    }

    /** 执行有超时的网络请求，不将 URL 或服务器消息正文放进异常提示。 */
    private byte[] request(String url, String body) throws Exception {
        if (closed) { throw new InterruptedException("微信连接已关闭"); }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40)).header("User-Agent", "Mozilla/5.0").header("Accept", "*/*");
        if (URI.create(url).getPath().endsWith("/webwxnewloginpage")) {
            Properties properties = new Properties();
            try (var stream = WebWechatClient.class.getResourceAsStream("/wechat-login.properties")) {
                if (stream == null) { throw new IOException("微信登录兼容配置缺失"); }
                properties.load(stream);
            }
            builder.header("client-version", "2.0.0").header("extspam", properties.getProperty("extspam"));
        }
        if (body != null) { builder.header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)); }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) { throw new IOException("微信请求失败，HTTP " + response.statusCode()); }
        return response.body();
    }

    /** 将协议错误转换成可恢复的会话错误或普通请求错误。 */
    private static void check(JsonObject response) throws IOException {
        JsonObject base = response.getAsJsonObject("BaseResponse");
        if (base == null || !base.has("Ret")) { throw new IOException("微信响应格式无效"); }
        int code = base.get("Ret").getAsInt();
        if (code == 1100 || code == 1101 || code == 1102 || code == 1203) { throw new SessionExpiredException("微信登录已失效，请重新扫码（" + code + "）"); }
        if (code != 0) { throw new IOException("微信请求未成功（" + code + "）"); }
    }

    /** 解析 JSON 响应，不把敏感原文带入异常。 */
    private static JsonObject parse(String response) throws IOException {
        try { return JsonParser.parseString(response).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("微信返回的数据无法解析"); }
    }

    /** 严格限制登录和恢复地址，防止票据发送到非微信站点。 */
    static void validateEndpoint(URI uri) {
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || !uri.getPath().startsWith("/cgi-bin/mmwebwx-bin")) { throw new IllegalArgumentException("无效的微信服务器地址"); }
    }

    /** 将同步键列表转换成长轮询参数。 */
    private static String syncKey(JsonObject object) {
        List<String> entries = new ArrayList<>();
        for (JsonElement element : array(object, "List")) {
            JsonObject item = element.getAsJsonObject();
            entries.add(string(item, "Key") + "_" + string(item, "Val"));
        }
        return String.join("|", entries);
    }

    /** 提取协议字段，匹配失败时隐藏响应内容。 */
    private static String capture(String pattern, String input) throws IOException {
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        if (!matcher.find()) { throw new IOException("微信响应格式发生变化，请重试"); }
        return matcher.group(1);
    }

    /** 读取 XML 元素的纯文本。 */
    private static String xmlText(Document doc, String tag) {
        return doc.getElementsByTagName(tag).getLength() == 0 ? "" : doc.getElementsByTagName(tag).item(0).getTextContent();
    }

    /** 读取可选 JSON 字符串。 */
    private static String string(JsonObject object, String name) {
        return object == null || !object.has(name) || object.get(name).isJsonNull() ? "" : object.get(name).getAsString();
    }

    /** 读取可选 JSON 数字。 */
    private static int number(JsonObject object, String name, int fallback) {
        return object.has(name) ? object.get(name).getAsInt() : fallback;
    }

    /** 读取可选 JSON 数组。 */
    private static JsonArray array(JsonObject object, String name) {
        return object == null || !object.has(name) || !object.get(name).isJsonArray() ? new JsonArray() : object.getAsJsonArray(name);
    }

    /** 编码 URL 参数，避免特殊字符破坏登录票据。 */
    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

    /** 将微信文本标记转换成纯文本，界面不渲染远程 HTML。 */
    public static String plainText(String value) {
        return value.replaceAll("(?i)<br\\s*/?>", "\n").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** 关闭连接并中断长轮询，保留服务端登录状态。 */
    @Override
    public void close() {
        closed = true;
        if (http != null) { http.shutdownNow(); }
    }

    /** 抽象 HTTP 传输以支持离线协议验证。 */
    @FunctionalInterface
    interface Transport {
        /** 发起 GET 或 JSON POST 并返回响应字节。 */
        byte[] call(String url, String body) throws Exception;
    }

    /** 标识需要重新扫码的失效会话。 */
    public static final class SessionExpiredException extends IOException {
        /** 构造不包含凭据或消息内容的失效提示。 */
        public SessionExpiredException(String message) { super(message); }
    }

    /** 保存恢复登录需要的完整会话，只进入安全凭据存储。 */
    private static final class Session {
        String baseUrl;
        String skey;
        String sid;
        String uin;
        String ticket;
        String device;
        String userId;
        String nickname;
        JsonObject syncKey;
        JsonObject syncCheckKey;
        boolean moreMessages;
        List<SavedCookie> cookies = new ArrayList<>();
    }

    /** 使用绝对到期时间保存 Cookie，避免重启延长有效期。 */
    private record SavedCookie(String name, String value, String domain, String path, boolean secure, boolean httpOnly, long expiresAt) {
    }
}
