# HJPLY Android 客户端

这是 HJPLY 的单页 Android VPN 客户端，使用项目内置的 `libbox.aar`，默认执行“中国大陆直连、其他流量代理”的规则。远程 DNS 使用 `ipv4_only`，避免移动端优先选择 Worker 出站不稳定的 IPv6 路径。项目已移除上游的二维码、配置编辑、应用更新、Root、Shizuku、Xposed 和普通代理模式，只保留 TUN VPN 必需代码。

完整的服务端部署、订阅配置、签名证书和 APK 构建步骤见上级目录的 [`README.md`](../README.md)。构建前必须准备：

- `subscription.properties`：自己的 HTTPS 订阅地址。
- `local.properties`：Android SDK 路径和签名信息。
- `app/release.keystore`：自己的 release 签名证书。

这些文件均被 Git 忽略，不得上传到公开仓库。发布前递增 `version.properties`，然后执行：

```powershell
.\gradlew.bat :app:assembleOtherRelease --no-daemon --console=plain
```

当前只生成 `arm64-v8a` APK。大陆域名和 IP 规则随 APK 内置，不需要运行时从 GitHub 下载。

完整发布检查：

```powershell
.\gradlew.bat :app:testOtherDebugUnitTest :app:lintOtherRelease :app:assembleOtherRelease --no-daemon --console=plain
```
