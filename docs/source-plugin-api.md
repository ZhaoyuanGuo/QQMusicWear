# QQMusicWear 音乐源插件开发指南

APK 只包含通用运行框架（Rhino JS 引擎 + 桥接 API），全部协议实现
（端点、请求签名、扫码登录链、播放地址解析、响应解析）在可下载的源脚本中。

## 0. 多音乐源模型

- **一次只加载一个源**（不支持聚合搜索）：每个 JS 文件 = 一个音乐源，
  宿主按 `manifest.id` 隔离脚本缓存与登录凭据。
- 受支持的源由宿主静态注册表 `SourceRegistry` 定义（id / 文件名 / 兜底展示名 / 兜底主题色）：

  | id | 文件名 | 展示名 | 品牌色 |
  | --- | --- | --- | --- |
  | `qmusic-web` | `qmusic_source.js` | QQ音乐 | `#31C27C` |
  | `kugou-web` | `kugou_source.js` | 酷狗音乐 | `#2BA3F0` |
  | `netease-web` | `netease_source.js` | 网易云音乐 | `#E23B2E` |
  | `fanqie-web` | `fanqie_source.js` | 番茄畅听 | `#FF6A3D` |

- 宿主对每个源沿用同一套镜像基地址拼接 `文件名` 下载；切换源即切换曲库、
  登录态、主题色与首页推送。
- 品牌名与主题色**优先取 manifest**（`name` / `themeColor`），缺省用注册表兜底。

## 1. 注册契约

脚本被 Rhino 1.7.15（`optimizationLevel = -1`，ES5 风格最稳）执行，
末尾必须调用：

```js
qmu.register({
  manifest: {
    id: 'kugou-web',           // 唯一 id，须与宿主注册表一致
    name: '酷狗音乐',           // 品牌展示名（宿主 UI 文案）
    themeColor: '#2BA3F0',     // 品牌主题色；宿主据此切换全 app primary
    version: SOURCE_VERSION,   // 整数，每次协议变更 +1
    minAppVersion: MIN_APP_VERSION, // 宿主 versionCode 下限；旧 APK 拒绝加载
    playbackHeaders: { ... },  // 播放/下载请求头
    imageHostSuffix: 'kugou.com',
    imageHeaders: { ... },     // 封面防盗链头
    qualityPrefixes: { ... },  // 音质 -> CDN 专属文件前缀（供播放页展示音质角标；每档勿与其它档重叠，
                               // 否则宿主「前缀→音质」映射会被兜底前缀覆盖而误标）
  },
  handlers: { ... },           // 名称 -> function(args) -> 结果对象
});
```

宿主通过 `SourceManager.call(name, argsJson)` 调用 handler，返回值会被
`JSON.stringify` 后回传 Kotlin 侧反序列化为 DTO（`SourceDtos.kt`）。
**新增/修改 handler 必须同步更新 `SourceDtos.kt` 与契约测试
（`tools/contract-test/run.js` 的 `REQUIRED_HANDLERS`）。**

### 必须实现的 handler

`ping, homeFeed, recommendSongs, recommendNewSongs, playlistDetail, toplists,
toplistSongs, artistSongs, albumSongs, musicHallShelves, myPlaylists,
favPlaylists, userProfile, lyric, lyricTrans, lyricRoma, searchAll,
resolveUrls, setLike, qrLogin`

未实现的能力请返回空列表/空对象（契约测试会试调并校验可 JSON 序列化），
不要抛异常。

### 可选 handler（新增能力）

`artistAlbums, radioSongs, heartMode, djRadios, djPrograms, songComments,
cloudSongs, userEvents, userFollows`

- 这 9 个不在 `REQUIRED_HANDLERS` 内：源**实现了**才会被契约测试试调，未实现不报错
  （QQ 源无需回填即可通过 CI）。
- **宿主按注册表做能力探测**：`SourceEngine` 在加载源时记录已注册的 handler 名，
  经 `SourceManager.capabilitiesFlow` 下发 UI。**未实现的能力连入口都不显示**——
  例如未实现 `djRadios` 则「我的」页无「播客/电台」，未实现 `userEvents`/`userFollows`
  则无「动态/关注」。因此「不实现」即可安全隐藏功能，无需在 manifest 里额外声明能力。
  （QQ 源自 v13 起已实现 `songComments`，播放页评论钮可用。）
