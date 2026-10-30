# tools/ —— 开发期诊断与验证脚本

> **这不是交付物，也不参与构建。** 这些脚本是排查和验收时用的"取证工具"：
> 结论只认 dump 文本 / 存储 / 服务端 JSON / 运行时测量，而这些脚本就是拿到它们的办法。
> 2026-09-30 曾把整个 `tools/` 移出仓库（当时目录里有真实会话 cookie、抓包数据和 7 份源码镜像）；
> 2026-10-30 按"只收录下次还会用到的、且不含任何凭据"的口径重新加回本目录。

## 为什么它们值得在仓库里

- **发布链路**：本机 `github.com:443` 长期不可达，`git-api-push.js` 是唯一推送通道，发版必须用；
- **验证纪律**：`watch-ui.ps1`、`rect-probe.py`、`video-area.py` 这套"数窗口 + 量像素"的办法
  是抓出「看着被遮挡其实是控件内部被裁」「叠窗数不出来」这类问题的关键；
- **可复现的结论**：GeckoView 体积曲线、补丁所需的浏览器代际判定，换台机器要能重跑一遍才算数；
- **运维**：公告走 Cloudflare KV，`cf-kv.js` 改公告不重新部署 Worker。

## 环境变量（脚本都用默认值，可覆盖）

| 变量 | 作用 | 默认 |
|---|---|---|
| `ADB` | adb 可执行文件路径 | `E:\sdk\platform-tools\adb.exe`（Windows SDK 常见位置） |
| `WATCH_SERIAL` | 目标设备序列号 | 自动取第一台 `device` 状态的设备 |
| `CF_TOKEN_FILE` | Cloudflare 凭据源文件 | `../.cf-token.txt`（仓库外，不入库） |
| `CF_CRED_FILE` | 解析后的凭据 JSON | `../cf-cred.json`（仓库外，不入库） |

## 依赖

- **Node.js ≥ 18**：所有 `.js` 只用内置模块（`fs` / `os` / `net` / `dns` / `child_process`），
  **不需要 `npm install`**（本仓库里不提供 `package.json`；开发机上那份属于历史实验，不入库）。
- **Python 3 + Pillow**：`*.py` 是像素测量脚本（`pip install pillow`）。
- **Windows PowerShell**：`*.ps1` 需要带 UTF-8 BOM（PS 5.1 无 BOM 会按 GBK 读中文注释而报解析错误）。

## 一、设备与真机诊断

| 脚本 | 用途 |
|---|---|
| `adb-retry.js` | 带自动重连重试的 adb 封装。目标手表的非官方充电器连接不稳，**其它脚本都 `require` 它** |
| `watch-ui.ps1` | **数弹窗**（`ty=APPLICATION` 且可见的 `com.dywatch.app` 窗口数 —— uiautomator dump 只导出活动窗口，数不出叠窗）+ 列可见文本与中心坐标；`-TapX/-TapY` 可"先点再量" |
| `watch-census4.js` | 手表体检：身份、WebView 内核版本、内存、屏幕参数、安装信息 |
| `watch-repro3.js` | 复现私信失败并抓 logcat（自动关弹窗、找聊天入口） |
| `watch-chat-probe.js` | 聊天页 DOM 与桥事件探针 |
| `watch-chat-timing.ps1` | 聊天页分阶段耗时（冷启动 → 会话列表 → 进入会话） |
| `watch-webview-allowlist.js` | 从设备 `framework-res.apk` 里挖 WebView 提供者白名单（判定"能不能换内核"的决定性证据） |
| `dns-diagnose.js` | 共享通配域（`workers.dev` / `r2.dev`）的 DNS 污染取证 |

## 二、像素与版面测量

结论不能只看截图，这几支是给出**数值**的：

| 脚本 | 用途 |
|---|---|
| `edge-probe.py` | 沿某 y 行从左扫，找第一个非底色像素 = 该行左边缘（量"页面内缩了多少"） |
| `rect-probe.py` | 某矩形内的像素构成：药丸底灰阶与高度、文字亮像素 bbox（量控件内部有没有被裁） |
| `crop-zoom.py` | 裁一块放大保存，配 `read_image` 肉眼验收 |
| `video-area.py` | 量视频画面占屏高（多列聚合找 letterbox 黑边） |
| `col-runs.py` | 沿某列做颜色分段（早期版本，细节取证用） |
| `pinch.sh` | `sendevent` 注入双指捏合试验。**本表 SELinux 拒绝写 `/dev/input/event1`**，双指类手势只能手指验；脚本留着，换设备可复用 |

