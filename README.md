# QQMusicWear

Wear OS 第三方 QQ 音乐客户端（Jetpack Compose + Media3），为手表圆形小屏打造的完整听歌体验。

[![source-contract](https://github.com/ZhaoyuanGuo/QQMusicWear/actions/workflows/source-contract.yml/badge.svg)](https://github.com/ZhaoyuanGuo/QQMusicWear/actions/workflows/source-contract.yml)

> **⚠️ 免责声明**
>
> - 本项目系个人开发者出于**学习、研究与技术交流**目的开发的非官方、非商业性软件，与腾讯公司及其「QQ音乐」产品**不存在任何隶属、代理、合作、授权或关联关系**，亦非其官方客户端。
> - 通过本软件获取的全部内容仅限个人学习与测试，严禁传播、二次分发或用于任何商业用途，请以合法正当方式获取内容、**支持正版**。
> - 使用本人 QQ 账号登录产生的一切后果与风险（包括但不限于账号被限制、冻结、封禁、凭据失效等）由用户**自行承担**。
> - 本软件按「现状」提供，开发者不对可用性、稳定性、持续维护作任何形式的担保。
> - 下载、安装或使用前，请在应用内完整阅读《用户协议与免责声明》全文。

## 功能特性

- 推荐卡片首页 / 每日推荐
- 排行榜、歌单广场、搜索分区
- 歌词同步显示
- 红心收藏、最近播放
- 播放队列管理
- 批量下载（本地保存，仅供学习研究，请合理期限内自行删除）
- 睡眠定时

## 架构：音乐源插件机制

APK 安装包内**不包含任何协议实现**。协议逻辑外置为 JavaScript 音乐源插件，首次启动时从公开镜像下载，在设备本地的 Rhino 沙箱中运行：

- **Ed25519 签名校验**：插件头部携带数字签名，APK 内置公钥强制校验；未签名或被篡改的脚本拒绝执行，缓存验签失败同样会被自动清除
- **多镜像容灾**：腾讯云 CloudBase 静态托管（国内直连，首选）→ jsDelivr → fastly → ghproxy → raw.githubusercontent，依次回退
- **手动导入兜底**：全部镜像不可用时，可在门页或设置页「从存储导入」开发者发布的插件文件（同样强制签名校验）
- **凭据过期闭环**：检测到登录态失效（接口 code 2001/2002）时自动清除本地凭据并提示重新登录
- **版本闸门**：插件 manifest 声明 `minAppVersion`，旧版宿主拒绝加载不兼容的新插件

插件源码见 [`source/`](source/)，行为契约与签名校验的 CI 门禁见 [`tools/contract-test/`](tools/contract-test/)，插件开发指南见 [`docs/source-plugin-api.md`](docs/source-plugin-api.md)。

## 系统要求

- Wear OS 4+ 手表设备（Android 13 / API 33 及以上）
- 支持 QQ 扫码登录

## 构建

```bash
git clone https://github.com/ZhaoyuanGuo/QQMusicWear.git
cd QQMusicWear
./gradlew assembleRelease
```

或使用 Android Studio 打开项目直接运行（需 JDK 17+）。

## 使用限制

- 本项目未附带开源许可证，默认保留所有权利：你可以查看、学习、研究源码，但未经开发者书面许可，不得用于商业或营利用途，不得再分发或收费提供（详见应用内《用户协议与免责声明》第二、三条）。
- 「QQ音乐」及相关名称、标识为腾讯公司或其权利人的商标，本项目中的提及仅用于客观描述内容来源，不构成任何商标性使用或授权。
