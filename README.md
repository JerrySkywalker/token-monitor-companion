# Token Monitor Companion

在 Android 手机和 Wear OS 手表上查看 Token Monitor Hub 的用量、账户额度和余额。手机提供可自定义总览、额度页、趋势和 Glance 桌面小工具。手表提供 StopWatch 看板和系统 Tile。

## 安装

在 [GitHub Releases](https://github.com/JerrySkywalker/token-monitor-companion/releases/latest) 下载适合设备的 APK。

- **Android 11 或以上**。在手机安装 `token-monitor-companion-android-v0.2.0.apk`。
- **Wear OS**。在手表安装 `token-monitor-companion-wear-os-v0.2.0.apk`，可使用 [Wear OS 无线调试](https://developer.android.com/training/wearables/get-started/debug-wifi) 侧载。
- 两端 Release APK 使用同一个应用 ID 和发布证书。若已装开发版，Android 会拒绝用不同证书覆盖安装。卸载开发版会清除其本地设置，之后可在 Release 版重新连接 Hub。

下载的 `SHA256SUMS.txt` 可用于核对两个 APK 文件。

## 总览、趋势和排序

- 打开「设置 → 总览时间筛选」，从当日、7 天、30 天、365 天、本周、本月、本年中固定至多四项。总览的「更多时间」可临时选择其余范围。
- 总览的 Tokens 趋势和「活动」页共用所选时间范围。在活动页选择折线或 K 线，可点图表查看该采样区间或日记录的数值，也可切换到预估成本。
- 当日趋势从手机成功读取 Hub 时积累样本，显示相邻采样之间观察到的增量。Hub 的公开接口提供日历史，首次使用无法回填早先的日内曲线。长范围从 Hub 日历史绘制，缺失的日期留空。K 线从各分桶首、尾、最高和最低的已有读数生成。
- 「编辑总览」可添加、移除和拖动展示队列里的卡片。「额度与余额 → 调整顺序」可分别拖动额度、余额与支出的账户卡片。排序和固定范围会保存在本机。
- 手表额度按短时、周期和余额分区。每个区内同一工具只显示一次，再列出其不同账户和时间窗。

## 连接现有 Token Monitor Hub

在手机 App 首页填写 Hub 的 **HTTPS 地址**（例如 `https://hub.example.com`）和 **Hub Secret**，点「连接 Hub」。之后可在「设置 → Token Monitor Hub」查看连接状态、编辑配置和检查连接。手机可以独立使用。

两端直接使用 [Token Monitor Hub API](https://github.com/Javis603/token-monitor/blob/v0.95.1/docs/API.md#get-apistats)。日常使用只需现有 Hub，无需部署 Companion Server。

### 扫码导入到手表

1. 手机已连接 Hub，手机和手表连接到可相互访问的同一 Wi‑Fi。
2. 在手表 App 点「从手机导入 Hub」。已有看板时，从齿轮进入连接设置。
3. 在手机 App 打开「设置 → Wear OS → 扫描手表配对码」，扫表上的二维码，再点「发送 Hub 配置」。
4. 等手机显示「手表已验证 Hub」。手表验证配置后保存，并进入「独立直连」。

扫码仅需局域网和手机相机，即使 Google Play 服务的手表同步不可用也能导入。二维码包含临时连接信息和一次性配对密钥，有效期两分钟。Hub 地址和 Secret 经 AES-GCM 加密发送。两端使用 Android Keystore 保护已保存的 Secret。

### 手机推送

1. 先在系统中配对 Android 手机和 Wear OS 手表，在两端打开 App。
2. 在手机 App 进入「设置 → Wear OS」，找到并绑定手表。绑定后可导入当前手机的 Hub 地址和 Secret，为之后的独立直连做好准备。
3. 返回手表设置，选择「手机推送」。手机从自己的 Hub 取数，经 Wear OS Data Layer 转发用量，手表回执显示「已同步」。手机也可设置暂停、置顶额度、外圈来源和直连刷新间隔，或请求刷新。

若已通过扫码在表上启用独立直连，先在手表连接页点「允许手机发现」，再在手机绑定。持续用量推送依赖两端可用的 [Wear OS Data Layer](https://developer.android.com/training/wearables/data/overview)。

### 独立直连

- 通过扫码导入时，手表自动进入独立直连。使用系统绑定时，可在手机的「手表 Hub 直连设置」发送当前手机的 Hub 配置，或填写另一套配置，再选择「独立直连」。
- 仅安装手表 App 时，可在手表点「本机配置 Hub」，直接输入 HTTPS 地址和 Secret。
- 手表随后通过自己的 Wi‑Fi 或可用的蜂窝网络读取 Hub。点看板底部的时间手动刷新，再打开齿轮查看「本机取数」。这个时间代表手表最近一次成功读取的时间，即使采集器尚未上报新数据也会更新。

## 本地构建与验收

使用 JDK 17、Android SDK Platform 36、Build Tools 36.0.0 和 Platform Tools。从仓库根目录运行 PowerShell：

```powershell
.\scripts\local-ci.ps1
.\scripts\local-ci.ps1 -Release
```

本地 CI 运行 Kotlin 单元测试和两端 Lint，并构建 Debug APK。加 `-Release` 会同时构建两端 Release APK。Gradle 的 Release 输出默认未签名。随 GitHub Release 发布的可安装文件由同一项目证书签名。

查看 [模拟器验收方法](docs/AVD.md) 和 [验收结论](docs/ACCEPTANCE.md)。

采用 [MIT 许可证](LICENSE)。任务完成提醒由独立项目负责。仓库中的早期网关代码仅用于维护已有历史版本。
