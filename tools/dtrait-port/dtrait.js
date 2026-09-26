'use strict';
/**
 * dtrait.js — 抖音 x-tt-session-dtrait 生成链（Node.js 干净室移植）
 *
 * 规格来源（只读参考，Python 实现）：
 *   - utils/dtrait.py           → build_session_dtrait / _build_session_dtrait_header
 *                                  parse_rsa_public_key / rsa_encrypt_pkcs1v15
 *                                  builtin_trait_pubkey / aes_cbc_encrypt
 *   - utils/dtrait_features.py  → build_blob / computed_features / math_features
 *                                  murmur3_32 / _bool_buffer / parse_blob
 *   - utils/dy_util.py          → generate_csrf_token / generate_ree_key
 *   - utils/bd_ticket.py        → get_ree_key / ticket_guard_version
 *   - utils/fingerprint.py      → get_profile（设备档案字段结构）
 *
 * 头结构（协议细节，逐字节照搬）：
 *
 *   <pk1_version>_<base64(RSA_PKCS1v15(pk1, aes_key_hex))>_<base64(iv + AES128_CBC(payload))>
 *
 *   - aes_key_hex：16 字节随机数的 32 位小写十六进制**字符串**，RSA 加密的是这个字符串本身
 *   - AES：AES-128-CBC + PKCS7，密钥 = bytes.fromhex(aes_key_hex)，IV 随机 16 字节前置到密文
 *   - payload：{"dtrait":<blob>,"timestamp":<秒级 int>,"sdkVersion":<ver>,"path":<pathname>}
 *     键顺序必须是 dtrait,timestamp,sdkVersion,path；JSON 紧凑分隔符 (",",":")，非 ASCII 原样 UTF-8
 *   - 随机消耗顺序：key(16) -> iv(16) -> RSA padding
 *
 * 仅使用 Node 内置模块（crypto）；Node 18+。
 */

const crypto = require('crypto');

// ---------------------------------------------------------------------------
// 常量（照抄 dtrait.py）
// ---------------------------------------------------------------------------

const GET_TRAIT_CERT_API = '/passport/ticket_guard/get_client_cert/';
// zero.js 里写死的容器 SDK 版本，只用于拼 query
const CONTAINER_SDK_VERSION = '1.0.0.381';
const DEFAULT_TRAIT_SDK_VERSION = '1.0.0.16';

// web login bundle 内置的 d0 公钥（base64 包着 PEM 文本），常量原样复制。
const _BUILTIN_TRAIT_PK1_B64 =
  'LS0tLS1CRUdJTiBSU0EgUFVCTElDIEtFWS0tLS0tCk1JSUJDZ0tDQVFFQTQrZHZ2WTd1' +
  'TStvcGMrbkxHL0R1bVNlRm83YVZjSW0xTE8rbVVJcldwclJ6UDBhMUdwRVEKNHF0TzlN' +
  'UmYvbHdFSXgzOCs0Qlo0WE9HemV2VnR1VXZmSU9VRTdBVHRRVzdGS0pmNVBuU0xDSTYv' +
  'azB2bDFGQwpMVVNWbUVQNnFQSnJJalo0elhvcWkzeXVOWisxb2RiUkEvL0dIZ2NnU3l5' +
  'eWFMcXp3amtwV0dYb3VNWW12WXNTCnBway9mdjJFV0FCc3RQTnhXYTRFT0JDYWRUVVBr' +
  'WE5RNzZOQkVQOXh6ZkpTMjB3aUR2MW9TL3ZLdnJTVXBXY0oKbmF6a2tCdnFRYmJBcVZi' +
  'UUZURi9EUGlrcHB1NlpUNmxHSVh2SktDcmVlRmlIQTJxSzZ0UzE4U1dWSFc5QVJ6MQor' +
  'cGpCMWVxSUlZdG9oV3BUMkI0ME9DNE84dFZlQkFuYmlRSURBUUFCCi0tLS0tRU5EIFJT' +
  'QSBQVUJMSUMgS0VZLS0tLS0=';

