# 云端通道说明（Cloudflare 三件的实测结论与决策）

> 最后更新：2026-09-30　结论一句话：**只保留「公告 API」，R2 镜像与签名兜底已按实测砍掉。**

---

## 一、最重要的实测结论：被墙的不是 Cloudflare，是两个通配域名

同一时刻、同一网络（国内家宽）实测：

| 域名 | 本地 DNS 解析 | 阿里 DoH（223.5.5.5）解析 | 判定 |
|---|---|---|---|
| `*.workers.dev` | `108.160.170.45`（Facebook 段） | `69.63.184.14`（假 IP） | ❌ DNS 污染，**连 DoH 都被投毒** |
| `workers.dev` | `192.133.77.191`（Twitter 段） | `103.226.246.99`（假 IP） | ❌ 同上 |
| `cdn.jsdelivr.net` | `104.17.207.5 / .208.5` | `104.17.208.5 / .207.5` | ✅ **真 Cloudflare IP**，615ms |
| `api.github.com` | `20.205.243.168` | 同一真实 IP | ✅ 477ms |
| `raw.githubusercontent.com` | — | — | ❌ ECONNRESET |
| `*.r2.cloudflarestorage.com` | `172.64.66.1`（真 CF IP） | — | ❌ TLS 握手直接失败 |
| `gitee.com` | — | — | ✅ 310ms |

**读法**：`cdn.jsdelivr.net` 背后就是 Cloudflare 的边缘网络（104.17.x 是 Cloudflare 的段），
它跑得好好的。被投毒的是 `workers.dev` / `r2.dev` 这类**共享通配域名**——
它们在名单里，换 DNS 也没用（DoH 一样拿到假 IP）。

由此推出的工程口径：
- **Cloudflare 能用，但必须借一个"没被污染的域名"**。本项目里那就是 jsDelivr。
- 没有自有域名 → Workers / R2 的默认域名全部等于不可用。

---

## 二、保留项：公告通道（①）

### 端点优先级（App 侧 `net/AnnounceApi.java`，单测锁死顺序）

| # | 端点 | 形态 | 为什么是这个位置 |
|---|---|---|---|
| 1 | `api.github.com/.../contents/announce.json` | `{content: base64}` | **每次都读仓库实时内容**（无 CDN 缓存），实测国内可达 477ms。公告要"改了立刻生效"，靠它 |
| 2 | `cdn.jsdelivr.net/gh/...@main/announce.json` | 裸 JSON | 国内可用性好，但**分支→commit 映射有 12h 缓存**（实测 purge 后仍吐旧内容、加查询串也无效） |
| 3 | `fastly.jsdelivr.net/...` | 裸 JSON | 同上换节点 |
| 4 | `dy-announce.sharkyline.workers.dev/announce` | 裸 JSON / 204 | 改 KV 即时生效，但对国内基本不可达；留给有代理的用户，排最后避免拖慢 |

全失败 → 静默不弹（用户拍板口径："拉不到就不显示"）。

### ⚠️ 踩过的坑（改这块代码前必读）

1. **`Accept: application/vnd.github.raw+json` 会让 GitHub 返回裸文件**，而不是
   `{content: base64}` 包装 → 解析器找不到 `content` 字段，判成"无公告"。
   真机症状：诊断页出现 `[announce] 无有效公告 via gh-api`（通道明明是通的）。
   现在：每个端点带自己的 `accept` 字段；`parseGithubContents` 在缺 `content` 时
   回退按公告 JSON 解析（对内容协商做防御）。
2. **`android.util.Base64` 会让 JVM 单测直接 "Stub!" 失败**，`java.util.Base64` 又要 API 26
   （本项目 minSdk 21）→ 自己写了 20 行纯 Java 解码器 `AnnounceApi.decodeBase64`。

### 怎么发一条公告（运维流程）

```bash
# 1. 改 announce.json（enabled=true，id 必须换新——同 id 用户读过就不再弹）
# 2. 提交
cd D:\dev\dywatch
git add announce.json && git commit -F .cm.txt
# 3. 推送（见第三节：本机 github.com:443 被墙，要用 API 推送工具）
node C:\Users\admin\Desktop\newproject3\tools\git-api-push.js
```

用户侧生效时间：**几秒**（端点 1 无缓存）。走 jsDelivr 的用户可能要等缓存过期。
急的话可以顺手刷一下 jsDelivr（实测对 @main 分支映射不一定立刻生效，仅作为辅助）：

```
https://purge.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json
```

公告字段说明见仓库根目录 `announce.json` 里的 `_schema`。

### Worker 本体（备用通道，已部署）

- 源码：`cloudflare/dy-announce.js`；接口：`GET /announce`（KV 单条）、`GET /version`（Releases 镜像兜底）
- 部署：`node tools/cf-deploy-worker.js cloudflare/dy-announce.js dy-announce <KV_NAMESPACE_ID>`
- KV 命名空间：`dy_announce` = `b7ba22497b704fd9a40bd658a4e03ad3`；workers.dev 子域 `sharkyline`
- 改公告（比提交快）：`node tools/cf-kv.js put <nsId> current <json文件>`

---

## 三、本机推送特殊处理：github.com:443 被墙

实测：`github.com:443` 超时，但 `api.github.com:443`（50ms）、`github.com:22`、
`ssh.github.com:443` 都通。本机没有 SSH key，也不想往账号里塞新凭证，所以走
**GitHub Git Data API 推送**：

```bash
node tools/git-api-push.js        # 读 gh auth token，逐提交复制到远端并对齐本地 refs
```

原理：blobs → trees(base_tree=父提交的树) → commits → PATCH ref。

**关键坑（已在工具里处理）**：
- Windows `cmd.exe` 把 `^` 当转义符 → `<sha>^` 会被吃掉导致 diff 为空（422 Invalid tree info）。
  统一改用 `git log -1 --format=%P` / `git show -s --format=%T` 取父与树。
- **GitHub 创建提交时会去掉 message 末尾的换行**（爆破验证得出：时区、作者、时间戳都原样保留）。
  工具已主动对齐，因此本地与远端 **sha 完全一致**（`git status` 干净，不是"看起来分叉"）。
- 别用 shell 重定向 `< file` 调 `git hash-object`，Windows 下会触发 Node/libuv 断言崩溃 → 用 `execSync` 的 `input`。

---

## 四、已砍掉的两项（决策记录，2026-09-30 用户拍板）

### ② R2 做 APK CDN —— 砍掉

- Cloudflare API 直接回：`Please enable R2 through the Cloudflare Dashboard`（R2 必须控制台手动开通）；
- 即便开通，下载域名必是 `pub-xxx.r2.dev` → **与 workers.dev 同款被污染**，对国内用户无意义；
- 且 R2 的 S3 上传端点在当前网络 TLS 握手就失败，本机根本传不上去。

替代（需要时再做）：把 APK 提交到仓库的 `dist` 分支，用
`https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@dist/<apk>` 分发——
走的是**已验证可达的那条 Cloudflare 边缘**，单文件上限 20MB（APK 6.2MB 够用）。

### ③ 签名混合（Worker `/sign` 兜底）—— 砍掉

价值是反的：它只在"本地签名算法失效、急需救急"时才启用，而那一刻国内用户
**恰好连不上 workers.dev**。真要这条能力，前提是先有自有域名（CNAME 到 Cloudflare
的域名不在污染名单里，约 ¥30-80/年的便宜域名即可），届时再做才有意义。
