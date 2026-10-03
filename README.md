# QQMusicWear

Wear OS 第三方音乐客户端（Kotlin + Jetpack Compose for Wear OS + Media3），内置 **QQ音乐 / 酷狗音乐 / 酷狗概念版 / 网易云音乐** 四种音乐源，一次加载一个——主题色、品牌名、桌面图标随源切换。专为手表小屏打造：圆表方表自适应、表冠导航、卡拉OK歌词，一只手、一块表即可完成从发现到播放的全部操作。

[![source-contract](https://github.com/ZhaoyuanGuo/QQMusicWear/actions/workflows/source-contract.yml/badge.svg)](https://github.com/ZhaoyuanGuo/QQMusicWear/actions/workflows/source-contract.yml)
[![Release](https://img.shields.io/github/v/release/ZhaoyuanGuo/QQMusicWear)](https://github.com/ZhaoyuanGuo/QQMusicWear/releases/latest)

> **⚠️ 免责声明**
>
> - 本项目系个人开发者出于**学习、研究与技术交流**目的开发的非官方、非商业性软件，与腾讯公司、网易公司及其「QQ音乐」「酷狗音乐」「网易云音乐」等产品**不存在任何隶属、代理、合作、授权或关联关系**，亦非其官方客户端。
> - 通过本软件获取的全部内容仅限个人学习与测试，严禁传播、二次分发或用于任何商业用途，请以合法正当方式获取内容、**支持正版**。
> - 使用本人账号登录各音乐平台产生的一切后果与风险（包括但不限于账号被限制、冻结、封禁、凭据失效等）由用户**自行承担**。
> - 本软件按「现状」提供，开发者不对可用性、稳定性、持续维护作任何形式的担保。
> - 下载、安装或使用前，请在应用内完整阅读《用户协议与免责声明》全文。

## 功能特性

**多音乐源**

- 内置 **QQ音乐 / 酷狗音乐 / 酷狗概念版 / 网易云音乐** 四种音乐源，设置内一键切换，一次只加载一个
- 主题色、品牌名、桌面应用名与图标随源切换；登录凭据按源隔离，切源即切账号
- 账号卡昵称右侧显示当前平台的会员身份徽标（绿钻 / 酷狗概念版 / 黑胶），按平台着色

**内容发现**

- 推荐卡片首页 / 每日推荐 / 私人漫游（FM）
- 排行榜、歌单广场、歌手页（含「专辑」分区）、专辑页
- 搜索分区（歌曲 / 歌手 / 专辑 / 歌单）
- 播客 / 电台、动态 / 关注
- 歌曲评论（热门评论 + 分页）
- 红心收藏、最近播放（含本周收听统计）

**播放与下载**

- 音质选择：标准 / 高品质 / 无损 / Hi-Res
- 播放失败自愈：优先重试本地已下载文件 → 音质逐级降级重试 → 自动跳过下一曲
- 播放队列管理，队列与播放进度自动持久化，冷启动恢复
- 批量下载（本地保存，仅供学习研究，请合理期限内自行删除）
- 睡眠定时、屏幕常亮 / 播放页不熄屏
- 常驻系统媒体卡片（Ongoing Activity），播放状态一目了然，点击返回应用

**歌词**

- 逐行接力卡拉OK填充效果
- 原文 / 翻译 / 罗马音三轨显示
- 已下载歌曲自动缓存歌词，离线可用

**手表深度适配**

- 圆表 / 方表双布局策略：数据层完全共享，仅 UI 分叉——圆表内容居中 + ScalingLazyColumn 边缘缩放，方表用满矩形空间 + 全宽贴边卡片，可跟随屏幕自动识别或手动切换
- 表冠导航：播放页转表冠进入队列页，队列页表冠滚动列表，无库内触觉设备（小米手表等）自动补充刻度震动
- 右侧竖直滚动指示条，半透明轨道 + 白色滑块
- 6 种播放进度样式（设置 → 显示可预览切换）：屏幕边框描边 / 封面描边 / 液体填充 / 波形刻度 / 点阵进度 / 唱片弧线，默认液体填充
- 低配置设备模式：一键关闭模糊封面、唱片旋转、动画过渡等视觉效果，流畅优先
- 启动崩溃诊断：致命崩溃自动落盘并在下次启动展示诊断页，支持一键复制日志反馈

## 系统要求

- **Wear OS 手表**：Wear OS 4+（推荐）
- **非 Wear OS 手表**：Android 8.1（API 27）及以上也可运行（如小米手表、OPPO Watch 等，部分功能可能受限）
- 各音乐源均支持扫码登录

## 安装

前往 [Releases](https://github.com/ZhaoyuanGuo/QQMusicWear/releases/latest) 下载最新版本 APK（页顶 Release 徽章即当前最新版，文件名形如 `QQMusicWear-v<版本号>.apk`），通过 adb 或文件传输安装到手表：

```bash
# 将文件名替换为你下载的最新版 APK
adb install QQMusicWear-v<版本号>.apk
```

首次启动会引导阅读用户协议，并从公开镜像下载音乐源插件（见下文）；若全部镜像不可用，可在门页或设置页「从存储导入」开发者发布的插件文件。

## 架构：音乐源插件机制

APK 安装包内**不包含任何协议实现**。协议逻辑外置为 JavaScript 音乐源插件，首次启动时从公开镜像下载，在设备本地的 Rhino 沙箱中运行：

- **Ed25519 签名校验**：插件头部携带数字签名，APK 内置公钥强制校验；未签名或被篡改的脚本拒绝执行，缓存验签失败同样会被自动清除
- **多镜像容灾**：腾讯云 CloudBase 静态托管（国内直连，首选，插件当前仅托管于此）→ jsDelivr → fastly → ghproxy → raw.githubusercontent，依次回退
- **手动导入兜底**：全部镜像不可用时，可在门页或设置页「从存储导入」开发者发布的插件文件（同样强制签名校验）
- **凭据过期闭环**：检测到登录态失效（接口 code 2001/2002）时自动清除本地凭据并提示重新登录
- **版本闸门**：插件 manifest 声明 `minAppVersion`，旧版宿主拒绝加载不兼容的新插件

插件源码**不随本仓库分发**（仅通过 CDN 提供），行为契约与签名校验工具见 [`tools/contract-test/`](tools/contract-test/)，插件开发指南见 [`docs/source-plugin-api.md`](docs/source-plugin-api.md)。

## 构建

环境要求：JDK 17+、Android SDK（compileSdk 36）。

```bash
git clone https://github.com/ZhaoyuanGuo/QQMusicWear.git
cd QQMusicWear
./gradlew assembleRelease
```

或使用 Android Studio 打开项目直接运行。产物位于 `app/build/outputs/apk/release/`。

**主要技术栈**：Kotlin、Jetpack Compose for Wear OS（Material 3）、Media3 / ExoPlayer、Coil、Rhino（JS 插件沙箱）、DataStore / SharedPreferences。

### 本地验证

提交前可在本地复现 CI 的质量门禁（单元测试 + 音乐源签名/契约校验），无需真机。

前置环境：

- **JDK 17+**：运行 Gradle 单元测试
- **Node.js 20+**：运行音乐源签名校验与契约测试脚本

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

音乐源契约测试（与 CI `source-contract` 工作流等价）：仓库不包含插件源码，需先将插件脚本放入本地 `source/` 目录，再依次执行

```bash
# 1) 校验音乐源插件的 Ed25519 签名（输出 VERIFY OK 表示通过）
node tools/contract-test/verify_sig.js source/qmusic_source.js tools/source-signing/source_signing_public.key

# 2) 以 mock 桥接加载插件，校验注册契约与 handler 齐全性（输出 CONTRACT OK 表示通过）
node tools/contract-test/run.js source/qmusic_source.js

# 3) mock 冒烟测试
node tools/contract-test/mock_smoke.js source/qmusic_source.js
```

任一命令以非零码退出即表示门禁失败。CI 在 `source/` 不存在时会自动跳过该项。

## 使用限制

- 本项目未附带开源许可证，默认保留所有权利：你可以查看、学习、研究源码，但未经开发者书面许可，不得用于商业或营利用途，不得再分发或收费提供（详见应用内《用户协议与免责声明》第二、三条）。
- 「QQ音乐」「酷狗音乐」「网易云音乐」及相关名称、标识为对应权利人（腾讯公司 / 网易公司等）的商标，本项目中的提及仅用于客观描述内容来源，不构成任何商标性使用或授权。
