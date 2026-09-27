# 腕上音符（wanshang-yinfu）

安卓**手表**端轻量抖音客户端：刷视频 + 私信聊天。纯手动操作，免费给朋友用，不商业化。

> ⚠️ 本应用与抖音官方**无任何关系**，非官方应用。仅供个人学习与技术交流，使用风险自负。

## 功能

- **刷视频**：上下滑切换 · 预加载缓冲 · H.264/540p 省流选档 · 登录态个性化推荐
- **播放控制**：单击呼出面板（暂停键 + 进度条）· 双击左右半屏 ±10s · 拖动进度条跳转
- **私信聊天**：会话列表 · 消息收发 · 快捷回复（手表输入层）
- **互动**：点赞 / 收藏 / 评论（看 + 发）/ 分享=复制链接
- **「我的」**：喜欢历史（服务端）· 看过记录（本地）· 作者主页
- **手表适配**：圆屏边距可调 · 界面缩放/字体大小独立 · 表冠滚动（可选）· 无手势无按键导航 · 常亮开关
- **更新检查**：GitHub Releases 静默检测

## 下载

到 [Releases](https://github.com/5h1iky/wanshang-yinfu/releases) 下载 `wanshang-yinfu-vX.Y.Z-release.apk` 侧载安装（Android 5.0+，手机/手表均可装）。

## 隐私

- 登录态（扫码会话）**只保存在你自己的设备上**，开发者不收集、不存储、不上传任何账号信息或聊天内容。
- 不收费、不接广告、无任何统计 SDK。

## 技术栈

Java + XML · AGP 8.7 / Gradle 9.3 / JDK 21 · minSdk 21 · 播放 [DKVideoPlayer](https://github.com/Doikki/DKVideoPlayer)（Apache-2.0，源码内嵌）· App 内 Rhino 跑 a_bogus 签名（[douyin_sign](https://github.com/ylcangel/douyin_sign)，Apache-2.0）· 读接口原生直连、写操作 WebView 引擎（零风控复刻成本）。

## 开源协议

[GPL-3.0](LICENSE)
