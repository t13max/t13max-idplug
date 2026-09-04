# T13max Reddit Line Reader

适用于 IntelliJ IDEA 2026.1 的单行 Reddit 状态栏阅读器。

## 默认使用：免登录公开只读模式

从 `1.0.2` 开始，默认使用 `Public RSS (no sign-in)`，升级旧版本时也默认启用。无需申请 Client ID，也无需登录 Reddit 或 Google。

1. 从磁盘安装插件 ZIP 后重启 IDEA。
2. 插件尝试读取上次选择的专区；首次默认 `r/programming`。
3. 点击底部状态栏文字，选择 `Choose Community...`，输入例如 `java` 或 `r/java`。
4. 使用 `Hot`、`New`、`Top` 切换排序，使用快捷键进入帖子评论；评论刷新失败时可通过 `Back to Posts` 返回。

公开模式只读取 Reddit 提供的 Atom/RSS 内容，不爬取登录 Cookie、不借用其他应用的 Client ID，也不绕过访问限制。它不能访问账号订阅、私有专区，不能点赞、收藏或回复。评论 RSS 可能只暴露部分评论、不保留完整楼层关系，空订阅源不代表网站中没有评论。遇到限制或 RSS 不可用时会显示英文提示。

### 缓存与请求限制

- 成功的 RSS 在本地内存缓存 5 分钟，最多保留 32 个订阅源，当前 IDEA 进程的多个项目共用。重启 IDEA 后缓存清空。
- 同一订阅源的并发请求合并；点击 `Refresh` 在 5 分钟内会复用缓存，不强制请求网站。
- 两次不同订阅源的网络请求至少间隔 2 秒；过快进入新页面会提示稍后重试。
- HTTP 429 后至少冷却 5 分钟，并参考 `Retry-After`（最长 24 小时）。不会自动循环重试或切换域名绕过限制。
- 其他失败后至少间隔 30 秒再请求；存在过期缓存时继续显示，并标记 `Cached RSS`。
- 限流或网络错误会占用状态栏一行显示，不再被旧帖子遮住；可按上一条或下一条继续阅读已加载内容。
- RSS 可用性取决于 Reddit 和当前网络。本机曾取得有效 HTTP 200，也观察到 HTTP 429；不能保证持续可用。

## 可选高级设置：OAuth 模式

只在你已经有可用的 Reddit API Client ID 时使用。Reddit 可能要求先审核 API 访问申请，个人使用并不保证能直接创建应用。

1. 在 Reddit 的应用设置中创建 `installed app` 类型应用。
2. 将 Redirect URI 填写为 `http://127.0.0.1:18931/reddit-callback`。
3. 点击状态栏，进入 `Optional OAuth...` → `Set Client ID...`。
4. 选择 `Optional OAuth...` → `Sign In to Reddit`，插件会打开系统默认浏览器，可在 Reddit 页面使用 Google 登录。
5. 成功授权后切换至 OAuth 模式，专区选择支持读取账号订阅。勾选 `Public RSS (no sign-in)` 可随时返回公开模式，不会删除已保存令牌。

刷新令牌保存在 IntelliJ Platform 的 PasswordSafe 中，插件不接触 Reddit 或 Google 密码。能否跨重启保留取决于 IDEA 的 PasswordSafe 配置；仅内存存储模式不会保留。选择 `Sign Out and Use Public RSS` 会删除令牌并回到公开模式。

## 操作

- 鼠标滚轮：横向滚动当前一行内容。
- `Ctrl+Alt+Shift+J`：下一条。
- `Ctrl+Alt+Shift+K`：上一条。
- `Ctrl+Alt+Shift+Enter`：进入帖子评论。
- `Ctrl+Alt+Shift+Backspace`：返回帖子列表。
- `Ctrl+Alt+Shift+S`：选择专区。
- `Ctrl+Alt+Shift+U`：刷新。
- `Ctrl+Alt+Shift+R`：显示或隐藏。

所有快捷键均可在 IDEA Keymap 设置中修改。

## 使用 Shell 脚本打包

打包脚本位于插件模块根目录：`build-plugin.sh`。脚本面向 Windows Git Bash，会使用 IDEA 2026.1 自带的 JBR，不支持直接在 WSL 中调用 Windows 版 IDEA/JBR。

先在 Git Bash 中进入插件目录：

```bash
cd /e/T13maxProjects/t13max-idplug/t13max-idplug-reddit
```

IDEA 安装在默认目录 `D:\Program Files\JetBrains\IntelliJ IDEA 2026.1` 时，直接执行：

```bash
bash ./build-plugin.sh
```

IDEA 安装在其他位置时，将目录作为第一个参数传入：

```bash
bash ./build-plugin.sh "/d/Program Files/JetBrains/IntelliJ IDEA 2026.1"
```

也可以使用环境变量指定 IDEA 路径：

```bash
IDEA_PATH='D:\Program Files\JetBrains\IntelliJ IDEA 2026.1' bash ./build-plugin.sh
```

重复开发时可跳过 `clean`，缩短构建时间：

```bash
bash ./build-plugin.sh --skip-clean
```

脚本执行 `test`、`buildPlugin`、`verifyPluginProjectConfiguration` 和 `verifyPluginStructure`，成功后输出安装包完整路径、文件大小和 SHA-256。自动测试使用离线 Atom 样本及模拟 HTTP 响应，不访问 Reddit、浏览器或账号。安装包位于：

```text
build/distributions/t13max-idplug-reddit-1.0.2.zip
```

Gradle Wrapper、插件依赖和构建工具保存在 `GRADLE_USER_HOME` 指定的公共目录；未配置时使用用户目录的 `.gradle`。本机当前使用 `E:\GradleUserHome`，所有项目和后续构建都会复用该目录；脚本不会清理公共缓存。`clean` 只清理模块构建产物，不清理 Gradle 公共缓存。首次使用新版本依赖时需要下载，后续复用已有内容。

### 常见错误

- `IDEA 目录不存在`：检查传入的路径，Git Bash 路径可写成 `/d/Program Files/...`，也可以传入 Windows 路径。
- `未找到 IDEA 自带 JBR`：确认所选目录是完整的 IntelliJ IDEA 安装目录。
- 依赖下载失败：检查 Maven Central、Gradle Plugin Portal 和项目中配置的 Maven 镜像是否能够访问，然后重新执行脚本。
- 在 WSL 中运行失败：请改用 Windows Git Bash；WSL 不能直接把 Windows 版 JBR 当作 Linux Java 运行时。
