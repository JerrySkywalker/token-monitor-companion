# 本地模拟器验收

## 准备

1. 在 Android Studio Device Manager 创建 Android 手机 AVD 和 Wear OS 圆形 AVD。示例使用 Android 15 和 Wear OS 5.1。
2. 运行 `./scripts/local-ci.ps1`。使用 Android Studio Run 或 `adb install -r` 安装相应模块的 Debug APK。两端须由同一证书签名。
3. 准备可从模拟器访问的 HTTPS Token Monitor Hub 或兼容 `GET /api/stats` 和 `GET /api/history` 的测试实例。使用系统配对相关场景时，在 Android Studio 通过 [Pair Wearable](https://developer.android.com/training/wearables/get-started/connect-phone) 完成系统配对。

## 验收清单

| 场景 | 核对点 |
| --- | --- |
| 手机直连 | 手机填入测试 Hub 地址与 Secret，连接详情显示已连接。重启后配置保留。 |
| 扫码导入 | 手机和手表使用可互访的 Wi‑Fi 网络。手表生成配对码，手机打开扫码相机并确认发送。手表成功取数后，手机收到确认。 |
| 手机推送 | 手机在 Wear OS 页面发现并绑定手表，选择「手机推送」。手表显示手机使用的 Hub 数据，手机收到「已同步」回执。 |
| 远程配置与模式切换 | 给手表下发另一套测试 Hub 配置。切换到独立直连，表上用量来自手表配置的 Hub。切回手机推送，表上用量切回手机使用的 Hub。 |
| 手表独立刷新 | 让手表使用自己的网络，关闭手表蓝牙并停止手机 App。手动刷新后「本机取数」推进。真机额外验收电脑完全关机后刷新。 |
| 断网恢复 | 独立直连模式下断开手表网络，确认缓存仍可查看。恢复网络后重新刷新。 |
| 总览与桌面小工具 | 在设置里修改卡片顺序与可见性并重启 App。添加 Glance 小工具，核对卡片和跳转目标。 |
| 额度与 Wear OS Tile | 验证标准与紧凑布局。手表横滑查看额度及趋势，添加系统 Tile 后点击回到看板。 |

对真实 Hub 截取的画面、日志和测试数据应保存在本地。报告面向外部读者时可使用合成数据。