// ---------------------------------------------------------------------------
// 随机源（randbytes 语义与 Python 的 os.urandom / 注入函数一致：(n) -> n 字节）
// ---------------------------------------------------------------------------

function defaultRandbytes(n) {
  return crypto.randomBytes(n);
}

// ---------------------------------------------------------------------------
// RSA：PKCS#1 v1.5 公钥加密（手写 BigInt modexp，与 Python 版逐字节一致）
// ---------------------------------------------------------------------------

/** 解析 DER 长度域。derLen(buf, i) -> [length, nextIndex] */
function derLen(buf, i) {
  let n = buf[i];
  i += 1;
  if (n < 0x80) return [n, i];
  const k = n & 0x7f;
  let val = 0n;
  for (let j = 0; j < k; j++) val = (val << 8n) | BigInt(buf[i + j]);
  return [Number(val), i + k];
}

/** 解析 PKCS#1 `BEGIN RSA PUBLIC KEY` PEM，返回 {n, e}（BigInt）。 */
function parseRsaPublicKey(pem) {
  const body = String(pem).trim().split(/\r?\n/).filter((l) => !l.includes('-----')).join('');
  const der = Buffer.from(body, 'base64');
  if (der[0] !== 0x30) throw new Error('不是合法的 DER SEQUENCE');
  let i = derLen(der, 1)[1];
  if (der[i] !== 0x02) throw new Error('缺少 modulus');
  let ln;
  [ln, i] = derLen(der, i + 1);
  const n = BigInt('0x' + der.subarray(i, i + ln).toString('hex'));
  i += ln;
  if (der[i] !== 0x02) throw new Error('缺少 exponent');
  let le;
  [le, i] = derLen(der, i + 1);
  const e = BigInt('0x' + der.subarray(i, i + le).toString('hex'));
  return { n, e };
}

function bitLength(x) {
  let bits = 0;
  while (x > 0n) {
    x >>= 1n;
    bits += 1;
  }
  return bits;
}

function modPow(base, exp, mod) {
  let result = 1n;
  base %= mod;
  while (exp > 0n) {
    if (exp & 1n) result = (result * base) % mod;
    base = (base * base) % mod;
    exp >>= 1n;
  }
  return result;
}

function bigIntToBuffer(x, length) {
  let hex = x.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  let buf = Buffer.from(hex, 'hex');
  if (buf.length > length) throw new Error('整数超出目标长度');
  if (buf.length < length) buf = Buffer.concat([Buffer.alloc(length - buf.length), buf]);
  return buf;
}

/**
 * RSA PKCS#1 v1.5 公钥加密（对齐 JSEncrypt 默认行为 / Python rsa_encrypt_pkcs1v15）。
 * EM = 0x00 0x02 || PS || 0x00 || M，PS 由 randbytes 逐字节过滤 0x00 填充。
 */
function rsaEncryptPkcs1v15(n, e, message, randbytes = defaultRandbytes) {
  const m = Buffer.isBuffer(message) ? message : Buffer.from(message);
  const k = (bitLength(n) + 7) >> 3;
  if (m.length > k - 11) throw new Error('明文超长');
  const psLen = k - m.length - 3;
  const ps = [];
  while (ps.length < psLen) {
    const chunk = randbytes(psLen - ps.length);
    for (const b of chunk) if (b !== 0) ps.push(b);
  }
  const em = Buffer.concat([
    Buffer.from([0x00, 0x02]),
    Buffer.from(ps.slice(0, psLen)),
    Buffer.from([0x00]),
    m,
  ]);
  const c = modPow(BigInt('0x' + em.toString('hex')), e, n);
  return bigIntToBuffer(c, k);
}

// ---------------------------------------------------------------------------
// AES：AES-128-CBC + PKCS7（手写 padding，与 Python aes_cbc_encrypt 一致）
// ---------------------------------------------------------------------------

