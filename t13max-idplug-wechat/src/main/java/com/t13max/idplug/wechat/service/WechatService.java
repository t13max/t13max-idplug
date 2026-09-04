package com.t13max.idplug.wechat.service;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.util.Disposer;
import com.t13max.idplug.wechat.client.WebWechatClient;
import com.t13max.idplug.wechat.model.ChatHistory;
import com.t13max.idplug.wechat.model.ChatMessage;
import com.t13max.idplug.wechat.model.Contact;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 全应用共享一个微信会话，多项目窗口只订阅消息状态。 */
@Service(Service.Level.APP)
public final class WechatService implements Disposable {
    private static final CredentialAttributes CREDENTIALS = new CredentialAttributes("T13max WeChat Session");
    private final ChatHistory history = new ChatHistory(Path.of(PathManager.getConfigPath(), "t13max-wechat", "history.json"));
    private final ExecutorService operations = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "Wechat storage and send"));
    private final ExecutorService receiver = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "Wechat receive"));
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private volatile WebWechatClient client;
    private volatile State state = State.OFFLINE;
    private volatile String status = "尚未登录，请扫码登录";
    private volatile String storageWarning = "";
    private volatile BufferedImage qr;
    private volatile boolean disposed;

    /** 获取应用级微信服务。 */
    public static WechatService getInstance() { return ApplicationManager.getApplication().getService(WechatService.class); }

    /** 创建可随 IDE 退出的后台线程。 */
    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** 启动时加载历史并自动尝试恢复安全存储中的会话。 */
    public void start() {
        if (!started.compareAndSet(false, true) || disposed) { return; }
        operations.execute(() -> {
            try { history.load(); }
            catch (Exception error) { storageWarning = "历史读取失败；请检查配置目录中的历史备份"; }
            refresh();
            begin(true);
        });
    }

    /** 用户主动获取新的登录二维码。 */
    public void login() {
        start();
        operations.execute(() -> begin(false));
    }

    /** 用户在网络恢复后重试已保存的会话。 */
    public void reconnect() {
        operations.execute(() -> begin(true));
    }

    /** 建立新连接并通过代次隔离旧请求的迟到回调。 */
    private void begin(boolean restore) {
        if (disposed) { return; }
        long token = generation.incrementAndGet();
        WebWechatClient previous = client;
        if (previous != null) { previous.close(); }
        WebWechatClient next = new WebWechatClient();
        client = next;
        qr = null;
        state = State.CONNECTING;
        status = restore ? "正在恢复微信登录…" : "正在获取登录二维码…";
        refresh();
        receiver.execute(() -> connect(next, token, restore));
    }

    /** 在接收线程执行登录和长轮询，界面线程不等待网络。 */
    private void connect(WebWechatClient next, long token, boolean restore) {
        try {
            List<ChatMessage> initial = List.of();
            if (restore) {
                Credentials saved = PasswordSafe.getInstance().get(CREDENTIALS);
                String secret = saved == null ? null : saved.getPasswordAsString();
                if (secret == null || secret.isBlank()) {
                    offline(token, "尚未保存登录状态，请扫码登录");
                    return;
                }
                next.restore(secret);
                initial = next.poll();
                next.fetchContacts();
            } else {
                next.login(image -> showQr(token, image), text -> updateStatus(token, State.LOGIN, text));
            }
            List<ChatMessage> restoredMessages = initial;
            operations.submit(() -> {
                if (!active(token)) { return; }
                history.selectAccount(next.accountId());
                state = State.ONLINE;
                status = "已登录：" + next.nickname();
                qr = null;
                commit(next, restoredMessages);
            }).get();
            int failures = 0;
            while (active(token)) {
                try {
                    List<ChatMessage> messages = next.poll();
                    operations.submit(() -> {
                        if (!active(token)) { return; }
                        state = State.ONLINE;
                        status = "已登录：" + next.nickname();
                        commit(next, messages);
                    }).get();
                    failures = 0;
                    Thread.sleep(300);
                } catch (WebWechatClient.SessionExpiredException expired) {
                    throw expired;
                } catch (Exception error) {
                    if (!active(token)) { return; }
                    if (++failures >= 5) { throw error; }
                    updateStatus(token, State.CONNECTING, "网络中断，正在重连（" + failures + "/5）…");
                    Thread.sleep(Math.min(1000L << failures, 15000));
                }
            }
        } catch (WebWechatClient.SessionExpiredException expired) {
            operations.execute(() -> {
                if (!active(token)) { return; }
                clearCredentials();
                offline(token, expired.getMessage());
            });
        } catch (Exception error) {
            offline(token, restore ? "登录恢复失败，请重试连接或重新扫码" : "微信连接失败或二维码过期，请重新扫码；账号需支持网页版登录");
        } finally {
            next.close();
        }
    }

    /** 先持久化消息再保存游标，防止崩溃后跳过已拉取的消息。 */
    private void commit(WebWechatClient connection, List<ChatMessage> messages) {
        history.updateContacts(connection.contacts());
        for (ChatMessage message : messages) { history.append(message, false); }
        if (saveHistory()) {
            try {
                PasswordSafe safe = PasswordSafe.getInstance();
                safe.set(CREDENTIALS, new Credentials(connection.accountId(), connection.snapshot()));
                if (safe.isMemoryOnly()) { storageWarning = "IDE 密码存储为仅内存，重启保持登录需在密码设置中启用持久存储"; }
            } catch (Exception error) {
                storageWarning = "登录凭据保存失败，重启后可能需要重新扫码";
            }
        }
        refresh();
    }

    /** 回车或按钮发送，失败时由界面保留原输入内容。 */
    public void send(String recipient, String text, Consumer<String> completion) {
        long token = generation.get();
        WebWechatClient connection = client;
        if (text == null || text.isBlank()) { completion.accept("请输入消息"); return; }
        operations.execute(() -> {
            String error = null;
            try {
                if (!active(token) || state != State.ONLINE || connection == null) { throw new IllegalStateException(); }
                ChatMessage message = connection.send(recipient, text);
                if (active(token)) {
                    history.append(message, true);
                    saveHistory();
                    refresh();
                }
            } catch (Exception failure) {
                error = "发送未确认成功，请核对聊天记录后重试；输入内容已保留";
            }
            String result = error;
            ApplicationManager.getApplication().invokeLater(() -> completion.accept(result));
        });
    }

    /** 显式退出清除安全凭据；普通 IDE 退出只关闭连接。 */
    public void logout() {
        generation.incrementAndGet();
        WebWechatClient previous = client;
        String snapshot = previous == null ? null : previous.snapshot();
        if (previous != null) { previous.close(); }
        client = null;
        qr = null;
        state = State.OFFLINE;
        status = "已退出登录，本地历史仍可查看";
        refresh();
        operations.execute(() -> {
            clearCredentials();
            if (snapshot == null) { return; }
            try (WebWechatClient logoutClient = new WebWechatClient()) {
                logoutClient.restore(snapshot);
                logoutClient.logout();
            } catch (Exception error) {
                status = "本地登录已退出；网络未确认远端退出，可在手机检查登录设备";
                refresh();
            }
        });
    }

    /** 清除凭据失败时明确提示，避免错误声称退出状态已持久化。 */
    private void clearCredentials() {
        try { PasswordSafe.getInstance().set(CREDENTIALS, null); }
        catch (Exception error) { storageWarning = "安全凭据清除失败，请检查 IDE 密码存储"; }
    }

    /** 标记正在查看的会话已读，并在后台保存。 */
    public void markRead(String conversationId) {
        if (history.unread(conversationId) == 0) { return; }
        history.markRead(conversationId);
        operations.execute(this::saveHistory);
        refresh();
    }

    /** 保存历史并将磁盘错误显示给用户。 */
    private boolean saveHistory() {
        try { history.save(); return true; }
        catch (Exception error) { storageWarning = "聊天历史保存失败，请检查磁盘空间和配置目录权限"; return false; }
    }

    /** 注册随窗口销毁的监听，避免多次打开项目造成监听泄漏。 */
    public void subscribe(Disposable parent, Runnable listener) {
        listeners.add(listener);
        Disposer.register(parent, () -> listeners.remove(listener));
    }

    /** 在界面线程通知所有项目窗口和状态栏。 */
    private void refresh() {
        if (disposed) { return; }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed) { listeners.forEach(Runnable::run); }
        });
    }

    /** 显示当前代次的二维码。 */
    private void showQr(long token, BufferedImage image) {
        if (!active(token)) { return; }
        qr = image;
        updateStatus(token, State.LOGIN, "请使用微信扫描二维码");
    }

    /** 更新连接状态并刷新界面。 */
    private void updateStatus(long token, State newState, String text) {
        if (!active(token)) { return; }
        state = newState;
        status = text;
        refresh();
    }

    /** 将当前代次标记为离线。 */
    private void offline(long token, String text) {
        if (!active(token)) { return; }
        qr = null;
        updateStatus(token, State.OFFLINE, text);
    }

    /** 判断异步结果是否仍属于当前登录。 */
    private boolean active(long token) { return !disposed && generation.get() == token; }

    /** 获取当前连接状态。 */
    public State state() { return state; }

    /** 获取不包含聊天正文的状态提示。 */
    public String status() { return status; }

    /** 获取历史或凭据存储提示。 */
    public String storageWarning() { return storageWarning; }

    /** 获取当前二维码图片。 */
    public BufferedImage qr() { return qr; }

    /** 获取线程安全的聊天历史模型。 */
    public ChatHistory history() { return history; }

    /** 判断是否允许向本次登录的联系人发送。 */
    public boolean canSend(Contact contact) { return contact != null && state == State.ONLINE && client != null && client.canSend(contact.id()); }

    /** 销毁服务时停止后台连接，已保存的凭据供下次启动恢复。 */
    @Override
    public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        WebWechatClient connection = client;
        if (connection != null) { connection.close(); }
        receiver.shutdownNow();
        operations.shutdown();
        listeners.clear();
    }

    /** 区分未登录、等待扫码、连接中和已登录四种状态。 */
    public enum State { OFFLINE, LOGIN, CONNECTING, ONLINE }
}