- 未登录 / 源不支持时必须安全返回空列表或空对象（不得抛异常、不得返回 undefined）。
- 数据契约见 `SourceDtos.kt`：`ArtistAlbumsDto` / `RadioDto` / `DjProgramDto` /
  `SongCommentsDto` / `UserEventDto` / `FollowUserDto`（`radioSongs`/`cloudSongs`/`heartMode` 返回 `SongDto[]`）。

## 2. 桥接 API（全局对象 `qmu`）

| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `http` | `(method, url, headersJson, body, contentType, followRedirects, bodyB64) -> {status, location, setCookie[], body, bodyB64}` | 同步阻塞请求；二进制响应用响应的 `bodyB64`；请求体为二进制时传第 7 参 `bodyB64`（base64，优先于文本 `body`，避免 UTF-8 编码破坏密文） |
| `credential` | `() -> {musicid, musickey, strMusicid, encryptUin, nick, avatarUrl, isLogged}` | 当前登录凭据快照 |
| `md5` | `(s) -> hex` | UTF-8 MD5 |
| `b64decode` | `(s) -> utf8 字符串` | 歌词等 base64 解码 |
| `aesCbcHex` | `(plain, key, iv) -> hex` | AES-CBC/PKCS7，输出 hex；key/iv 按 Latin1 取字节（16 字节 key = AES-128，32 字节 = AES-256）。酷狗 web 登录包体、网易 weapi |
| `aesCbcDecryptB64` | `(b64, key, iv) -> utf8 字符串` | AES-CBC/PKCS7 解密；失败返回空串（不抛异常）。酷狗 cloudlist 响应解密 |
| `inflateB64` | `(b64) -> utf8 字符串` | zlib 解压（`java.util.zip.Inflater`）；失败返回空串。酷狗 KRC 歌词解码 |
| `rsaNoPadHex` | `(plain, modulusHex, exponentHex) -> hex` | 裸 RSA（NoPadding，明文对齐到块高位）；key/iv 与包体原语一致。酷狗 web 登录包体 |
| `sleep` | `(ms)` | 同步等待（扫码轮询；引擎单线程，勿在 UI 关键路径滥用） |
| `log` | `(s)` | logcat `SourceJS` tag |
| `guid` | `() -> string` | 安装期随机 guid |
| `emit` | `(eventJson)` | 事件回调：扫码登录事件流 / 全局事件（见下） |
| `register` | `({manifest, handlers})` | 源入口，脚本末尾调用一次 |

> 摘要/加密类桥接按需扩展：实现新源若需要新的原语（如 RSA/AES），
> 在 `SourceEngine.buildBridge` 中补充后同步更新本文档与 `minAppVersion`。

## 3. 事件流

- **扫码登录**：`qrLogin` handler 执行期间通过 `emit({type, ...})` 发
  `QrReady{b64} / WaitingScan / ScannedConfirm / Expired / Refused / Success{credential} / Error{message}`，
  由 `QrLoginManager` 解析为领域事件。
  - `QrReady.b64` 为二维码 **PNG 原始字节的 base64**（宿主直接解码为 Bitmap 显示）；
    源的二维码接口若只返回字符串，请在 JS 内用二维码生成逻辑或源提供的图片接口转成 PNG。
  - `Success.credential` 字段：`musicid`（数字 uid，未知填 0）、`musickey`（会话密钥/Cookie，
    非空即视为已登录）、`strMusicid`、`encryptUin`（无则空串）、`nick`、`avatarUrl`。
- **凭据过期**：任意 handler 里 `emitGlobal('CredentialExpired')`
  （检测到 401/-460/2001 等且本地处于登录态），宿主清除凭据并 Toast 提示重登。

## 4. 数据契约要点（跨源约定）

- `userProfile` 返回 `{musicid, nick, avatarUrl, encryptUin, vipLabel}`：
  `vipLabel` 为会员身份徽标（如 `绿钻SVIP`/`概念版SVIP`/`黑胶SVIP`），非会员或查询失败返回**空串**（优雅降级，不抛异常）。
  判定口径（2026-10 实测）：QQ `userInfo.VipQueryServer/SRFVipQuery_V2`（`iSuperVip`/`HugeVip`→SVIP，`iVipFlag`/`ieight`/`itwelve`→VIP）；
  酷狗 `gateway.kugou.com/v1/get_union_vip`（GET + x-router=`kugouvip.kugou.com`，`busi_type=concept` 必传；
  `busi_vip[].product_type=svip/tvip`→概念版徽标，顶层 `is_vip`/`svip_level`→酷狗徽标）；
  网易云 `/api/nuser/account/get` 的 `account.vipType`（10=黑胶VIP 11/12=黑胶SVIP 100=音乐包）。
  宿主按标签关键字着色：绿钻=绿、酷狗/概念版=蓝、黑胶/音乐包=红。
