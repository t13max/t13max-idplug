# 微信插件

面向 IntelliJ IDEA 2026.1（261）的微信工具窗口。右侧 WeChat 与 Maven 同级，支持 `Alt+W` 显示或隐藏。

## 使用

1. 在 IDEA 的 Settings → Plugins → 齿轮 → Install Plugin from Disk 中选择 `build/distributions/t13max-idplug-wechat-1.1.0.zip`，按 IDE 提示重启。
2. 打开右侧 WeChat，点击“扫码登录”，使用手机微信扫描窗口内二维码并确认。
3. 单击联系人进入聊天；名称优先显示备注，其次昵称。底部输入文本，回车或点击“发送”；“返回”回到联系人列表。
4. UTF-8 编码控件附近的微信图标在有未读消息时显示红点，图标和悬浮提示均不包含联系人或消息正文。点击图标打开窗口。
5. 关闭 IDE 时保留会话；下次打开项目后后台尝试恢复。点击“退出”会清除保存的登录凭据。

## 登录与历史

- 沿用原 `t13max-wxbot` 的微信网页版协议及登录客户端兼容参数，不依赖机器人管理器、全局事件总线或私有 Maven 包。
- 账号必须获准使用微信网页版。微信撤销、过期或拒绝会话时需要重新扫码，不能保证永久免扫码。
- 登录票据和 Cookie 存入 IntelliJ PasswordSafe，不进入项目文件或聊天历史。若 IDE 密码存储设置为“仅内存”，窗口会提示无法跨重启保存登录；需要在 IDE 的密码设置中启用持久存储。
- 已接收和发送的消息、联系人及未读计数保存到 IDE 配置目录的 `t13max-wechat/history.json`，按账号隔离。历史文件是本地明文 JSON，登录凭据不在其中。
- 历史只包含本插件收到的消息；微信网页版不提供手机全部历史的任意同步。图片、语音、视频和文件以类型提示显示，需在手机上查看；本版本发送文本。
- 同一有效会话重启后保留联系人标识和聊天历史。重新扫码后微信可能更换联系人标识，旧历史仍可查看，但不会仅凭昵称把旧联系人与新联系人合并，旧标识不可发送。
- 多项目共享一个登录和接收服务。只在当前活动窗口的可见聊天页清除该会话未读，隐藏窗口或只打开联系人列表不会清除全部未读。
- 网络发送失败或响应丢失会保留输入内容；响应丢失时请先核对聊天记录再重试，避免重复发送。

## 构建

需要 JDK 21 和 Gradle Wrapper 9.1.0。可从仓库根目录执行：

```powershell
./gradlew.bat :t13max-idplug-wechat:test :t13max-idplug-wechat:buildPlugin
```

也可独立打开当前目录为 Gradle 项目，执行 `./gradlew.bat test buildPlugin`。

复用已安装的 IDEA 2026.1 时传入本机安装路径；使用 JDK/JBR 25 编译器时传入 `-PbuildJavaVersion=25`，输出仍为 Java 21 字节码。例如在当前模块目录执行：

```powershell
$env:JAVA_HOME = 'D:/Program Files/JetBrains/IntelliJ IDEA 2026.1/jbr'
./gradlew.bat '-PlocalIdePath=D:/Program Files/JetBrains/IntelliJ IDEA 2026.1' -PbuildJavaVersion=25 test buildPlugin verifyPluginProjectConfiguration verifyPlugin
```

`verifyPlugin` 使用 JetBrains 官方 Plugin Verifier 检查当前目标 IDE。首次构建需要下载公开依赖和验证器。

根 Wrapper 和 replace 模块构建脚本同步迁移到新版 Gradle 插件，以便根项目能配置；replace 的业务代码和原 2024.2.1 目标没有改动。

## 验证范围

自动化测试覆盖二维码登录流程、有效会话恢复、失效会话、Cookie 到期、私聊和群聊解析、手机发出消息归属、发送失败、同步分页续传、历史恢复、未读计数、去重和账号隔离。

真实账号仍需在安装后验证扫码授权、双向消息收发、重启恢复和状态栏视觉位置。不同账号的网页版登录资格和会话期限由微信服务端决定。