function pkcs7Pad(data, block = 16) {
  const pad = block - (data.length % block);
  return Buffer.concat([data, Buffer.alloc(pad, pad)]);
}

function aesCbcEncrypt(key, iv, plaintext) {
  const cipher = crypto.createCipheriv('aes-128-cbc', key, iv);
  cipher.setAutoPadding(false); // padding 自己做，照搬 _pkcs7_pad
  return Buffer.concat([cipher.update(pkcs7Pad(plaintext, 16)), cipher.final()]);
}

// ---------------------------------------------------------------------------
// x-tt-session-dtrait
// ---------------------------------------------------------------------------

/** 会话材料归一化：接受 [keyHex, encKey] 或 {keyHex, encKey}。 */
function normalizeMaterial(material) {
  if (Array.isArray(material)) {
    const [keyHex, encKey] = material;
    return { keyHex, encKey };
  }
  if (material && typeof material === 'object' && 'keyHex' in material) {
    return { keyHex: material.keyHex, encKey: material.encKey };
  }
  throw new Error('sessionMaterial 必须是 [keyHex, encKey] 二元组或 {keyHex, encKey}');
}

function buildHeaderFromMaterial(path, blob, pk1Version, keyHex, encKey, sdkVersion,
  { timestamp = null, randbytes = defaultRandbytes, iv = null } = {}) {
  if (typeof keyHex !== 'string' || keyHex.length !== 32) {
    throw new Error('keyHex 必须是 16 字节随机数对应的 32 位十六进制字符串');
  }
  if (!/^[0-9a-fA-F]{32}$/.test(keyHex)) throw new Error('keyHex 不是合法十六进制');
  const key = Buffer.from(keyHex, 'hex');
  const ivBuf = iv != null ? iv : randbytes(16);
  if (ivBuf.length !== 16) throw new Error('iv 必须是 16 字节');
  const ts = Math.trunc(timestamp != null ? Number(timestamp) : Date.now() / 1000);
  if (!Number.isFinite(ts)) throw new Error('timestamp 不是合法数字');
  // 键顺序必须保持为 dtrait,timestamp,sdkVersion,path
  const payload = {
    dtrait: blob,
    timestamp: ts,
    sdkVersion: sdkVersion || DEFAULT_TRAIT_SDK_VERSION,
    path,
  };
  const plaintext = Buffer.from(JSON.stringify(payload), 'utf8');
  const cipher = aesCbcEncrypt(key, ivBuf, plaintext);
  const part1 = Buffer.from(encKey).toString('base64');
  const part2 = Buffer.concat([ivBuf, cipher]).toString('base64');
  return `${pk1Version}_${part1}_${part2}`;
}

/**
 * 拼出 x-tt-session-dtrait 头。
 *
 * @param {string} path 请求 pathname，不含 query
 * @param {string} blob 设备特征 blob（payload 的 dtrait 字段）
 * @param {string} pk1Pem PKCS#1 `BEGIN RSA PUBLIC KEY` PEM
 * @param {string} pk1Version 公钥版本（如 d0），必须与 pk1Pem 配对，成为头前缀
 * @param {object} [opts]
 * @param {string} [opts.sdkVersion] 默认 '1.0.0.16'
 * @param {number} [opts.timestamp] 秒级时间戳，缺省取当前时间并截断为整数
 * @param {function} [opts.randbytes] (n) -> Buffer，注入随机源（测试用）
 * @param {Array|object} [opts.sessionMaterial] 复用已生成的 (keyHex, encKey)：
 *        Chrome 同页面会话复用第一段（RSA 后的 AES key），每请求仅重算 IV + payload
 * @param {Buffer} [opts.iv] 指定 IV（测试用；缺省随机 16 字节）
 * @param {boolean} [opts.returnMaterial] 一并返回会话材料
 * @returns {string|{header:string,keyHex:string,encKey:Buffer}} "d0_<b64>_<b64>"
 */
