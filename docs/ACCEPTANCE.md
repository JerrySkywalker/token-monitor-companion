# MVP 本地验收记录

2026-11-22 在 SKYFORGE-01 执行。构建使用 JDK 17、Gradle Wrapper 8.14.5、Android SDK Platform 36、Build Tools 36.0.0 和 Node.js。模拟器为 Android Emulator 37.4.1，使用 WHPX，手机和手表镜像均为 API 35。所有测试及 APK 构建在本机完成。

| 验收项 | 结果 |
| --- | --- |
| 网关合同测试 | 6 项通过，覆盖配对码过期与重放、设备权限、配置版本、暂停、撤销和缓存降级 |
| Android 单元测试和 Lint | Kotlin 3 项通过，手机和手表 Lint 无错误 |
| Android 构建 | 手机和手表的 Debug 与未签名 Release APK 均生成，Debug APK 使用相同应用 ID 和签名 |
| 容器 | Linux 镜像启动后，HTTP 健康检查、发码、手机入网及返回用量通过 |
| 手机扫码 | AVD 的模拟相机实际读取配对 QR，显示 Codex 62% 和今日 1,264,020 tokens |
| 手表独立直连 | Wear AVD 手输一次性码后显示 Codex 41% 和 OpenRouter USD 12.34，关闭手机 AVD 后表上刷新取得较新的快照 |
| 手机控制直连表 | 手机暂停后收到了 v1 已暂停回执。恢复同步并置顶 OpenRouter 后，手表调整显示顺序，手机收到了 v3 已同步回执。手机发起刷新后收到了 v4 已同步回执 |
| 断网与恢复 | Wear AVD 开启飞行模式后保留上次余额并显示连接失败。恢复网络并重新打开应用后刷新成功 |
| Wear OS Data Layer | 手机 AVD 已从 Google Play 安装 Google Pixel Watch 5.1。系统配对停在 Wear OS 服务条款，待在手机 AVD 确认后验证发现、转发、回执和模式切换 |
| 真机 | eSIM 和厂商后台运行待在后续真机环境测量 |

各客户端读到的均是本地网关的合成数据。手机扫码通过临时测试 AVD 的模拟相机完成。两种 Android 构建和网关测试可通过 scripts/local-ci.ps1 重跑。模拟器截图、UI 断言与 CI 日志保存在本机 .local/evidence。操作步骤见 [模拟器验收](AVD.md)。[Android Studio 的 Wear 配对要求](https://developer.android.com/training/wearables/get-started/connect-phone)
