# Token Monitor Companion

在 Android 手机和 Wear OS 手表上查看自托管 Token Monitor 的账户额度、余额和用量。手机可以设置每块手表的同步方式、置顶额度和暂停状态，并查看手表回执。

| 组件 | 职责 |
| --- | --- |
| Token Monitor Agent + Hub | 继续在电脑采集，在 Hub 汇总 |
| Companion Gateway | 读取 Hub 的聚合结果，签发设备授权，保存手表配置 |
| Android | 扫码连接网关，展示用量和管理手表 |
| Wear OS | 接收手机快照，或独立访问网关 |

手机同步走 Hub → Companion Gateway → 手机 → Wear OS Data Layer → 手表。独立直连走 Hub → Companion Gateway → 手表。两种方式共用服务端保存的手表配置。

网关从 Hub 读取 GET /api/stats，向客户端提供今天和本月的 token、已定价成本、额度窗与充值余额。每个 provider 的可用指标取决于上游配置。上游未报告的数值显示为未知。余额沿用原币种，订阅额度按各时间窗显示。[Token Monitor Hub 接口](https://github.com/Javis603/token-monitor/blob/v0.95.1/docs/API.md#get-apistats)

## 自托管

1. 启用自己的 Token Monitor Hub，确认已有电脑通过正常的 Token Monitor 同步向它上传使用量。
2. 复制 .env.example 为 .env。设置 Hub origin（HUB_URL）、Hub 密钥文件（HUB_SECRET_PATH）和设备能访问的网关 origin（PUBLIC_URL）。例如 https://usage.example.net。密钥文件只写密钥一行，应能被容器的 node 用户读取。
3. 运行以下命令。反向代理将公开的 HTTPS 域名转发到 127.0.0.1:8787。网关的持久 SQLite 数据在 companion-data 卷里。

```sh
docker compose up -d --build
docker compose ps
curl --fail https://usage.example.net/healthz
docker compose exec gateway node src/cli.mjs issue phone
```

4. 手机打开 Token Monitor，点「扫描配对二维码」。也可输入终端显示的服务器地址和一次性码。配对码五分钟有效且只能用一次。
5. 在 Android 系统中配对手表，安装同一签名的手机和手表 APK，并在表上打开应用。手机的「手表」页查找并绑定。网关安装是单用户实例，所有已授权的手机都能管理这个实例上的手表。

网关以 HTTPS 访问 Hub。单机内网部署如需接入现有 HTTP Hub，可显式给网关设置 HUB_ALLOW_INSECURE=1，并保证该链路在可信网络内。移动端 release 需要 HTTPS。Hub 密钥保留在网关，手机和手表用不同作用域的随机凭据。网关输出不包括上游会话、项目、日志和设备诊断。

## 手表同步

**手机同步。** 手机从网关取数，向系统已配对的手表发送快照。手表不需要网关授权。打开手表上的应用后，在手机中绑定即可。手表记录最后一份快照并标注新鲜度。

**独立直连。** 手机将该表的模式设为独立直连，并尝试通过 Wear OS Data Layer 发送短期授权码。自动授权未送达时，在手表输入手机显示的地址和代码。手机不可用时，也可让服务器管理员运行 docker compose exec gateway node src/cli.mjs issue wear，从手表完成独立入网。手表用自身可用网络独立取数，包括 Wi-Fi 和系统提供的 LTE 网络。实际路由由 Wear OS 选择。

手机保存设置后，手表回执会显示版本和最后收到的数据时间。切换为手机同步需要先绑定手机。暂停保留最后快照，继续读取管理配置以允许远程恢复。请求刷新在对应链路可用时生效。手机与手表后台检查由系统调度，页面上的刷新按钮会主动拉取。移除手表时撤销其网关凭据。管理服务器设备也可以运行以下命令。

```sh
docker compose exec gateway node src/cli.mjs devices
docker compose exec gateway node src/cli.mjs revoke DEVICE_ID
```

## 本地开发

工具为 JDK 17、Node.js 24+、Android SDK Platform 36、Build Tools 36.0.0、Platform Tools 和 Android Emulator。Android Studio 打开仓库根目录，Gradle JDK 选 17。手机和手表有同一个 Android application ID。编译两种正式 APK 时应使用同一应用签名。

```powershell
.\scripts\local-ci.ps1
.\scripts\local-ci.ps1 -Release
```

调试 APK 生成在 mobile/build/outputs/apk/debug 和 wear/build/outputs/apk/debug。Release 编译产物默认未签名。开发顺序在 [ROADMAP.md](ROADMAP.md)，模拟器验收步骤在 [docs/AVD.md](docs/AVD.md)，本机实测见 [验收记录](docs/ACCEPTANCE.md)。

项目采用 MIT 许可证。完成提醒由独立应用处理。