function buildSessionDtrait(path, blob, pk1Pem, pk1Version, opts = {}) {
  const {
    sdkVersion = DEFAULT_TRAIT_SDK_VERSION,
    timestamp = null,
    randbytes = defaultRandbytes,
    sessionMaterial = null,
    returnMaterial = false,
  } = opts;

  if (sessionMaterial != null) {
    const { keyHex, encKey } = normalizeMaterial(sessionMaterial);
    const header = buildHeaderFromMaterial(path, blob, pk1Version, keyHex, encKey, sdkVersion,
      { timestamp, randbytes, iv: opts.iv != null ? opts.iv : null });
    return returnMaterial ? { header, keyHex, encKey } : header;
  }

  // 保持旧实现的随机消耗顺序：key -> iv -> RSA padding。
  const keyHex = randbytes(16).toString('hex'); // 小写十六进制
  const iv = opts.iv != null ? opts.iv : randbytes(16);
  const { n, e } = parseRsaPublicKey(pk1Pem);
  const encKey = rsaEncryptPkcs1v15(n, e, Buffer.from(keyHex, 'ascii'), randbytes);
  const header = buildHeaderFromMaterial(path, blob, pk1Version, keyHex, encKey, sdkVersion,
    { timestamp, randbytes, iv });
  return returnMaterial ? { header, keyHex, encKey } : header;
}

// ---------------------------------------------------------------------------
// 设备特征 blob（dtrait_features.build_blob 链）
// ---------------------------------------------------------------------------

// str_N -> tag：str_1..str_33 -> 32..64，str_34 -> 71
const STR_TAGS = {};
for (let n = 1; n <= 33; n += 1) STR_TAGS[n] = 31 + n;
STR_TAGS[34] = 71;

// 只能作为档案常量的特征（存 murmur3 结果而非原串）
const RENDER_FEATURES = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 20, 21, 22, 23, 24, 25, 26, 33];

// V8 的 Math.expm1(1)；Python 侧因 CPython 差 1 ULP 而硬编码，这里保留同一字面量
const V8_EXPM1_1 = 1.7182818284590453;

/** MurmurHash3 x86 32-bit（与 SDK / murmur3_32 一致），返回 uint32。 */
function murmur332(input, seed = 0) {
  const data = typeof input === 'string' ? Buffer.from(input, 'utf8') : Buffer.from(input);
  const c1 = 0xcc9e2d51;
  const c2 = 0x1b873593;
  let h = seed >>> 0;
  const n = Math.floor(data.length / 4) * 4;
  for (let i = 0; i < n; i += 4) {
    let k = data.readUInt32LE(i);
    k = Math.imul(k, c1) >>> 0;
    k = ((k << 15) | (k >>> 17)) >>> 0;
    k = Math.imul(k, c2) >>> 0;
    h = (h ^ k) >>> 0;
    h = ((h << 13) | (h >>> 19)) >>> 0;
    h = (Math.imul(h, 5) + 0xe6546b64) >>> 0;
  }
  let k = 0;
  let tail = false;
  for (let i = n, shift = 0; i < data.length; i += 1, shift += 8) {
    k |= data[i] << shift;
    tail = true;
  }
  if (tail) {
    k = Math.imul(k, c1) >>> 0;
    k = ((k << 15) | (k >>> 17)) >>> 0;
    k = Math.imul(k, c2) >>> 0;
    h = (h ^ k) >>> 0;
  }
  h = (h ^ data.length) >>> 0;
  h = (h ^ (h >>> 16)) >>> 0;
  h = Math.imul(h, 0x85ebca6b) >>> 0;
  h = (h ^ (h >>> 13)) >>> 0;
  h = Math.imul(h, 0xc2b2ae35) >>> 0;
  h = (h ^ (h >>> 16)) >>> 0;
  return h >>> 0;
}

/**
 * Math 指纹（str_11 / str_12）。
 * JS 的 String(x) 本身就是 ECMA-262 Number::toString，无需移植 js_number_to_str。
 * 注意：str_12 第一项按 Python 侧钉死的字面量 1.7182818284590453 输出——实测
 * Node 24 的 Math.expm1(1) 已变成 1.718281828459045（V8 数学实现跨版本漂移），
 * 与被验证过的 blob 不符，故照搬常量而非现算；其余函数现算（Python 验证时
 * CPython libm 与 V8 在这些函数上一致）。可用 profile.feature_overrides 覆盖。
 */
