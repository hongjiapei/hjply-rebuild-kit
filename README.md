# HJPLY 私有部署与构建包

> 给 AI 的执行指令：请完整阅读本 `README.md`、`server/README.md` 和 `android/README.md`，并严格按说明部署服务端和构建客户端；不要使用外部 ProxyIP、第三方订阅或未知 Worker 代码。

本目录包含两个独立部分：

- `server/`：Cloudflare Worker 服务端，提供 VLESS-over-WebSocket 和订阅接口。
- `android/`：单页 Android 客户端，内置订阅地址并使用精简的 libbox 内核；不包含二维码、配置编辑、应用更新、Root、Shizuku 或 Xposed 功能。

本包不包含任何可用的 UUID、订阅令牌、Cloudflare 凭据或 APK 签名私钥。每位部署者必须生成自己的配置。

## 架构与限制

客户端通过 `https://你的域名/sub?token=...` 获取一个固定 VLESS 节点。服务端运行在 Cloudflare Worker，使用 WebSocket 路径 `/ws`。

这是纯 Cloudflare Worker 转发，不使用第三方 ProxyIP、SOCKS 回退、远程订阅转换或 Telegram 上报。部分由 Cloudflare 代理的网站可能被 Cloudflare Worker Socket 关闭；没有 VPS 或可信第三方出口时无法保证这类站点可用。

Worker 会在单个运行实例内缓存 UUID 和订阅令牌 60 秒，以减少 KV 读取和连接延迟；订阅接口按来源 IP 做轻量限流。VLESS 的每个 TCP 连接都需要独立 WebSocket，因此不对 WebSocket 握手做进程内限流，防止正常浏览网页时被误判。订阅限流只能降低偶发滥用，不能代替 Cloudflare WAF 或账号级 Rate Limiting 规则。

### 默认路由

客户端默认启用规则模式：中国大陆域名和 IP 直连，其余流量走 VLESS 节点。大陆域名使用直连 DNS `223.5.5.5`，其他域名使用经节点转发的 Google DoH；DNS 只返回 IPv4 结果，避免 Android/Chromium 优先选择 Cloudflare Worker 出站不稳定的 IPv6 路径。`geosite-cn.srs` 与 `geoip-cn.srs` 规则集已随 APK 内置，首次运行仅复制到应用私有目录，不依赖 GitHub 或其他远程规则下载服务；规则更新随新的 APK 发布。它们仅是域名/IP 分类数据，不是 ProxyIP、订阅或中转服务。

### 已知访问限制

这不是具备固定独立出口 IP 的通用 VPN，而是通过 Cloudflare Worker 的 TCP Socket 转发。实测 `x.com` 和 ChatGPT 无法稳定访问；其他同样接入 Cloudflare、启用较严格反滥用策略，或不接受 Cloudflare Worker 转发流量的服务，也可能连接重置、页面无法打开或间歇性失败。

根因是流量从 Cloudflare 边缘进入 Worker 后，又访问受 Cloudflare 保护的目标站点，可能被目标站点或 Cloudflare 的安全策略识别为代理/自动化/循环代理流量并中断。更换优选 IP、订阅格式、客户端或 Worker 域名不能从根本上解决该限制。若需要稳定访问这些服务，必须使用自己控制的 VPS 或可信第三方的独立出口；本项目刻意不内置第三方 ProxyIP 或未知中转来绕过限制。

## 1. 部署服务端

前置条件：

- Cloudflare 账号，并且自己的域名已接入 Cloudflare。
- Node.js 20 或更高版本。
- Wrangler 已登录权限，执行 `npx wrangler login` 即可。

在 `server/` 中创建 KV：

```powershell
cd server
npm ci
npx wrangler kv namespace create CONFIG
```

将命令输出的 namespace ID 填入 `wrangler.vpn.toml` 的 `id`，并修改以下两项：

