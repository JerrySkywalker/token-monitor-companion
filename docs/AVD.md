# 本地模拟器验收

## 准备

1. 用 Android Studio Device Manager 创建 Android 15 Google Play 手机 AVD 和 Wear OS 5.1 圆形 AVD，或使用相同镜像的现有 AVD。安装 SDK Platform 36、Build Tools 36.0.0 和 Android Platform Tools。
2. 在 Android Studio 里使用 [Pair Wearable](https://developer.android.com/training/wearables/get-started/connect-phone) 为两个 AVD 建立系统配对。使用受支持的手机伴侣 App。若只核对手机普通读数和手表独立直连，可分别启动两台设备。
3. 执行 scripts/local-ci.ps1 运行 Node 合同测试、Kotlin 单元测试、Lint 和两个 APK 构建。准备好两个 AVD 的 ADB 序列号。

在仓库根目录开一个 PowerShell 终端启动本地合成数据。HTTP 限于开发构建中的模拟器宿主地址。

```powershell
$env:COMPANION_FIXTURE = '1'
$env:COMPANION_ALLOW_INSECURE = '1'
$env:PUBLIC_URL = 'http://10.0.2.2:8787'
$env:COMPANION_DB = Join-Path $PWD '.local/avd.sqlite'
node gateway/src/server.mjs
```

另一个 PowerShell 终端使用同一数据库发码。

```powershell
$env:COMPANION_ALLOW_INSECURE = '1'
$env:PUBLIC_URL = 'http://10.0.2.2:8787'
$env:COMPANION_DB = Join-Path $PWD '.local/avd.sqlite'
node gateway/src/cli.mjs issue phone
node gateway/src/cli.mjs issue wear
```

通过 Android Studio Run 或 adb install -r 安装对应 debug APK。adb devices -l 可查看两个 AVD 的序列号。

## 验收清单

| 场景 | 操作与证据 |
| --- | --- |
| 手机入网 | 扫描终端 QR 或手输地址和码。看到 Codex 62% / 41%、OpenRouter USD 12.34、今日 1,264,020 tokens。关闭重启 App 后仍有授权和快照 |
| 手表备用入网 | 用独立 wear 码，在 Wear AVD 输入服务器和代码。看到「独立直连」和窗口最紧的 Codex 额度。关闭手机模拟器后在表上刷新，快照时间更新 |
| 手机接力 | 重置测试表的应用绑定，从系统已配对的手机内发现并绑定。待手机显示已同步，手表显示手机同步和同一份配额 |
| 手机调整表状态 | 从手机选择置顶额度，暂停、恢复、请求刷新。检查手表 UI 和手机的版本回执递增 |
| 模式切换 | 手机同步切到独立直连，再切回去。检查 UI、回执、暂停时服务端拒绝直连读数 |
| 失败恢复 | 关闭测试网关或关闭网络，看到上次数据和错误。恢复网络、执行刷新并观察数据恢复 |
| 撤销 | 从手机移除手表后，旧直连授权失效。表上原有快照应在收到解绑时清除 |

建议记录每台设备的 Android 版本、ADB 序列号、对应截图和通过项目。APK 的本地 CI 通过与端到端通过分别记录。真机测量 eSIM、国行手机后台调度与实际刷新间隔。