- `Song.qualities` 为**该曲实际拥有的音质**（升序 Quality 名称数组：`STANDARD`/`HIGH`/`LOSSLESS`/`HI_RES`）。
  播放页音质面板据此只列出真实可选的档位，音质角标也按实际解析出的文件前缀显示；
  **判定不到时必须返回 `[]`（不得写死或猜测）**，宿主会降级为显示全部档位。
  各源判定口径（读自身真实文件信息，与账号权益无关）：
  - QQ：`file.size_128mp3`(标准)/`size_320mp3`(高品)/`size_flac`(无损)/`size_hires`(Hi-Res)，
    兼容 musicu 命名 `size128`/`size320`/`sizeflac`/`sizehires`。
  - 酷狗 / 概念版：`hash`(标准)/`320hash`(高品)/`sqhash`(无损)；无法区分无损与 Hi-Res，最高只到 `LOSSLESS`。
  - 网易云：详情 `l|m`(标准)/`h`(高品)/`sq`(无损)/`hr`(Hi-Res) 文件对象是否存在。
- `Song.mid` 必须**全局唯一且稳定**，用于队列/历史/红心/下载/歌词缓存的主键。
  非 QQ 源务必加前缀避免与其它源碰撞：酷狗 `kg_<hash>`、网易云 `ne_<songId>`、
  番茄 `fq_<itemId>`；QQ 保持原始 mid（兼容老用户数据）。
- 播放相关字段（`resolveUrls` 输入）按源自定义编码承载：
  - 酷狗：`mid = kg_<hash>`，`mediaMid = <encode_album_audio_id>`，`albumMid = <album_id>`。
  - 网易云：`mid = ne_<id>`，`songId = <id>`。
  - 番茄：`mid = fq_<itemId>`，`songId = <itemId>`，`albumMid = <bookId>`。
- `playlistDetail(disstid)`、`toplistSongs(topId)` 等入口的 id 为数字：
  非 QQ 源请把歌单/榜单/专辑的数字 id 映射到该字段。
- `homeFeed` 返回 `{cards:[{id,title,subtitle,action,targetId,coverUrl,songName,singers,colorStart,colorEnd,songs[]}]}`；
  `action` ∈ `songs|daily|rank|square|playlist|toplist|album|artist|recent|downloads|radio|podcast|events`，
  宿主据此分发（`radio`=拉 `radioSongs` 直接开播、`podcast`=进电台列表、`events`=进动态/关注）；
  `songs` 为该卡可直接播放的内容（可空）。

## 5. 签名与发布流程（强制）

APK 内置 Ed25519 公钥，下载/缓存/导入的脚本**必须**带签名头
`//qmu-sig:v1:<Base64签名>`，否则拒绝执行。私钥仅保存在开发者本地
`tools/source-signing/source_signing_private.key`（已 gitignore，严禁提交）。

```bash
cd tools/source-signing
java SourceSign.java gen                    # 仅首次：生成密钥对
java SourceSign.java sign ../../source/kugou_source.js
java SourceSign.java verify ../../source/kugou_source.js
```

CI（`.github/workflows/source-contract.yml`）在推送时强制对 `source/*_source.js`
逐个执行：
1. `verify_sig.js` —— 签名与公钥匹配；
2. `run.js` —— 契约测试（manifest 完整、handler 齐全、ping 可用、安全 handler 可试调）。

未签名/坏推送会被 CI 拦截，不会影响线上用户。

## 6. 版本兼容

- `manifest.version`：协议变更 +1，用户可在设置页手动「更新音乐源」。
- `manifest.minAppVersion`：新源若依赖新桥接 API（如 `aesCbcHex` / `rsaNoPadHex`），设为所需宿主
  `versionCode`；旧 APK 会提示更新应用而不是加载后崩溃。
  使用 `http.bodyB64` / `aesCbcDecryptB64` / `inflateB64`（宿主 versionCode ≥ 40）的源
  `minAppVersion` 必须 ≥ 40。

## 7. 兜底

所有镜像不可达时，用户可在门页或设置页「从存储导入」本地已签名的
`<源文件名>`（导入同样强制签名校验，导入到当前选中的源槽位）。