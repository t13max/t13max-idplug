package com.t13max.idplug.heybox;

import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 在单一应用级浏览器会话中读取官方页面，所有状态在界面线程更新。 */
@Service(Service.Level.APP)
public final class HeyboxReader implements Disposable {
    public static final String HOME = "https://www.xiaoheihe.cn/app/bbs/home";
    private final List<Runnable> listeners = new ArrayList<>();
    private final Timer timer = new Timer(1000, event -> inspect());
    private JBCefBrowser browser;
    private JBCefJSQuery query;
    private JDialog window;
    private JDialog qrWindow;
    private JLabel qrLabel;
    private JLabel qrHint;
    private String qrScript;
    private String lastQrImage = "";
    private String script;
    private PageSnapshot page = new PageSnapshot();
    private PageSnapshot savedList;
    private boolean pendingCommunityDialog;
    private List<String> communities = List.of();
    private String expectedUrl = HOME;
    private String listUrl = HOME;
    private String status = "Click to sign in";
    private int index;
    private int offset;
    private int savedIndex;
    private long deadline;
    private long generation;
    private long lastNavigation;
    private boolean visible = true;
    private boolean disposed;
    private boolean loginRequested;
    private boolean loginChecking;
    private String lastSnapshot = "";
    private int stableSamples;
    private volatile boolean documentReady;
    private volatile long loadCompletedAt;
    private PageSnapshot refreshBaseline;
    private String refreshResult = "";
    private boolean signedIn;
    private boolean logoutRequested;
    private boolean logoutClicked;
    private boolean logoutMenuOpened;
    private int loggedOutSamples;
    private final java.util.concurrent.ExecutorService sessionWorker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "heybox-session"); thread.setDaemon(true); return thread; });
    private boolean sessionStarted;
    private boolean sessionBusy;
    private boolean savingDisabled;
    private boolean savePending;
    private long lastSessionSave;
    private long lastStableSessionGeneration = -1;
    private String sessionNotice = "";

    /** 创建阅读器，状态栏初始化后再尝试恢复安全存储中的会话。 */
    public HeyboxReader() { }

    /** 首次创建状态栏时读取安全存储，恢复完成之前禁止导航和扫码。 */
    public void startSession() {
        if (sessionStarted || disposed) return;
        sessionStarted = true;
        sessionBusy = true;
        status = "Restoring session...";
        changed();
        sessionWorker.execute(() -> {
            try {
                List<SessionCookies.Entry> entries = SessionStore.read();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed) return;
                    if (entries.isEmpty()) { sessionBusy = false; status = "Click to sign in"; changed(); return; }
                    if (!ensureBrowser()) { sessionBusy = false; return; }
                    sessionWorker.execute(() -> {
                        try { SessionStore.restore(entries); finishSessionRestore(true); }
                        catch (Exception ignored) { finishSessionRestore(false); }
                    });
                });
            } catch (Exception ignored) { finishSessionRestore(false); }
        });
    }

    /** 在界面线程结束恢复，网络验证失败不能直接宣称已登录。 */
    private void finishSessionRestore(boolean success) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed) return;
            sessionBusy = false;
            if (success) { navigate(HOME); loginChecking = true; status = "Checking sign-in status..."; changed(); }
            else { sessionNotice = "Session restore failed; sign in again"; status = sessionNotice; changed(); }
        });
    }

    /** 返回不会被帖子文字覆盖的安全存储提示。 */
    public String sessionNotice() { return sessionNotice; }

    /** 登录确认后在串行后台队列保存会话，避免频繁写入安全存储。 */
    private void saveSession() {
        if (!sessionStarted || savingDisabled || savePending || System.currentTimeMillis() - lastSessionSave < 30_000) return;
        savePending = true;
        lastSessionSave = System.currentTimeMillis();
        sessionWorker.execute(() -> {
            boolean success;
            try { SessionStore.save(); success = true; } catch (Exception ignored) { success = false; }
            boolean saved = success;
            ApplicationManager.getApplication().invokeLater(() -> {
                savePending = false;
                if (disposed || savingDisabled) return;
                sessionNotice = saved ? "" : "Session could not be saved; restart may require sign in";
                changed();
            });
        });
    }

    /** 页面稳定后补存一次，捕获登录跳转期间迟到或轮换的 Cookie。 */
    private void saveStableSession() {
        if (!signedIn || savingDisabled || savePending || lastStableSessionGeneration == generation) return;
        lastStableSessionGeneration = generation;
        lastSessionSave = 0;
        saveSession();
    }

    /** 清除操作排在已提交保存之后，防止登出后旧会话被重新写回。 */
    private void clearSession() {
        if (!sessionStarted) return;
        savingDisabled = true;
        sessionWorker.execute(() -> {
            try { SessionStore.clear(); }
            catch (Exception ignored) { ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) { sessionNotice = "Unable to delete saved session; check PasswordSafe"; changed(); } }); }
        });
    }

    /** 获取应用内共享的阅读器。 */
    public static HeyboxReader get() { return ApplicationManager.getApplication().getService(HeyboxReader.class); }

    /** 添加状态栏监听器。 */
    public void addListener(Runnable listener) { listeners.add(listener); }

    /** 移除状态栏监听器。 */
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    /** 判断是否显示状态栏。 */
    public boolean isVisible() { return visible; }

    /** 获取完整单行文字，像素裁剪由状态栏组件负责。 */
    public String text() {
        return status.isBlank() && !page.items.isEmpty() ? ReadingText.marker(page.items.get(index).kind) + " [" + (index + 1) + "/" + page.items.size() + "] " + page.items.get(index).text : status;
    }

    /** 仅翻译提示语，不翻译网页原文。 */
    public String displayText() { return status.isBlank() ? text() : UiLanguage.text(status); }

    /** 切换语言并立即更新现有窗口，选择由应用设置持久化。 */
    public void setEnglish(boolean english) {
        UiLanguage.get().setEnglish(english);
        if (window != null) { window.setTitle(UiLanguage.text("Heybox - Official Sign In / Reader")); translateComponents(window.getContentPane()); }
        if (qrWindow != null) { qrWindow.setTitle(UiLanguage.text("Heybox - Scan QR")); translateComponents(qrWindow.getContentPane()); }
        changed();
    }

    /** 更新插件创建的 Swing 文案，不访问网页 DOM。 */
    private void translateComponents(Component component) {
        if (component instanceof JLabel label) label.setText(UiLanguage.text(label.getText()));
        if (component instanceof AbstractButton button) button.setText(UiLanguage.text(button.getText()));
        if (browser != null && component == browser.getComponent()) return;
        if (component instanceof Container container) for (Component child : container.getComponents()) translateComponents(child);
    }

    /** 返回最近确认的登录状态，不通过是否有帖子推断登录。 */
    public boolean isSignedIn() { return signedIn; }

    /** 通过官方账号菜单退出当前网页会话，不删除全局 Cookie 或浏览器缓存。 */
    public void signOut() {
        if (browser == null || !signedIn) return;
        clearSession();
        loginRequested = false;
        loginChecking = false;
        logoutRequested = true;
        logoutClicked = false;
        logoutMenuOpened = false;
        loggedOutSamples = 0;
        refreshBaseline = null;
        pendingCommunityDialog = false;
        if (qrWindow != null) qrWindow.setVisible(false);
        clearQr("");
        savedList = null;
        page = new PageSnapshot();
        communities = List.of();
        index = 0;
        generation++;
        expectedUrl = browser.getCefBrowser().getURL();
        status = "Signing out...";
        startInspection(30_000);
        changed();
    }

    /** 依据明确登录按钮或头像连续确认账号状态，避免页面加载时误判登出。 */
    private boolean updateAuthentication(PageSnapshot incoming) {
        if (incoming.signedIn) { signedIn = true; loggedOutSamples = 0; if (loginRequested) savingDisabled = false; if (!logoutRequested) saveSession(); }
        else if (incoming.signedOut && ++loggedOutSamples >= 3) {
            if (loggedOutSamples == 3) clearSession();
            signedIn = false;
            if (logoutRequested) {
                logoutRequested = false;
                generation++;
                timer.stop();
                savedList = null;
                communities = List.of();
                page = new PageSnapshot();
                expectedUrl = HOME;
                status = "Signed out; click to sign in";
                changed();
                return true;
            }
        } else if (!incoming.signedOut) { loggedOutSamples = 0; }
        return false;
    }

    /** 返回最近一次刷新结果，便于区分未刷新和内容未变化。 */
    public String refreshResult() { return refreshResult; }

    /** 广播文本和显隐变化。 */
    private void changed() { if (!disposed) List.copyOf(listeners).forEach(Runnable::run); }

    /** 切换隐蔽状态。 */
    public void toggle() { visible = !visible; changed(); }

    /** 按实际字体宽度限制像素滚动，末尾不留出可继续滚动的空白。 */
    public void scroll(int delta, FontMetrics metrics, int width) { int textWidth = metrics.stringWidth(displayText()); offset = TextScroll.clamp(TextScroll.clamp(offset, textWidth, width) + delta * 36L, textWidth, width); changed(); }

    /** 取得用于绘制的像素偏移。 */
    public int scrollOffset() { return offset; }

    /** 判断是否位于帖子内，加载期间也能立即退出。 */
    public boolean insidePost() { return expectedUrl.matches("https://www[.]xiaoheihe[.]cn/app/bbs/link/[0-9]+"); }

    /** 同一个快捷键根据列表或帖子状态进入和退出。 */
    public void enterOrExit() { if (insidePost()) back(); else open(); }

    /** 在已加载内容中切换，不发送网络请求。 */
    public void move(int delta) {
        if (!page.items.isEmpty()) { index = Math.floorMod(index + delta, page.items.size()); offset = 0; status = ""; changed(); }
    }

    /** 初始化内置浏览器、只读桥接和可重复打开的窗口。 */
    private boolean ensureBrowser() {
        if (browser != null) return true;
        if (!JBCefApp.isSupported()) { status = "JCEF unavailable; use the bundled JetBrains Runtime"; changed(); return false; }
        try (var input = HeyboxReader.class.getResourceAsStream("/reader.js"); var qrInput = HeyboxReader.class.getResourceAsStream("/login-qr.js")) {
            if (input == null) throw new IllegalStateException("Reader script missing");
            if (qrInput == null) throw new IllegalStateException("QR script missing");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (script.endsWith(";")) script = script.substring(0, script.length() - 1);
            qrScript = new String(qrInput.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (qrScript.endsWith(";")) qrScript = qrScript.substring(0, qrScript.length() - 1);
            browser = JBCefBrowser.createBuilder().setUrl("about:blank").setOffScreenRendering(true).build();
            query = JBCefJSQuery.create((JBCefBrowserBase) browser);
            query.addHandler(payload -> {
                if (payload != null && payload.length() < 3_100_000) ApplicationManager.getApplication().invokeLater(() -> accept(payload));
                return null;
            });
            browser.getJBCefClient().getCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                /** 主页面开始加载后不再提取旧文档。 */
                @Override
                public void onLoadingStateChange(CefBrowser source, boolean loading, boolean canBack, boolean canForward) { documentReady = !loading; if (!loading) loadCompletedAt = System.currentTimeMillis(); }
            });
            browser.getJBCefClient().getCefClient().addRequestHandler(new CefRequestHandlerAdapter() {
                /** 阻止主窗口访问官方站点以外的地址，不影响官方页面资源。 */
                @Override
                public boolean onBeforeBrowse(CefBrowser source, CefFrame frame, CefRequest request, boolean gesture, boolean redirect) {
                    if (!frame.isMain()) return false;
                    String url = request.getURL();
                    return !"about:blank".equals(url) && !url.startsWith("https://www.xiaoheihe.cn/") && !url.startsWith("https://login.xiaoheihe.cn/");
                }
            });
            browser.getJBCefClient().getCefClient().addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                /** 禁止页面自行弹出其他窗口。 */
                @Override
                public boolean onBeforePopup(CefBrowser source, CefFrame frame, String target, String name) { return true; }
            });
            window = new JDialog((Frame) null, UiLanguage.text("Heybox - Official Sign In / Reader"), false);
            window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            window.setSize(1020, 760);
            window.setLocationRelativeTo(null);
            JPanel root = new JPanel(new BorderLayout());
            root.add(new JLabel(UiLanguage.text("  Sign in on the official page. Close this window to keep reading in the status bar.")), BorderLayout.NORTH);
            root.add(browser.getComponent(), BorderLayout.CENTER);
            window.setContentPane(root);
            browser.getComponent().setSize(1000, 700);
            browser.getCefBrowser().createImmediately();
            browser.getCefBrowser().wasResized(1000, 700);
            window.addWindowListener(new WindowAdapter() {
                /** 关闭网页窗口后进行有界提取，不销毁登录会话。 */
                @Override
                public void windowClosing(WindowEvent event) { loginRequested = false; loginChecking = false; startInspection(30_000); }
            });
            return true;
        } catch (Exception error) {
            status = "Unable to initialize Heybox browser";
            if (query != null) Disposer.dispose(query);
            if (browser != null) Disposer.dispose(browser);
            query = null;
            browser = null;
            changed();
            return false;
        }
    }

    /** 先检查官方会话，确认未登录后才展示二维码窗口。 */
    public void signIn() {
        if (sessionBusy || loginChecking || logoutRequested) return;
        if (!ensureBrowser()) return;
        if (System.currentTimeMillis() - lastNavigation < 2000) return;
        savingDisabled = false;
        lastSessionSave = 0;
        window.setVisible(false);
        if (qrWindow != null) qrWindow.setVisible(false);
        clearQr("");
        loginRequested = true;
        navigate(HOME);
        loginChecking = true;
        loggedOutSamples = 0;
        status = "Checking sign-in status...";
        startInspection(30_000);
        changed();
    }

    /** 未登录状态连续确认后才打开扫码窗口并启动有界提取。 */
    private void showConfirmedQr() {
        ensureQrWindow();
        clearQr("Loading official QR code...");
        qrWindow.setVisible(true);
        qrWindow.toFront();
        status = "Loading official QR code...";
        startInspection(300_000);
    }

    /** 两种提取回调共用成功处理，避免二维码回调已登录但窗口仍为空白。 */
    private void completeSignIn() {
        signedIn = true;
        loggedOutSamples = 0;
        savingDisabled = false;
        loginChecking = false;
        loginRequested = false;
        if (window != null) window.setVisible(false);
        if (qrWindow != null) qrWindow.setVisible(false);
        clearQr("");
        saveSession();
        startInspection(30_000);
        status = "Signed in; loading your feed...";
        changed();
    }

    /** 检查阶段不以匿名帖子或加载骨架推断登录，等待明确账号标记。 */
    private void checkLogin(PageSnapshot incoming) {
        if (incoming.signedIn && (loginChecking || loginRequested)) completeSignIn();
        else if (loginChecking && incoming.signedOut && loggedOutSamples >= 3) {
            loginChecking = false;
            if (loginRequested) showConfirmedQr();
            else status = "Click to sign in";
        }
    }

    /** 创建只显示二维码、提示和备用入口的紧凑窗口。 */
    private void ensureQrWindow() {
        if (qrWindow != null) return;
        qrWindow = new JDialog((Frame) null, UiLanguage.text("Heybox - Scan QR"), false);
        qrWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        qrWindow.setResizable(false);
        qrLabel = new JLabel("", SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(260, 260));
        qrLabel.setOpaque(true);
        qrLabel.setBackground(Color.WHITE);
        qrHint = new JLabel(UiLanguage.text("Loading official QR code..."), SwingConstants.CENTER);
        JPanel content = new JPanel(new BorderLayout(6, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(qrHint, BorderLayout.NORTH);
        content.add(qrLabel, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton refresh = new JButton(UiLanguage.text("Refresh QR"));
        refresh.addActionListener(event -> signIn());
        JButton official = new JButton(UiLanguage.text("Official Page"));
        official.addActionListener(event -> showBrowser());
        buttons.add(refresh);
        buttons.add(official);
        content.add(buttons, BorderLayout.SOUTH);
        qrWindow.setContentPane(content);
        qrWindow.pack();
        qrWindow.setLocationRelativeTo(null);
        qrWindow.addWindowListener(new WindowAdapter() {
            /** 用户关闭窗口后停止登录提取，清除内存中的二维码。 */
            @Override
            public void windowClosing(WindowEvent event) { loginRequested = false; loginChecking = false; clearQr("Sign-in closed"); startInspection(30_000); }
        });
    }

    /** 清除旧二维码，避免过期或验证状态下继续展示。 */
    private void clearQr(String hint) {
        lastQrImage = "";
        if (qrLabel != null) { qrLabel.setIcon(null); qrHint.setText(UiLanguage.text(hint)); }
    }

    /** 根据受限图片数据更新二维码，不读取网页账号凭据。 */
    private void acceptQr(com.google.gson.JsonObject data) {
        if (!loginRequested || logoutRequested) return;
        String state = data.get("state").getAsString();
        if ("signedIn".equals(state)) { completeSignIn(); return; }
        if (loginChecking || qrWindow == null || !qrWindow.isVisible()) return;
        if ("ready".equals(state)) {
            String image = data.get("image").getAsString();
            if (!image.equals(lastQrImage)) {
                var decoded = QrImage.decode(image);
                qrLabel.setIcon(new ImageIcon(decoded.getScaledInstance(240, 240, Image.SCALE_REPLICATE)));
                lastQrImage = image;
            }
            qrHint.setText(UiLanguage.text("Scan with the Heybox app"));
        } else if ("expired".equals(state)) {
            clearQr("QR expired - click Refresh QR");
        } else if ("verification".equals(state)) {
            clearQr("Verification required - open Official Page");
        } else {
            clearQr("QR unavailable - refresh or open Official Page");
        }
    }

    /** 打开原网页，供扫码、验证码、账号退出或手动切换页面。 */
    public void showBrowser() {
        if (sessionBusy) return;
        if (!ensureBrowser()) return;
        if (qrWindow != null) qrWindow.setVisible(false);
        clearQr("");
        window.setVisible(true);
        window.toFront();
        if (!expectedUrl.equals(browser.getCefBrowser().getURL())) navigate(PageSnapshot.allowed(expectedUrl) ? expectedUrl : HOME);
        startInspection(300_000);
    }

    /** 返回登录账号的官方首页。 */
    public void home() { savedIndex = 0; savedList = null; navigate(HOME); }

    /** 进入当前帖子，正文段落和评论共用逐条阅读。 */
    public void open() {
        if (page.items.isEmpty()) return;
        PageSnapshot.Item item = page.items.get(index);
        if ("Post".equals(item.kind)) { listUrl = page.url; savedIndex = index; savedList = page; navigate(item.url); }
    }

    /** 直接恢复原列表与位置，取消旧提取回调，不额外刷新推荐流。 */
    public void back() {
        if (!insidePost()) return;
        if (savedList == null) { navigate(listUrl); return; }
        generation++;
        timer.stop();
        page = savedList;
        expectedUrl = page.url;
        index = Math.min(savedIndex, Math.max(0, page.items.size() - 1));
        offset = 0;
        status = "";
        changed();
    }

    /** 手动刷新当前页面，限制连续刷新频率。 */
    public void refresh() {
        if (sessionBusy || loginChecking) return;
        if (!ensureBrowser()) return;
        if (System.currentTimeMillis() - lastNavigation < 2000) { status = "Please wait 2 seconds before navigating again"; changed(); return; }
        PageSnapshot before = page;
        String target = PageSnapshot.allowed(expectedUrl) ? expectedUrl : HOME;
        boolean sameUrl = target.equals(browser.getCefBrowser().getURL());
        prepareNavigation(target);
        refreshBaseline = before;
        refreshResult = "Refreshing...";
        if (sameUrl) browser.getCefBrowser().reloadIgnoreCache(); else browser.loadURL(target);
    }

    /** 选择官方首页已展示的社区，不猜测社区编号。 */
    public void chooseCommunity() {
        if (sessionBusy || loginChecking) return;
        if (System.currentTimeMillis() - lastNavigation < 2000) { status = "Please wait 2 seconds before choosing a community"; changed(); return; }
        if (communities.isEmpty() || !HOME.equals(page.url) || browser == null || !HOME.equals(browser.getCefBrowser().getURL())) { pendingCommunityDialog = true; home(); status = "Loading communities..."; changed(); return; }
        String choice = Messages.showEditableChooseDialog(UiLanguage.text("Choose a community shown on your Home page:"), UiLanguage.text("Heybox Community"), Messages.getQuestionIcon(), communities.toArray(String[]::new), communities.getFirst(), null);
        if (choice == null || !communities.contains(choice)) return;
        savedIndex = 0;
        savedList = null;
        String code = "(() => { const label = " + new Gson().toJson(choice) + "; const button = Array.from(document.querySelectorAll('main button')).find(e => e.innerText.trim() === label); if (button) button.click(); })();";
        browser.getCefBrowser().executeJavaScript(code, HOME, 0);
        expectedUrl = "";
        page = new PageSnapshot();
        status = "Loading community...";
        generation++;
        startInspection(30_000);
        changed();
    }

    /** 校验并加载地址，切换页面时使旧回调失效。 */
    private void navigate(String url) {
        if (sessionBusy || loginChecking) return;
        if (!PageSnapshot.allowed(url) || !ensureBrowser()) return;
        if (System.currentTimeMillis() - lastNavigation < 2000) { status = "Please wait 2 seconds before navigating again"; changed(); return; }
        prepareNavigation(url);
        browser.loadURL(url);
    }

    /** 初始化新导航的状态，在文档加载完成前拒绝旧回调。 */
    private void prepareNavigation(String url) {
        refreshBaseline = null;
        refreshResult = "";
        documentReady = false;
        lastNavigation = System.currentTimeMillis();
        generation++;
        expectedUrl = url;
        page = new PageSnapshot();
        index = 0;
        offset = 0;
        status = "Loading official page...";
        startInspection(30_000);
        changed();
    }

    /** 启动限时 DOM 检查，不重新请求网页。 */
    private void startInspection(long duration) { deadline = System.currentTimeMillis() + duration; lastSnapshot = ""; stableSamples = 0; timer.start(); }

    /** 在官方页面提取 DOM，桥接只接收数据，不接受执行指令。 */
    private void inspect() {
        if (disposed || browser == null || System.currentTimeMillis() > deadline) {
            timer.stop();
            pendingCommunityDialog = false;
            if (logoutRequested) { logoutRequested = false; status = "Unable to confirm sign out; open Official Page"; changed(); return; }
            if (loginChecking) { loginChecking = false; loginRequested = false; status = "Sign-in check timed out; open Official Page"; changed(); return; }
            if (refreshBaseline != null) { page = refreshBaseline; refreshBaseline = null; index = 0; refreshResult = "Refresh timed out; showing previous content"; status = refreshResult; changed(); return; }
            if (loginRequested) { loginRequested = false; clearQr("Sign-in timed out - click Refresh QR"); }
            if (!disposed && page.items.isEmpty()) { status = "No readable content; open Official Page to check login or restrictions"; changed(); }
            return;
        }
        String current = browser.getCefBrowser().getURL();
        if (!PageSnapshot.allowed(current) || !documentReady) return;
        if (window.isVisible() && !current.equals(expectedUrl)) { expectedUrl = current; generation++; }
        String prefix = "(() => { const result = " + script + "; if (!result) return; const payload = JSON.stringify({generation:" + generation + ", page:result}); ";
        browser.getCefBrowser().executeJavaScript(prefix + query.inject("payload") + " })();", current, 0);
        if (logoutRequested && !logoutClicked) {
            if (!logoutMenuOpened) {
                logoutMenuOpened = true;
                browser.getCefBrowser().executeJavaScript("document.querySelector('nav button img[alt$=\"头像\"]')?.closest('button')?.click();", current, 0);
            } else {
                String logoutCode = "(() => { const button = Array.from(document.querySelectorAll('[role=\"menu\"] button[role=\"menuitem\"]')).find(e => e.innerText.trim() === '退出登录'); if (!button) return; button.click(); const payload = JSON.stringify({generation:" + generation + ", logoutClick:true}); ";
                browser.getCefBrowser().executeJavaScript(logoutCode + query.inject("payload") + " })();", current, 0);
            }
        }
        if (loginRequested) {
            if (!loginChecking) browser.getCefBrowser().executeJavaScript("(() => { if (document.querySelector('nav img[alt$=\"头像\"]')) return; const button = Array.from(document.querySelectorAll('nav button')).find(e => e.innerText.trim() === '登录'); if (button && !document.body.innerText.includes('扫码快捷登录')) button.click(); })();", current, 0);
            String qrCode = "(() => { const result = " + qrScript + "; const payload = JSON.stringify({generation:" + generation + ", url:location.href, qr:result}); ";
            browser.getCefBrowser().executeJavaScript(qrCode + query.inject("payload") + " })();", current, 0);
        }
    }

    /** 接收已校验的阅读内容，刷新时不恢复旧位置，成功后直接显示第一条。 */
    private void acceptReadingItems(PageSnapshot incoming, String fingerprint) {
        boolean first = page.items.isEmpty();
        page = incoming;
        index = Math.min(first && refreshBaseline == null && listUrl.equals(page.url) ? savedIndex : index, page.items.size() - 1);
        status = "";
        stableSamples = fingerprint.equals(lastSnapshot) ? stableSamples + 1 : 0;
        lastSnapshot = fingerprint;
        if (refreshBaseline != null && stableSamples >= 3 && System.currentTimeMillis() - loadCompletedAt >= 8000) {
            refreshResult = ReadingText.sameItems(refreshBaseline.items, incoming.items) ? "Refreshed; page content unchanged" : "Refreshed; page content updated";
            refreshBaseline = null;
            savedIndex = 0;
            index = 0;
            status = "";
            offset = 0;
        }
    }

    /** 校验当前代次和地址后更新阅读状态，不记录账号数据到日志。 */
    private void accept(String payload) {
        if (disposed || browser == null) return;
        try {
            var envelope = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
            if (envelope.get("generation").getAsLong() != generation) return;
            if (envelope.has("logoutClick")) { logoutClicked = true; return; }
            if (!documentReady) return;
            if (envelope.has("qr")) {
                if (HOME.equals(envelope.get("url").getAsString()) && HOME.equals(browser.getCefBrowser().getURL())) acceptQr(envelope.getAsJsonObject("qr"));
                return;
            }
            PageSnapshot incoming = PageSnapshot.parse(envelope.get("page").toString());
            if (!incoming.url.equals(browser.getCefBrowser().getURL()) || (!expectedUrl.isBlank() && !expectedUrl.equals(incoming.url)) || (expectedUrl.isBlank() && HOME.equals(incoming.url))) return;
            expectedUrl = incoming.url;
            if (updateAuthentication(incoming)) return;
            if (logoutRequested) return;
            if (!incoming.communities.isEmpty()) communities = incoming.communities;
            checkLogin(incoming);
            if (loginChecking) { status = "Checking sign-in status..."; changed(); return; }
            if (!incoming.items.isEmpty()) {
                acceptReadingItems(incoming, envelope.get("page").toString());
                if (stableSamples >= 3) saveStableSession();
                if (!window.isVisible() && !loginRequested && !logoutRequested && refreshBaseline == null && stableSamples >= 3) timer.stop();
            }
            if (pendingCommunityDialog && HOME.equals(incoming.url) && !incoming.communities.isEmpty()) {
                pendingCommunityDialog = false;
                page = incoming;
                ApplicationManager.getApplication().invokeLater(() -> { if (!disposed && HOME.equals(expectedUrl)) chooseCommunity(); });
            }
            changed();
        } catch (RuntimeException ignored) { clearQr("Unable to display QR - open Official Page"); status = "Page format changed; open Official Page"; changed(); }
    }

    /** 释放窗口、桥接和浏览器，不删除任何浏览器缓存。 */
    @Override
    public void dispose() {
        disposed = true;
        sessionWorker.shutdown();
        timer.stop();
        listeners.clear();
        clearQr("");
        if (qrWindow != null) qrWindow.dispose();
        if (window != null) window.dispose();
        if (query != null) Disposer.dispose(query);
        if (browser != null) Disposer.dispose(browser);
    }
}
