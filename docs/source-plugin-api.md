# QQMusicWear 音乐源插件开发指南

APK 只包含通用运行框架（Rhino JS 引擎 + 桥接 API），全部协议实现
（端点、请求签名、扫码登录链、播放地址解析、响应解析）在可下载的
`source/qmusic_source.js` 中。本文档面向源插件的开发与维护者。

## 1. 注册契约

脚本被 Rhino 1.7.15（`optimizationLevel = -1`，ES5 风格最稳）执行，
末尾必须调用：

```js
qmu.register({
  manifest: {
    id: 'qmusic-web',          // 固定值
    version: SOURCE_VERSION,   // 整数，每次协议变更 +1
    minAppVersion: MIN_APP_VERSION, // 宿主 versionCode 下限；旧 APK 拒绝加载
    playbackHeaders: { ... },  // 播放/下载请求头
    imageHostSuffix: 'gtimg.cn',
    imageHeaders: { ... },     // 封面防盗链头
    qualityPrefixes: { ... },  // 音质 -> CDN 文件前缀
  },
  handlers: { ... },           // 名称 -> function(args) -> 结果对象
});
```

宿主通过 `SourceManager.call(name, argsJson)` 调用 handler，返回值会被
`JSON.stringify` 后回传 Kotlin 侧反序列化为 DTO（`SourceDtos.kt`）。
**新增/修改 handler 必须同步更新 `SourceDtos.kt` 与 `app/src/test` 契约测试。**

## 2. 桥接 API（全局对象 `qmu`）

| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `http` | `(method, url, headersJson, body, contentType, followRedirects) -> {status, location, setCookie[], body, bodyB64}` | 同步阻塞请求；二进制响应用 `bodyB64` |
| `credential` | `() -> {musicid, musickey, strMusicid, encryptUin, nick, avatarUrl, isLogged}` | 当前登录凭据快照 |
| `md5` | `(s) -> hex` | UTF-8 MD5 |
| `b64decode` | `(s) -> utf8 字符串` | 歌词等 base64 解码 |
| `sleep` | `(ms)` | 同步等待（扫码轮询；引擎单线程，勿在 UI 关键路径滥用） |
| `log` | `(s)` | logcat `SourceJS` tag |
| `guid` | `() -> string` | 安装期随机 guid |
| `emit` | `(eventJson)` | 事件回调：扫码登录事件流 / 全局事件（见下） |
| `register` | `({manifest, handlers})` | 源入口，脚本末尾调用一次 |

## 3. 事件流

- **扫码登录**：`qrLogin` handler 执行期间通过 `emit({type, ...})` 发
  `QrReady{b64} / WaitingScan / ScannedConfirm / Expired / Refused / Success{credential} / Error{message}`，
  由 `QrLoginManager` 解析为领域事件。
- **凭据过期**：任意 handler 里 `emitGlobal('CredentialExpired')`
  （检测 musicu code 2001/2002 且本地处于登录态），宿主清除凭据并 Toast 提示重登。

## 4. 签名与发布流程（强制）

APK 内置 Ed25519 公钥，下载/缓存/导入的脚本**必须**带签名头
`//qmu-sig:v1:<Base64签名>`，否则拒绝执行。私钥仅保存在开发者本地
`tools/source-signing/source_signing_private.key`（已 gitignore，严禁提交）。

```bash
cd tools/source-signing
java SourceSign.java gen                    # 仅首次：生成密钥对
java SourceSign.java sign ../../source/qmusic_source.js
java SourceSign.java verify ../../source/qmusic_source.js
```

CI（`.github/workflows/source-contract.yml`）在推送时强制：
1. `verify_sig.js` —— 签名与公钥匹配；
2. `run.js` —— 契约测试（manifest 完整、handler 齐全、ping 可用）。

未签名/坏推送会被 CI 拦截，不会影响线上用户。

## 5. 版本兼容

- `manifest.version`：协议变更 +1，用户可在设置页手动「更新音乐源」。
- `manifest.minAppVersion`：新源若依赖新桥接 API，设为所需宿主
  `versionCode`；旧 APK 会提示更新应用而不是加载后崩溃。

## 6. 兜底

所有镜像不可达时，用户可在门页或设置页「从存储导入」本地已签名的
`qmusic_source.js`（导入同样强制签名校验）。
