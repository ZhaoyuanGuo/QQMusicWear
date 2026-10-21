# 番茄畅听取链中转服务

## 原理

番茄畅听（com.xs.fm）的播放取链接口 `playerapi/video_model/mget/v1` 在服务端拒绝所有
非官方客户端请求（PC 直连返回 `403 invalid aid`），且七神签名（X-Gorgon/X-Argus 等）
在 libmetasec_ml.so 的 native 层注入，无法在纯 JS 插件中复现。

实测发现（Frida hook 确认）：

1. App 在 Java 层发出的取链请求也**不带签名头**（仅 x-ss-req-ticket），
   签名由 libsscronet 内部 native 路径注入，且请求走 QUIC 私有通道。
2. 但 App 内部业务方法 `PlayerApiService.j(MGetVideoModelRequest)` 可以被
   Frida 主动调用（构造 request + rxjava2 subscribe 即发请求）。
3. 响应模型 `VideoModelData.$init` 可 hook，`video_model` 字段是 JSON 字符串，
   含 `video_list[].main_url` —— **明文 M4A 直链**（novelfmvod.com，支持 Range）。
4. 章节目录走 `BookApiService.h(GetDirectoryItemIdsRequest)`（请求只需 book_id），
   批量章节详情走 `BookApiService.i(GetDirectoryItemInfoRequest)`
   （book_id + item_ids 分批 20 个）；注意 `g()` 是带 page_scene 的另一端点，
   直接调会返回 `4000 网络错误`。响应统一用 Gson 序列化捕获（避免反射遍历崩溃）。

因此本服务不做签名复现，而是**让真机 App 自己取链**：

```
手表源 --HTTP--> 本服务 --Frida RPC--> 真机番茄畅听 App --> 直链
```

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET  | /health   | 健康检查（自动 attach App） |
| GET  | /dumpsvc  | dump Book/Player ApiService 方法签名（调试） |
| POST | /resolve  | `{"book_id":"..","item_ids":[".."]}` → 直链 JSON |
| POST | /raw      | 同上但附原始 video_model JSON |
| POST | /chapters | `{"book_id":".."}` → 全量章节（item_id+标题+时长）+ 书籍信息 |

/resolve 响应：

```json
{
  "item_id": "7629142497800749630",
  "expire": 1791297087,
  "duration": 409.221,
  "media_type": "audio",
  "urls": [{"quality": "medium", "size": 2492534, "url": "https://v5-gzb2-novelfm.novelfmvod.com/..."}]
}
```

/chapters 响应（2026-10-06 实测，全书 139 章 titled=139）：

```json
{
  "book_id": "7621481208274226201",
  "total": 139,
  "titled": 139,
  "chapters": [{"item_id": "7629142479614247486", "title": "001 犯五弊三缺者", "order": "1", "duration": 397013}],
  "book_info": {"book_name": "六爷扎纸铺", "author": "黑马写手", "anchor": "大百科", "thumb_url": "...", "abstract": "...", "tags": "民俗秘闻,悬疑,灵异", "score": "8.3", "serial_count": "139"}
}
```

## 前置条件

1. USB 连接已 root 真机（Magisk），设备序列号在 `DEVICE_ID` 常量
2. 手机安装番茄畅听并登录（未登录部分书不可播）
3. frida-server 16.7.19 运行于设备 `/data/local/tmp/frida-server16`
   （需 `su --mount-master` 启动以进入 magisk 域，避开 SELinux 限制）
4. PC 端 `pip install frida==16.7.19 frida-tools`，adb 在 PATH

## 启动

```powershell
python tools/fanqie-proxy/fq_proxy.py
# 监听 :8791；首次 /health 或 /resolve 时自动 attach com.xs.fm
```

注意（实例捕获时序）：
- 服务重启后需要 App 有任意一次 PlayerApiService/BookApiService 自然调用
  才会捕获到 service 代理实例；日志出现 `SVC_CAPTURED` / `BOOK_CAPTURED` 即就绪。
- 播放中的 App 不会持续调 BookApiService；可靠触发方式是
  「App 内进入一本书详情页并点开始播放」（建队列会调 h/i）。
- 手表侧源（source/fanqie_audiobook_source.js）在 resolve/chapters 失败时
  单曲返回 null，宿主自动跳过，不抛异常。

## 消费方

- `source/fanqie_audiobook_source.js`（番茄畅听听书源）：
  搜索/分类榜免签直连 novelfm API；章节目录与播放直链走本服务。
  源内 `PROXY_BASES` 常量需包含本机局域网地址（当前 `192.168.28.133:8791`）；
  手表也可用 `adb reverse tcp:8791 tcp:8791` 后走 `127.0.0.1:8791`。
- Windows 防火墙需放行 8791 入站（手表 → PC 方向）。

## 已验证

- 2026-10-06：端到端取链成功（《六爷扎纸铺》medium 档 2.49MB m4a），
  Range 请求返回 206 + `ftyp M4A` 魔数，ExoPlayer 可直接播放。
- 2026-10-06：`/chapters` 全量目录打通（h() 目录 + i() 分批详情合并，139/139 有标题）。
- 2026-10-06：听书源实时冒烟通过（node 桩宿主：分类榜 → 搜书 → 专辑章节 →
  书入口自动展开第一章 → 直链 206 ftyp M4A）。
- 2026-10-06：OPPO Watch (OW19W1) 实机验证通过：切源 → 听书分类 → 都市榜 →
  点书自动从第一章开播，进度持续走动（bookId 7623003194712394814，
  0:56/6:34 处仍在播放）。900+ 章大书全量目录拉取约 60s 会触发宿主超时，
  已通过 /chapters `limit` 参数解决（书入口 20、专辑页 100）。

## 已知限制

- 直链有时效（响应 `url_expire`，约 1-2 小时），过期需重新 resolve
- 主动调用需要 App 前台且已初始化播放服务；App 冷启动后需触发一次播放/切集
- 中转重启后需重新捕获 BookApiService 实例（见上）
