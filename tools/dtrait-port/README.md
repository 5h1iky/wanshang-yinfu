# dtrait-port — 抖音 x-tt-session-dtrait 生成链（Node.js 干净室移植）

把 Python 版 `dy-spider-src` 的 dtrait 生成链移植成纯 Node.js 模块。
Python 源码仅作规格参考；实现为干净室重写，协议细节（AES 模式/填充、RSA 填充、
密钥派生、字段顺序、时间戳单位、随机数长度）逐一照搬。

- 运行环境：Node 18+（本机验证于 v24），只用内置 `crypto`，无第三方依赖。
- 自检：`node test.js`（48 项检查，全部通过）。

## 产出文件

| 文件 | 说明 |
| --- | --- |
| [dtrait.js](dtrait.js) | 模块本体（全部纯算 + 一个可选网络换取函数） |
| [test.js](test.js) | 自检 + 样例值生成（dtrait 头 / csrf token / ree key） |
| [README.md](README.md) | 本文档 |

## 快速上手

```js
const {
  buildSessionDtrait, buildBlob, builtinTraitPubkey,
  generateCsrfToken, generateReeKey, defaultProfile,
} = require('./dtrait.js');

const profile = defaultProfile();                       // Windows Chrome 153 形态设备档案
const blob = buildBlob(profile, 0);                     // 设备特征 blob（base64）
const pk = builtinTraitPubkey();                        // 内置 d0 公钥
const header = buildSessionDtrait(                      // x-tt-session-dtrait 头
  '/aweme/v1/web/commit/item/digg/', blob, pk.pk1, pk.pk1Version,
);
```

## 接口说明

### `buildSessionDtrait(path, blob, pk1Pem, pk1Version, opts?) → string`

拼出 `x-tt-session-dtrait` 头，格式 `"d0_<b64>_<b64>"`：

```
<pk1_version>_<base64(RSA_PKCS1v15(pk1, aes_key_hex))>_<base64(iv + AES128_CBC(payload))>
```

- `path`：请求 pathname，不含 query。
- `blob`：设备特征 blob（payload 的 `dtrait` 字段）。
- `pk1Pem`：PKCS#1 `BEGIN RSA PUBLIC KEY` PEM。
- `pk1Version`：公钥版本（如 `d0`），成为头前缀，必须与 `pk1Pem` 配对。
- `opts.sdkVersion`：默认 `'1.0.0.16'`。
- `opts.timestamp`：秒级时间戳，缺省 `Date.now()/1000` 截断为整数（与 Python
  `int(time.time())` 一致）。
- `opts.randbytes`：`(n) => Buffer` 随机源注入（测试用），默认 `crypto.randomBytes`。
- `opts.sessionMaterial`：`[keyHex, encKey]` 或 `{keyHex, encKey}`。Chrome 同页面
  会话复用第一段（RSA 后的 AES key），每请求只重生成 IV 并按 pathname 重算
  第二段——传入上次 `returnMaterial` 的材料即可。
- `opts.iv`：指定 16 字节 IV（测试用）。
- `opts.returnMaterial`：为 true 时返回 `{header, keyHex, encKey}` 而非纯字符串。

协议细节（与 Python 逐字节对齐）：

- `aes_key_hex`：16 字节随机数的 **32 位小写十六进制字符串**；RSA 加密的是这个
  ASCII 字符串本身（32 字节），不是那 16 个原始字节。
- RSA：PKCS#1 v1.5（EM = `00 02 || PS || 00 || M`），PS 取随机非零字节；
  签名算法等价 JSEncrypt 默认行为。
- AES：AES-128-CBC + PKCS7（不足一块也补满一块），密钥 `bytes.fromhex(key_hex)`，
  IV 随机 16 字节并前置到密文。
- payload：`{"dtrait":<blob>,"timestamp":<秒级int>,"sdkVersion":<ver>,"path":<pathname>}`，
  键顺序必须为 `dtrait,timestamp,sdkVersion,path`，JSON 紧凑分隔符 `(",",":")`，
  非 ASCII 原样 UTF-8（等价 Python `ensure_ascii=False`）。
- 随机消耗顺序：`key(16) -> iv(16) -> RSA padding`（与旧实现一致，测试已断言）。
- 两段均用**标准 base64**（`+/` 表、`=` 补位），不是 base64url。

### `buildBlob(profile, accessType = 0) → string`

生成内层设备特征 blob（base64）。字节布局（逆自 `@byted/uc-secure-dtrait-core`）：