function mathFeatures() {
  const tan = Math.tan(-1e300);
  const atanh = Math.atanh(0.5);
  // SDK 自带的 atanh polyfill：log((1+x)/(1-x)) / 2
  const atanhPf = Math.log((1 + 0.5) / (1 - 0.5)) / 2;
  const cos = Math.cos(10.000000000123);
  const sin = Math.sin(-1e300);
  const powPi = Math.pow(Math.PI, -100);
  return {
    11: `${tan},${atanh},${atanhPf},${cos}`,
    12: `${V8_EXPM1_1},${powPi},${sin},${tan}`,
  };
}

function reqField(p, key) {
  const v = p[key];
  if (v === undefined || v === null) throw new Error(`设备档案缺少字段 ${key}`);
  return v;
}

/** 从设备档案算出可纯算的特征串。键是 str_N 的 N。 */
function computedFeatures(p) {
  const feats = { ...mathFeatures() };
  const join = (arr) => {
    if (!Array.isArray(arr)) throw new Error('设备档案字段应为数组');
    return arr.join(',');
  };
  feats[14] = `${reqField(p, 'downlink')},${reqField(p, 'effective_type')}`;
  feats[15] = `${reqField(p, 'language')},${join(reqField(p, 'languages'))}`;
  feats[16] = `${join(reqField(p, 'str16_list'))},${reqField(p, 'vendor')}`;
  feats[17] = `${reqField(p, 'platform')},${reqField(p, 'str17_tail')}`;
  feats[18] = join(reqField(p, 'str18_list'));
  feats[19] = String(reqField(p, 'ua'));
  feats[27] = `${reqField(p, 'locale')}+${reqField(p, 'timezone')}`;
  feats[28] = String(reqField(p, 'notification_permission'));
  feats[29] = `${reqField(p, 'str29_head')},${reqField(p, 'device_memory')},${reqField(p, 'hardware_concurrency')},${reqField(p, 'max_touch_points')}`;
  feats[30] = `${reqField(p, 'avail_height')},${reqField(p, 'avail_left')},${reqField(p, 'avail_top')},${reqField(p, 'avail_width')}`;
  feats[31] = `${reqField(p, 'screen_height')},${reqField(p, 'screen_width')}`;
  feats[32] = `${reqField(p, 'color_depth')},${reqField(p, 'pixel_depth')},${reqField(p, 'device_pixel_ratio')}`;
  feats[34] = String(reqField(p, 'hook_score'));
  return feats;
}

/** VM getBoolBuffer 的等价实现。bools: {序号: bool} */
function boolBuffer(bools) {
  const keys = Object.keys(bools || {}).map(Number).filter((k) => Number.isInteger(k)).sort((a, b) => a - b);
  if (keys.length === 0) return Buffer.alloc(0);
  const maxIdx = keys[keys.length - 1];
  const size = (Math.floor(maxIdx / 32) + 1) * 5;
  const buf = Buffer.alloc(size);
  for (const n of keys) {
    if (n % 32 === 0) buf[Math.floor(n / 32)] = Math.floor(n / 8) & 0xff;
    if (bools[n]) {
      const idx = 5 * (Math.floor(n / 32) + 1) - Math.floor((n % 32) / 8) - 1;
      buf[idx] |= 1 << (n % 8);
    }
  }
  return buf;
}

/**
 * 生成内层 dtrait blob（base64 字符串）。
 * @param {object} profile 设备档案（render_hashes / bools / 可算特征字段）
 * @param {number} accessType central 与 edge 均为 0
 */
