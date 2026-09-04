package com.t13max.idplug.heybox;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import java.util.Map;

/** 保存界面语言，中文默认，不修改网页原文和类型标记。 */
@Service(Service.Level.APP)
@State(name = "HeyboxUiLanguage", storages = @Storage("heybox-ui.xml"))
public final class UiLanguage implements PersistentStateComponent<UiLanguage.Data> {
    private Data data = new Data();
    private static final Map<String, String> WORDS = Map.ofEntries(
        Map.entry("Click to sign in", "点击扫码登录"),
        Map.entry("Restoring session...", "正在恢复登录…"),
        Map.entry("Checking sign-in status...", "正在检查登录状态…"),
        Map.entry("Sign-in check timed out; open Official Page", "登录状态检查超时，请打开官方页面"),
        Map.entry("Session restore failed; sign in again", "登录恢复失败，请重新扫码"),
        Map.entry("Session could not be saved; restart may require sign in", "登录态保存失败，重启后可能需要重新扫码"),
        Map.entry("Unable to delete saved session; check PasswordSafe", "保存的登录态删除失败，请检查 IDEA 密码安全存储"),
        Map.entry("Scan QR to sign in", "扫码登录"),
        Map.entry("Sign out", "登出"),
        Map.entry("Signing out...", "正在登出…"),
        Map.entry("Signed out; click to sign in", "已登出，点击扫码登录"),
        Map.entry("Unable to confirm sign out; open Official Page", "未能确认登出，请打开官方页面处理"),
        Map.entry("Official Page", "官方页面"),
        Map.entry("Home Feed", "首页推荐"),
        Map.entry("Choose Community...", "选择专区…"),
        Map.entry("Refresh List", "刷新列表"),
        Map.entry("Hide", "隐藏"),
        Map.entry("Heybox Line Reader", "小黑盒单行阅读器"),
        Map.entry("Heybox - Official Sign In / Reader", "小黑盒－官方登录与阅读"),
        Map.entry("  Sign in on the official page. Close this window to keep reading in the status bar.", "  请在官方页面登录，关闭窗口后可继续在状态栏阅读。"),
        Map.entry("Heybox - Scan QR", "小黑盒－扫码登录"),
        Map.entry("Loading official QR code...", "正在加载官方二维码…"),
        Map.entry("Refresh QR", "刷新二维码"),
        Map.entry("Sign-in closed", "已关闭扫码登录"),
        Map.entry("Scan with the Heybox app", "请使用小黑盒 App 扫码"),
        Map.entry("QR expired - click Refresh QR", "二维码已过期，请刷新"),
        Map.entry("Verification required - open Official Page", "需要额外验证，请打开官方页面"),
        Map.entry("QR unavailable - refresh or open Official Page", "二维码不可用，请刷新或打开官方页面"),
        Map.entry("Sign-in timed out - click Refresh QR", "登录超时，请刷新二维码"),
        Map.entry("Unable to display QR - open Official Page", "二维码显示失败，请打开官方页面"),
        Map.entry("Signed in; loading your feed...", "登录成功，正在加载推荐…"),
        Map.entry("JCEF unavailable; use the bundled JetBrains Runtime", "内置浏览器不可用，请使用 IDEA 自带运行时"),
        Map.entry("Unable to initialize Heybox browser", "小黑盒浏览器初始化失败"),
        Map.entry("Please wait 2 seconds before navigating again", "请等待两秒后再操作"),
        Map.entry("Please wait 2 seconds before choosing a community", "请等待两秒后再选择专区"),
        Map.entry("Loading communities...", "正在加载专区…"),
        Map.entry("Loading community...", "正在加载专区内容…"),
        Map.entry("Loading official page...", "正在加载官方页面…"),
        Map.entry("Choose a community shown on your Home page:", "请选择首页展示的专区："),
        Map.entry("Heybox Community", "小黑盒专区"),
        Map.entry("No readable content; open Official Page to check login or restrictions", "没有可读内容，请打开官方页面检查登录或访问限制"),
        Map.entry("Page format changed; open Official Page", "页面解析失败，请打开官方页面检查"),
        Map.entry("Refreshing...", "正在刷新…"),
        Map.entry("Refreshed; page content unchanged", "已刷新，页面内容未变化"),
        Map.entry("Refreshed; page content updated", "已刷新，页面内容已更新"),
        Map.entry("Refresh timed out; showing previous content", "刷新超时，保留之前的内容"),
        Map.entry("Show or Hide", "显示或隐藏"),
        Map.entry("Next Item", "下一条"),
        Map.entry("Previous Item", "上一条"),
        Map.entry("Enter or Exit Post", "进入或退出帖子"),
        Map.entry("Back to List", "返回列表")
    );

    /** 获取应用级语言设置。 */
    public static UiLanguage get() { return ApplicationManager.getApplication().getService(UiLanguage.class); }

    /** 根据当前设置翻译已知界面文字，未知文字保持原样。 */
    public static String text(String value) { return translate(value, get().data.english); }

    /** 纯函数翻译，同时支持已显示的中文在切换后恢复英文。 */
    public static String translate(String value, boolean english) {
        if (value == null) return "";
        String key = WORDS.entrySet().stream().filter(entry -> entry.getValue().equals(value)).map(Map.Entry::getKey).findFirst().orElse(value);
        return english ? key : WORDS.getOrDefault(key, value);
    }

    /** 查询当前是否使用英文。 */
    public boolean english() { return data.english; }

    /** 保存用户选择的语言。 */
    public void setEnglish(boolean value) { data.english = value; }

    /** 返回持久化的语言设置。 */
    @Override
    public @NotNull Data getState() { return data; }

    /** 加载语言设置。 */
    @Override
    public void loadState(@NotNull Data state) { data = state; }

    /** 保存不包含任何账号数据的界面偏好。 */
    public static final class Data {
        public boolean english;
        /** 创建默认中文设置。 */
        public Data() { }
    }
}