```
[1 字节头][bool 位图][数值段][字符串特征段]
头字节 = (reserved << 6) | (dtraitType << 5) | (accessType << 4) | version
bool 位图 = Uint8Array((floor(maxIdx/32)+1) * 5)，第 n 个 bool 落在
  buf[5*(floor(n/32)+1) - floor((n%32)/8) - 1] 的第 n%8 位
字符串特征段 = 34 条 × 5 字节 = [tag][murmur3 大端 4 字节]
  tag：str_1..str_33 -> 32..64，str_34 -> 71
```

- `profile.render_hashes`：渲染类特征（str_1..10/13/20..26/33）的 murmur3 值。
- `profile.bools`：`{序号: bool}`。
- 其余字段（`downlink`/`effective_type`/`language`/`languages`/`str16_list`/`vendor`/
  `platform`/`str17_tail`/`str18_list`/`ua`/`locale`/`timezone`/
  `notification_permission`/`str29_head`/`device_memory`/`hardware_concurrency`/
  `max_touch_points`/`avail_*`/`screen_*`/`color_depth`/`pixel_depth`/
  `device_pixel_ratio`/`hook_score`）现算成特征串再 murmur3。
- `profile.feature_overrides`（可选）：`{str序号: 特征原串}`，最后覆盖对应哈希
  （逃生口，见「已知不确定点」）。
- `accessType`：central/edge 实测均为 0。
- 缺字段会抛错（Python 侧是 KeyError；JS 模板串会把 `undefined` 悄悄拼进去，
  故显式校验）。

### `builtinTraitPubkey() → {pk1, pk1Version, urlVersion, dtraitVersion, source}`

浏览器 web login bundle 内置的 d0 公钥。常量 `_BUILTIN_TRAIT_PK1_B64` 原样复制自
`dtrait.py`，`pk1` 为 base64 解出的 PEM 文本（PKCS#1 `BEGIN RSA PUBLIC KEY`，
RSA-2048，e=65537）。对应 Python `builtin_trait_pubkey()` 的 dict（键名 camelCase）。

### `generateCsrfToken(cookieStr, origin?, opts?) → Promise<[token1, token2]>`

向站点换取 `x-secsdk-csrf-token`。对应 Python `dy_util.generate_csrf_token`——
注意这**不是纯计算**，是网络换取（HEAD 请求拿响应头），因此是 async：

- HEAD `<origin><path>`（默认 `/service/2/abtest_config/`），请求头恰好 6 个、
  顺序照抄浏览器：`x-secsdk-csrf-request → referer → user-agent →
  x-secsdk-csrf-version → accept → accept-language`，不带 `sec-ch-ua*`/`cache-control`/
  `pragma`。
- 返回响应头 `X-Ware-Csrf-Token` 按逗号切分后的**第 2 段与第 5 段**
  （Python `parts[1]` / `parts[4]`），与 Python 同为二元组形态。
- 任何失败（离线/无 cookie/无响应头）返回 `[null, null]`（Python 为 `(None, None)`）。
- 必须按子域取：token 与 `csrf_session_id` 绑定，creator 域要用 creator 域换。
- `opts`：`path`/`referer`/`userAgent`/`acceptLanguage`/`timeoutMs`/`fetchImpl`。

### `generateReeKey(privateKeyPem, opts?) → string`

`bd-ticket-guard-ree-public-key` 头值。对应 `dy_util.generate_ree_key` →
`bd_ticket.get_ree_key`：EC P-256 私钥 → 未压缩公钥点 `0x04 || X || Y`（65 字节）
→ base64。本模块按任务书输出 `"pub." + base64` 前缀格式（与 `bd_ticket.
_server_pub_point` 新版 `pub.<b64 裸公钥点>` 格式一致）；需要 Python 原样的裸
base64 时传 `opts.prefix = ''`。入参接受 SEC1/PKCS#8 PEM、`crypto.KeyObject`
或 32 字节 hex 裸私钥（后两者对应 Python `_load_signing_key` 的两种形态）。

### `defaultProfile() → object`

一份可用的设备档案（Windows + Chrome 153 形态），可直接喂 `buildBlob`。字段结构
参考 `fingerprint.get_profile()` + `dtrait_features` 的 profile 契约：

- UA/几何按 `get_profile` 的模型：1920×1080，任务栏 48px（avail 1920×1032），
  `platform=Win32`，`vendor=Google Inc.`。