## 三、老内核（Chromium 83）判定

抖音 PC 私信微前端按 Chrome 92+ 构建，手表内核冻结在 83 → 整模块解析失败（结论见根目录文档）。
这四支是当初定案与对照实验的工具：

| 脚本 | 用途 |
|---|---|
| `js-syntax-proof.js` | 扫 JS 文件的语法，判定需要哪代内核（`PrivateIdentifier` 真值对照） |
| `js-syntax-scan.js` | 同族批量扫描 |
| `mobile-web-probe.js` | 对照实验：手机版页有没有私信页 |
| `ua-bundle-test.js` | 对照实验：抖音是否按 UA 版本分发不同 bundle（结论：不分发） |

## 四、发布与推送

`github.com:443` 不可达，推送/发版只能走 API；`gh` 相关命令同样依赖这些脚本的口径。

| 脚本 | 用途 |
|---|---|
| `git-api-push.js` | **推送主通道**：blobs → trees → commits → PATCH ref，自动对齐本地/远端 sha |
| `git-api-syncback.js` | 从远端回拉对齐本地引用 |
| `brute-commit.js` | 提交对象差异的爆破定位 |
| `compare-commit.js` | 本地与远端 commit 对象逐项对比 |
| `probe-commit-diff.js` | 差异定位（"GitHub 会去掉 commit message 末尾换行"就是靠它发现的） |
| `ci-watch.js` | 轮询 GitHub Actions run 直到结束 |
| `git-channel-test.js` | git 通道可达性测试 |

## 五、Cloudflare（公告 API 运维）

| 脚本 | 用途 |
|---|---|
| `cf-cred.js` | 解析 `.cf-token.txt` → `cf-cred.json`（打印一律脱敏，绝不输出完整密钥） |
| `cf-api.js` | 通用 API 客户端：`node cf-api.js GET /accounts/{id}/workers/scripts` |
| `cf-kv.js` | KV 读写（公告 `key=current`）；**改公告只改 KV，不用重新部署 Worker** |
| `cf-deploy-worker.js` | 部署 `cloudflare/dy-announce.js` |
| `r2-check.js` | R2 通道体检（S3 API 与 `r2.dev` 公共域可达性） |
| `reach-test.js` / `reach-test2.js` | 端点可达性测试 |
| `announce-eps-test.js` | 公告多端点降级链路测试 |

## 六、GeckoView 调研（已结案，留作复现）

方案 B（内置 GeckoView）已因内存与体积被否，这三支脚本是当时的数据来源。
注意坑：`maven-metadata.xml` 的版本列表**不按时间排序**，取"最后一个元素"会拿到 2022 年的版本。

| 脚本 | 用途 |
|---|---|
| `geckoview-size-curve.js` | 各版本 armeabi-v7a AAR 体积曲线（选 100~110 的依据） |
| `geckoview-channels.js` | release / nightly 通道核查 |
| `geckoview-versions.js` | 版本列表（按版本号排序取最大） |

## 安全边界

- **本目录不含任何凭据、会话 cookie、抓包数据或源码镜像。** 凭据只存在于仓库外的
  `.cf-token.txt` / `cf-cred.json`（被 `.gitignore` 挡住）。
- 开发机上同目录还留着历史抓包文件（`*.bin`、`*.json`、`session*`、`bili-src/` 等），
  它们被 `.gitignore` 显式排除，**不要 `git add -f`**。
- 一次性迭代脚本（`diag-*`、`patch-*`、`verify-*`）与"拿登录态探接口"的脚本
  （`expr-*`、`cookie-api-probe.js`）**故意不进仓库**：前者无复用价值，后者放公开仓库容易被误读。

## CI 不跑这些脚本

`.github/workflows/build.yml` 只跑单测与 debug 打包。这些脚本依赖真机、本机凭据和网络通道，
放进流水线既跑不通也没有意义。