- `name`：自己的 Worker 名称。
- `routes.pattern`：自己的 VPN 域名，例如 `vpn.example.com`。

该域名必须属于当前 Cloudflare 账号的已托管 Zone。部署 Worker 前确保域名 DNS 由 Cloudflare 代理。

生成一个 UUIDv4 和一个长随机订阅令牌，写入 KV。不要把它们写进代码、README 或截图。

```powershell
npx wrangler kv key put "secret:UUID" "YOUR_UUID_V4" --binding CONFIG --remote
npx wrangler kv key put "secret:SUB_TOKEN" "YOUR_LONG_RANDOM_TOKEN" --binding CONFIG --remote
npm run check
npm run deploy
```

部署成功后，订阅地址为：

```text
https://vpn.example.com/sub?token=YOUR_LONG_RANDOM_TOKEN
```

可用性检查：订阅响应是 Base64 编码的 VLESS 链接；访问 `/ws` 不带 WebSocket Upgrade 时应返回 404。

## 2. 配置 Android App

前置条件：

- JDK 17。
- Android SDK、NDK `28.0.13004108`。
- 构建 release APK 需要自己的签名证书。

复制模板：

```powershell
cd android
Copy-Item subscription.properties.example subscription.properties
Copy-Item local.properties.example local.properties
```

编辑 `subscription.properties`，填写上一节得到的完整订阅地址。编辑 `local.properties`，填写 Android SDK 位置和自己的签名证书信息。

生成签名证书示例：

```powershell
keytool -genkeypair -keystore app\release.keystore -alias hjply -keyalg RSA -keysize 4096 -validity 3650
```

`version.properties` 控制 APK 的版本。每次发布新 APK 都应递增 `VERSION_CODE`。

## 3. 构建精简 libbox

`android/app/libs/libbox.aar` 已包含可直接构建的精简核心，只保留当前 HJPLY 需要的 VLESS、WebSocket、TLS/uTLS、gVisor TUN、DNS 和 Clash API。

如需从源码重建内核，先安装 Go `1.25.11`，然后执行：

```powershell
cd android
.\scripts\build-official-libbox.ps1
.\scripts\build-hjply-libbox.ps1
Copy-Item app\libs\libbox-hjply.aar app\libs\libbox.aar -Force
```

不要移除 `with_clash_api`。Android 服务启动时会创建 Clash API；缺少该 tag 会导致“create clash-server”错误。

## 4. 构建 APK

Windows 上项目路径包含中文时，Android AIDL 可能失败。建议创建一个英文目录 Junction 后构建：

```powershell
cmd /c mklink /J D:\codes\yunke\hjply-app-build D:\codes\yunke\空\hjply-rebuild-kit\android
cd D:\codes\yunke\hjply-app-build
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat :app:assembleOtherRelease --no-daemon --console=plain
```

产物位于：

```text
android/app/build/outputs/apk/other/release/hjply-<版本>-other-arm64-v8a-release.apk
```

安装后先连接，再访问 Google 验证基本连通性。若修改了 Worker UUID、订阅令牌或域名，必须重新填写 Android 订阅地址并重新构建 APK。

发布前建议完整执行：

```powershell
cd server
npm run check
npx wrangler deploy --dry-run --config wrangler.vpn.toml

cd ..\android
.\gradlew.bat :app:testOtherDebugUnitTest :app:lintOtherRelease :app:assembleOtherRelease --no-daemon --console=plain
```

## 安全清单

- 使用自己的 UUID、订阅令牌、Cloudflare KV 和域名。
- 不分享订阅 URL；令牌泄露后立即在 KV 更换并重建 APK。
- 不提交 `local.properties`、`subscription.properties`、`release.keystore` 或构建产物。
- 订阅令牌会编入 APK，拿到 APK 的人可以提取它；只向可信对象分发，泄露后立即轮换令牌和 UUID。
- 不引入第三方 ProxyIP、外部订阅转换器或来源不明的 Worker 代码。