- WebGL/显卡用常见值：`webgl_vendor = "Google Inc. (NVIDIA)"`，
  `webgl_renderer = "ANGLE (NVIDIA GeForce GTX 1050 Ti Direct3D11)"`。
- 渲染类特征 `render_hashes` 由 `render_strings`（槽位候选原串）现算 murmur3，
  其中 WebGL 槽用上述真实形态字符串，其余槽为合成串（见不确定点）。
- `reserved=0, dtrait_type=1, version=0` → 头字节 `0x20`。

### 附带导出

- `ticketGuardVersion(tsSign)`：`bd_ticket.ticket_guard_version`——`ts.1` 前缀 → 1，否则 2。
- `parseBlob(blobB64)`、`murmur332(s, seed)`、`parseRsaPublicKey(pem)`、
  `rsaEncryptPkcs1v15(n, e, msg, randbytes)`、`aesCbcEncrypt`、`computedFeatures`、
  `mathFeatures`、`boolBuffer`：协议构件，供测试与二次组合。
- 常量：`DEFAULT_TRAIT_SDK_VERSION='1.0.0.16'`、`CONTAINER_SDK_VERSION='1.0.0.381'`、
  `GET_TRAIT_CERT_API`、`RENDER_FEATURES`、`STR_TAGS`。

## 移植对照表

| Node（dtrait.js） | Python 来源 |
| --- | --- |
| `buildSessionDtrait` | `dtrait.build_session_dtrait` + `_build_session_dtrait_header` |
| `buildBlob` | `dtrait_features.build_blob`（含 `computed_features`/`math_features`/`_bool_buffer`/`murmur3_32`） |
| `builtinTraitPubkey` | `dtrait.builtin_trait_pubkey`（`_BUILTIN_TRAIT_PK1_B64` 常量原样复制） |
| `generateCsrfToken` | `dy_util.generate_csrf_token`（网络换取，二元组返回格式一致） |
| `generateReeKey` | `dy_util.generate_ree_key` → `bd_ticket.get_ree_key`（+ 任务书要求的 `pub.` 前缀） |
| `ticketGuardVersion` | `bd_ticket.ticket_guard_version` |
| `defaultProfile` | `fingerprint.get_profile` 的结构 + `dtrait_features` 的 profile 契约 |
| `parseBlob` / `murmur332` | `dtrait_features.parse_blob` / `murmur3_32` |
| `parseRsaPublicKey` / `rsaEncryptPkcs1v15` | `dtrait.parse_rsa_public_key` / `rsa_encrypt_pkcs1v15` |
| `aesCbcEncrypt` | `dtrait.aes_cbc_encrypt` + `_pkcs7_pad` |
| `normalizeMaterial` / `buildHeaderFromMaterial` | `build_session_dtrait` 的 `session_material` 分支 |

未移植（纯网络接口，非本次范围）：`dtrait.fetch_trait_pubkey`、
`bd_ticket.fetch_server_cert`、`generate_bd_ticket_client_data`。

## 关键实现决策

1. **RSA PKCS#1 v1.5 手写实现**（BigInt 平方乘 + 手工 DER 解析），不用
   `crypto.publicEncrypt`：要与 Python/JSEncrypt 逐字节同语义——包括 PS 的
   `randbytes` 过滤 0x00 循环、随机消耗次数，以及 PKCS#1 PEM 直接解析。
   测试用「自己加密 → OpenSSL 私钥解密」互操作验证 EM 结构。
2. **AES-128-CBC 手写 PKCS7**（`setAutoPadding(false)`）：与 Python
   `_pkcs7_pad`（对齐长度也补满 16 字节）严格一致。
3. **JSON 键顺序用对象字面量插入序**保证 `dtrait,timestamp,sdkVersion,path`；
   紧凑分隔符；`timestamp` 秒级、`Math.trunc` 截断（Python `int()`）。
   注：`dtrait.py` docstring 说 sdkVersion「已废弃」，但代码与示例 payload 均含
   该键且明确要求四键顺序——以代码为准。
4. **base64 一律标准表**（`Buffer.toString('base64')`），ree/csrf 也不用 base64url；
   `key_hex` 用 `Buffer.toString('hex')` 保证小写。
5. **`String(x)` 即 JS Number::toString**，无需移植 Python 的 `js_number_to_str`
   仿真；但 `Math.expm1(1)` 不能现算——见不确定点 1，按 Python 钉死的字面量
   `1.7182818284590453` 输出。
