# HJPLY Worker

这是 HJPLY 的 Cloudflare Worker 服务端，只实现 VLESS-over-WebSocket 和私有订阅接口，不使用外部 ProxyIP、第三方订阅或回退代理。

部署前必须填写 `wrangler.vpn.toml` 中的 Worker 名称、VPN 域名和 Cloudflare KV namespace ID，并通过 `wrangler kv key put --remote` 写入自己的 UUID 与订阅令牌。`workers_dev` 默认关闭，只公开自定义域名。

发布前执行：

```powershell
npm ci
npm run check
npx wrangler deploy --dry-run --config wrangler.vpn.toml
npm run deploy
```

服务端会短时缓存 KV 密钥、兼容拆分的 VLESS 首包，并对单个 Worker 实例内的订阅请求做轻量限流。VLESS 每条 TCP 连接会使用独立 WebSocket，所以 WebSocket 握手不做进程内限流，避免误伤正常网页加载。完整部署和 Android 构建说明见上级目录的 `README.md`。