function buildBlob(profile, accessType = 0) {
  const head = (((profile.reserved ?? 0) & 0x3) << 6)
    | (((profile.dtrait_type ?? 1) & 0x1) << 5)
    | (((accessType ?? 0) & 0x1) << 4)
    | ((profile.version ?? 0) & 0xf);

  const values = { ...(profile.render_hashes || {}) };
  for (const [n, s] of Object.entries(computedFeatures(profile))) {
    values[n] = murmur332(s);
  }
  // 逃生口：特征原串的最终覆盖（如 Math 指纹跨引擎版本差异时按实测串钉死）
  for (const [n, s] of Object.entries(profile.feature_overrides || {})) {
    values[n] = murmur332(s);
  }

  const body = [];
  for (let n = 1; n <= 34; n += 1) {
    if (values[n] === undefined) throw new Error(`缺少特征 str_${n}`);
    body.push(STR_TAGS[n]);
    const v = values[n] >>> 0;
    body.push((v >>> 24) & 0xff, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff);
  }

  const blob = Buffer.concat([
    Buffer.from([head]),
    boolBuffer(profile.bools || {}),
    Buffer.from(body),
  ]);
  return blob.toString('base64');
}

/** 反解 blob -> {head, bools:{n:bool}, values:{n:uint32}}。 */
function parseBlob(blobB64) {
  const raw = Buffer.from(blobB64, 'base64');
  const head = raw[0];
  const boolBuf = raw.subarray(1, 6);
  const body = raw.subarray(6);
  const bools = {};
  for (let n = 1; n <= 10; n += 1) {
    const idx = 5 * (Math.floor(n / 32) + 1) - Math.floor((n % 32) / 8) - 1;
    bools[n] = ((boolBuf[idx] >> (n % 8)) & 1) === 1;
  }
  const tag2n = {};
  for (const [n, t] of Object.entries(STR_TAGS)) tag2n[t] = Number(n);
  const values = {};
  for (let i = 0; i + 4 < body.length; i += 5) {
    values[tag2n[body[i]]] = body.readUInt32BE(i + 1);
  }
  return { head, bools, values };
}

// ---------------------------------------------------------------------------
// 内置公钥
// ---------------------------------------------------------------------------

/** dtrait.py: builtin_trait_pubkey() —— 浏览器 login bundle 内置的 d0 公钥。 */
function builtinTraitPubkey() {
  return {
    pk1: Buffer.from(_BUILTIN_TRAIT_PK1_B64, 'base64').toString('ascii'),
    pk1Version: 'd0',
    urlVersion: DEFAULT_TRAIT_SDK_VERSION,
    dtraitVersion: '0',
    source: 'builtin',
  };
}

// ---------------------------------------------------------------------------
// bd-ticket-guard ree key / web-version
// ---------------------------------------------------------------------------

/** 从私钥 PEM（SEC1/PKCS#8 EC）、KeyObject 或 32 字节 hex 取 P-256 裸公钥点。 */
function rawEcPublicPoint(privateKey) {
  let keyObj;
  if (privateKey && typeof privateKey === 'object'
    && (privateKey.type === 'private' || typeof privateKey.export === 'function')) {
    keyObj = privateKey; // crypto.KeyObject
  } else {
    const text = String(privateKey).trim();
    if (text.includes('-----BEGIN')) {
      keyObj = crypto.createPrivateKey(text);
    } else {
      // bd_ticket._load_signing_key 的 hex 分支：32 字节裸私钥
      const d = Buffer.from(text, 'hex');
      if (d.length !== 32) throw new Error('hex 私钥必须是 32 字节');
      const der = Buffer.concat([
        Buffer.from('30310201010420', 'hex'),
        d,
        Buffer.from('a00a06082a8648ce3d030107', 'hex'), // [0] prime256v1
      ]);
      keyObj = crypto.createPrivateKey({ key: der, format: 'der', type: 'sec1' });
    }
  }
  const jwk = crypto.createPublicKey(keyObj).export({ format: 'jwk' });
  const x = Buffer.from(jwk.x, 'base64url');
  const y = Buffer.from(jwk.y, 'base64url');
  if (x.length !== 32 || y.length !== 32) throw new Error('仅支持 P-256（NIST256p）');
  return Buffer.concat([Buffer.from([0x04]), x, y]);
}