6. **murmur3 用公开黄金向量自检**（spaolacci/murmur3 测试集 9 组，覆盖 4 字节
   块、1/3 字节尾、多 seed）。
7. **随机消耗顺序 `key -> iv -> RSA padding`** 在测试里用脚本化随机源断言为
   `[16, 16, 221]`（2048 位 key、32 字节明文 → PS 221 字节）。
8. **`generateCsrfToken` 保持 Python 的容错语义**：任何异常 → `[null, null]`，
   不抛错；请求头个数与顺序照抄实录（6 个）。

## 已知不确定点

1. **`Math.expm1(1)` 跨引擎漂移（实测）**：Node 24 的 `Math.expm1(1)` 现为
   `1.718281828459045`，而 Python 侧验证 blob 时钉死的 V8 值是
   `1.7182818284590453`（差 1 ULP）。模块照搬 Python 常量（`str_12` 第一项）。
   同理，`str_11`/`str_12` 其余项（tan/atanh/log/cos/sin/pow）虽按规格现算，
   但无法在本机用 Python 复算对账；若要与某个抓包 blob 做逐字节 parity，
   请把实测特征串用 `profile.feature_overrides` 钉死。
2. **渲染类特征槽位语义未知**：`RENDER_FEATURES`（str_1..10/13/20..26/33）只知
   属于 canvas/WebGL/audio/字体像素/css/domRect/svgRect/mediaTypes 类，
   各槽与原串的对应关系是推测。`defaultProfile` 中 WebGL 槽（4/5/6/7）用
   GTX 1050 Ti 的常见 ANGLE 字符串现算，其余槽用合成串——**结构合法但不等于
   任何真实浏览器抓包**。要过风控需换成真实抓包的 `render_hashes`（可用 Python
   侧 `profile_from_blob` 的思路：从抓到的 blob 反解）。
3. **`defaultProfile` 的语义未知字段是猜测值**：`str16_list`（按 Chrome MIME 类型
   猜）、`str17_tail`（按 `productSub=20030107` 猜）、`str18_list`（按 pluginNames
   猜）、`str29_head`（无依据，填 `"0"`）、`hook_score`（填 `"0"`）、
   `bools 1..10`（布尔特征序号语义未知，取常见组合）。这些值只保证格式/拼接
   形态正确，不代表真实设备。
4. **`generateReeKey` 的 `pub.` 前缀**：Python `get_ree_key` 返回裸 base64；
   任务书要求 `"pub."` 前缀（对齐 `bd-ticket-guard-ree-public-key` 头的新版
   下发格式）。线上头值形态未直接抓包验证，故保留 `opts.prefix` 开关。
5. **`generateCsrfToken` 是网络换取**：不是纯计算，返回值来自服务端
   `X-Ware-Csrf-Token`（第 2/5 段）；离线返回 `[null, null]`。token 与
   `csrf_session_id` 绑定，必须按子域换。
6. **整体未经服务端验证**：`node test.js` 保证的是「格式正确」（长度、`d0_` 前缀、
   段数、base64 表、AES/RSA 回环、payload 键序、blob 字节布局）。头能否被
   passport 接受，还取决于真实 cookie 会话、`render_hashes` 的真实性、以及
   RSA 段与同会话其他请求材料的配对，需带真实会话实测。
7. **公钥版本轮换**：内置 key 为 `d0`；服务端轮换到 `d1` 时需走
   `get_client_cert?type=trait` 换新 key（未移植），并保持 `pk1_version` 与
   公钥配对。

## 测试说明（`node test.js`）

1. murmur3 黄金向量（9 组）+ expm1 常量检查
2. blob 结构：长度 176、头字节 0x20、tag 顺序、`parseBlob` 回环、`feature_overrides`
3. 内置公钥：2048 位/e=65537、手写 DER 解析 == Node crypto JWK 模数
4. dtrait 头：段数/`d0_` 前缀/base64 表/长度、随机消耗顺序 `[16,16,221]`、
   AES 回环解密、PKCS7 合法性、payload 四键与键顺序、秒级时间戳、会话材料复用
5. RSA PKCS#1 v1.5 与 OpenSSL 互操作（加密→私钥解密）、PS 过滤 0x00 分支
6. ree key：`pub.` 前缀、65 字节 `04||X||Y`、与 Node crypto 点坐标一致
7. csrf token：尝试真实换取（成功则打印服务端签发值，失败不阻塞）
8. 汇总：明确标注哪些值「格式正确但未经服务端验证」