/**
 * bd-ticket-guard-ree-public-key。
 *
 * 按任务书返回 "pub." 前缀格式（与 _server_pub_point 的新版 `pub.<b64 裸公钥点>`
 * 一致）；Python get_ree_key 返回裸 base64，需要原样可传 opts.prefix = ''。
 */
function generateReeKey(privateKeyPem, opts = {}) {
  const prefix = opts.prefix === undefined ? 'pub.' : opts.prefix;
  return prefix + rawEcPublicPoint(privateKeyPem).toString('base64');
}

/** zero.js `nQ`：web-version 由 ts_sign 前缀决定。 */
function ticketGuardVersion(tsSign) {
  return String(tsSign || '').startsWith('ts.1') ? 1 : 2;
}

// ---------------------------------------------------------------------------
// x-secsdk-csrf-token（dy_util.generate_csrf_token：网络换取，非纯计算）
// ---------------------------------------------------------------------------

/**
 * 向站点换取 x-secsdk-csrf-token（响应头 X-Ware-Csrf-Token）。
 *
 * 必须按子域取（token 与 csrf_session_id 绑定）。HEAD 请求头共 6 个，顺序照抄
 * 浏览器：x-secsdk-csrf-request -> referer -> user-agent -> x-secsdk-csrf-version
 * -> accept -> accept-language。
 *
 * @returns {Promise<[string|null, string|null]>} (token_1, token_2) ——
 *          X-Ware-Csrf-Token 逗号分隔的第 2 段与第 5 段（Python parts[1]/parts[4]）。
 *          失败时 (null, null)（Python 侧为 (None, None)）。
 */
async function generateCsrfToken(cookieStr, origin = 'https://www.douyin.com', opts = {}) {
  const path = opts.path || '/service/2/abtest_config/';
  const referer = opts.referer || origin + '/';
  const profile = defaultProfile();
  const headers = {
    'x-secsdk-csrf-request': '1',
    referer,
    'user-agent': opts.userAgent || profile.ua,
    'x-secsdk-csrf-version': '1.2.22',
    accept: '*/*',
    'accept-language': opts.acceptLanguage || profile.accept_language,
  };
  if (cookieStr) headers.cookie = cookieStr;
  try {
    const fetchImpl = opts.fetchImpl || globalThis.fetch;
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = controller && opts.timeoutMs !== 0
      ? setTimeout(() => controller.abort(), opts.timeoutMs || 15000)
      : null;
    const resp = await fetchImpl(origin + path, {
      method: 'HEAD',
      headers,
      redirect: 'manual',
      signal: controller ? controller.signal : undefined,
    });
    if (timer) clearTimeout(timer);
    const raw = resp.headers.get('x-ware-csrf-token');
    if (!raw) return [null, null];
    const parts = raw.split(',');
    return [parts[1] ?? null, parts[4] ?? null];
  } catch (error) {
    return [null, null];
  }
}

// ---------------------------------------------------------------------------
// 默认设备档案（Windows + Chrome 153 形态；参考 fingerprint.get_profile 结构）
// ---------------------------------------------------------------------------

/**
 * 一份可用的设备档案（buildBlob 的直接输入）。
 *
 * 渲染类特征（render_hashes）存 murmur3 值：canvas/WebGL/audio/字体像素等必须
 * 真实渲染才有原串，这里按槽位用候选原串现算（WebGL 槽用常见显卡字符串），属于
 * 合成值——结构合法，但不等于某台真实浏览器抓包，见 README「已知不确定点」。
 */
function defaultProfile() {
  const ua = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    + '(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36');
  // 渲染槽位候选原串（槽位语义为推测；4/5/6/7 用真实 WebGL 形态的常见值）
  const renderStrings = {
    1: 'canvas-2d-synthetic',
    2: 'canvas-winding-synthetic',
    3: 'canvas-webgl-dataurl-synthetic',
    4: 'Google Inc. (NVIDIA)',
    5: 'ANGLE (NVIDIA GeForce GTX 1050 Ti Direct3D11)',
    6: 'WebGL 1.0 (OpenGL ES 2.0 Chromium)',
    7: 'WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)',
    8: 'WebGL UNMASKED_VENDOR_WEBGL-synthetic',
    9: 'WebGL UNMASKED_RENDERER_WEBGL-synthetic',
    10: 'WebGL-params-synthetic',
    13: 'audio-fingerprint-synthetic',
    20: 'font-pixels-synthetic-0',
    21: 'font-pixels-synthetic-1',
    22: 'font-pixels-synthetic-2',
    23: 'css-synthetic',
    24: 'domRect-synthetic',
    25: 'svgRect-synthetic',
    26: 'mediaTypes-synthetic',
    33: 'render-extra-synthetic',
  };
  const renderHashes = {};
  for (const [n, s] of Object.entries(renderStrings)) renderHashes[n] = murmur332(s);

  return {
    // --- 形态标识（fingerprint.get_profile 同构）---
    ua,
    browser_version: '153.0.0.0',
    engine_name: 'Blink',
    engine_version: '153.0.0.0',
    os_name: 'Windows',
    os_version: '10',
    platform: 'Win32',
    vendor: 'Google Inc.',
    webgl_vendor: 'Google Inc. (NVIDIA)',
    webgl_renderer: 'ANGLE (NVIDIA GeForce GTX 1050 Ti Direct3D11)',
    accept_language: 'zh-CN,zh;q=0.9,en;q=0.8',
    // --- build_blob 可算特征字段 ---
    downlink: '10',
    effective_type: '4g',
    language: 'zh-CN',
    languages: ['zh-CN', 'zh', 'en'],
    locale: 'zh-CN',
    timezone: 'Asia/Shanghai',
    notification_permission: 'default',
    device_memory: '32',
    hardware_concurrency: '20',
    max_touch_points: '0',
    // 几何：1920x1080，任务栏 48px（fingerprint.get_profile 的几何模型）
    screen_width: '1920',
    screen_height: '1080',
    avail_width: '1920',
    avail_height: '1032',
    avail_left: '0',
    avail_top: '0',
    color_depth: '24',
    pixel_depth: '24',
    device_pixel_ratio: '1',
    // --- 语义未知的档案字段（见 README，取常见 Chrome 值）---
    str16_list: ['application/pdf', 'text/pdf', 'application/x-google-chrome-pdf'],
    str17_tail: '20030107',
    str18_list: ['PDF Viewer', 'Chrome PDF Viewer', 'Chromium PDF Viewer',
      'Microsoft Edge PDF Viewer', 'WebKit built-in PDF'],
    str29_head: '0',
    hook_score: '0',
    bools: { 1: true, 2: true, 3: true, 4: true, 5: false, 6: true, 7: true, 8: true, 9: true, 10: false },
    // --- blob 头字节字段（0x20：reserved 0 / dtrait_type 1 / access 0 / version 0）---
    reserved: 0,
    dtrait_type: 1,
    version: 0,
    // --- 渲染类特征 ---
    render_strings: renderStrings,
    render_hashes: renderHashes,
  };
}

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

module.exports = {
  // 对外接口（任务书）
  buildSessionDtrait,
  buildBlob,
  builtinTraitPubkey,
  generateCsrfToken,
  generateReeKey,
  defaultProfile,
  // 配套/测试用
  ticketGuardVersion,
  parseBlob,
  murmur332,
  parseRsaPublicKey,
  rsaEncryptPkcs1v15,
  aesCbcEncrypt,
  computedFeatures,
  mathFeatures,
  boolBuffer,
  // 常量
  DEFAULT_TRAIT_SDK_VERSION,
  CONTAINER_SDK_VERSION,
  GET_TRAIT_CERT_API,
  RENDER_FEATURES,
  STR_TAGS,
};
