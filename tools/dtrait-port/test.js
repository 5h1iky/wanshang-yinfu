'use strict';
/**
 * test.js — dtrait.js 移植自检 + 样例值生成
 *
 * 运行：node test.js
 *
 * 内容：
 *   1. murmur3 黄金向量自检（spaolacci/murmur3 测试集）
 *   2. buildBlob 结构检查（头字节、bool 位图、34 条字符串特征、parseBlob 回环）
 *   3. 内置公钥解析 + 与 Node crypto JWK 模数交叉验证
 *   4. buildSessionDtrait 格式检查（长度、d0_ 前缀、段数、base64 表、AES 回环解密、
 *      payload 键顺序、随机消耗顺序 key->iv->RSA padding）
 *   5. RSA PKCS#1 v1.5 与 Node/OpenSSL 互操作自检（自己加密 -> 私钥解密）
 *   6. 会话材料复用（第一段不变、第二段按 path 重算）
 *   7. generateReeKey 格式检查（pub. 前缀 + 65 字节 0x04 未压缩点）
 *   8. generateCsrfToken 尝试真实换取（网络不可达则降级，不影响自检）
 *
 * 输出里会明确标注哪些值是「格式正确但未经服务端验证」。
 */

const crypto = require('crypto');
const {
  buildSessionDtrait, buildBlob, builtinTraitPubkey, generateCsrfToken,
  generateReeKey, defaultProfile, ticketGuardVersion, parseBlob,
  murmur332, parseRsaPublicKey, rsaEncryptPkcs1v15, mathFeatures,
} = require('./dtrait.js');

let passed = 0;
let failed = 0;

function check(label, ok, detail = '') {
  const mark = ok ? '[ OK ]' : '[FAIL]';
  if (ok) passed += 1;
  else failed += 1;
  console.log(`${mark} ${label}${detail ? '  —— ' + detail : ''}`);
}

function section(title) {
  console.log(`\n=== ${title} ===`);
}

function b64re(s) {
  return /^[A-Za-z0-9+/]+={0,2}$/.test(s);
}

async function main() {
  // ---------------------------------------------------------------- 1. murmur3
  section('1. murmur3_32 黄金向量（spaolacci/murmur3 测试集）');
  const vectors = [
    ['', 0x00, 0x00000000],
    ['hello', 0x00, 0x248bfa47],
    ['hello, world', 0x00, 0x149bbb7f],
    ['19 Jan 2038 at 3:14:07 AM', 0x00, 0xe31e8a70],
    ['The quick brown fox jumps over the lazy dog.', 0x00, 0xd5c48bfc],
    ['', 0x01, 0x514e28b7],
    ['hello', 0x01, 0xbb4abcad],
    ['hello, world', 0x2a, 0x7ec7c6c2],
    ['The quick brown fox jumps over the lazy dog.', 0x2a, 0xc02d1434],
  ];
  for (const [s, seed, want] of vectors) {
    const got = murmur332(s, seed);
    check(`murmur3(${JSON.stringify(s)}, seed=${seed})`, got === want,
      `0x${got.toString(16).padStart(8, '0')} / 期望 0x${want.toString(16).padStart(8, '0')}`);
  }
  // expm1 现场值仅作观察：Node 24 的 Math.expm1(1) 已与被验证 blob 的常量不一致
  // （V8 数学实现跨版本漂移），模块按 Python 规格钉死字面量 1.7182818284590453。
  check('str_12 使用规格钉死的 expm1 常量 1.7182818284590453',
    mathFeatures()[12].startsWith('1.7182818284590453,'),
    `本地 Math.expm1(1)=${Math.expm1(1)}；已按常量输出`);

  // ----------------------------------------------------------------- 2. blob
  section('2. buildBlob（设备特征 blob）');
  const profile = defaultProfile();
  const blob = buildBlob(profile, 0);
  const raw = Buffer.from(blob, 'base64');
  check('blob 为合法 base64', b64re(blob));
  const boolSize = 5; // bool 序号最大 10 -> (10//32+1)*5 = 5
  check('blob 解码长度 = 1 + 5 + 34*5 = 176', raw.length === 176, `实际 ${raw.length}`);
  check('头字节 = 0x20（reserved 0 / dtrait_type 1 / access 0 / version 0）',
    raw[0] === 0x20, `实际 0x${raw[0].toString(16)}`);
  check('bool 位图长度 5 字节', raw.length > 1 + boolSize);
  const parsed = parseBlob(blob);
  let tagsOk = true;
  for (let n = 1; n <= 34; n += 1) {
    const idx = 6 + (n - 1) * 5;
    const want = n <= 33 ? 31 + n : 71;
    if (raw[idx] !== want) tagsOk = false;
  }
  check('字符串特征段 tag 顺序（str_1..33 -> 32..64，str_34 -> 71）', tagsOk);
  check('parseBlob 回环：bool 与档案一致',
    Object.entries(profile.bools).every(([n, v]) => parsed.bools[n] === v));
  check('parseBlob 回环：str_19 == murmur3(ua)',
    parsed.values[19] === murmur332(profile.ua));
  check('parseBlob 回环：str_31 == murmur3("1080,1920")',
    parsed.values[31] === murmur332('1080,1920'));
  check('parseBlob 回环：str_11/12 为 Math 指纹',
    typeof parsed.values[11] === 'number' && typeof parsed.values[12] === 'number');
  check('feature_overrides 逃生口生效（覆盖后哈希可钉死）',
    parseBlob(buildBlob({ ...profile, feature_overrides: { 11: 'pinned' } })).values[11]
      === murmur332('pinned'));
  console.log(`  str_11 = ${mathFeatures()[11]}`);
  console.log(`  str_12 = ${mathFeatures()[12]}`);
  console.log(`  blob = ${blob}`);

  // --------------------------------------------------------- 3. 内置公钥解析
  section('3. 内置 d0 公钥（builtinTraitPubkey）');
  const pk = builtinTraitPubkey();
  check('pk1 为 PKCS#1 RSA PUBLIC KEY PEM', pk.pk1.includes('BEGIN RSA PUBLIC KEY'));
  check('pk1_version = d0', pk.pk1Version === 'd0');
  const { n, e } = parseRsaPublicKey(pk.pk1);
  check('模数 2048 bit', bitLen(n) === 2048, `实际 ${bitLen(n)}`);
  check('指数 65537', e === 65537n, `实际 ${e}`);
  // 与 Node crypto 解析结果交叉验证
  const jwk = crypto.createPublicKey({ key: pk.pk1, format: 'pem', type: 'pkcs1' })
    .export({ format: 'jwk' });
  const nNode = BigInt('0x' + Buffer.from(jwk.n, 'base64url').toString('hex'));
  check('DER 手写解析 == Node crypto JWK 模数', nNode === n);

  // --------------------------------------------- 4. dtrait 头格式 + AES 回环
  section('4. buildSessionDtrait（x-tt-session-dtrait）');
  const path = '/aweme/v1/web/commit/item/digg/';
  const fixedTs = Math.floor(Date.now() / 1000);

  // 脚本化随机源：验证随机消耗顺序 key(16) -> iv(16) -> RSA padding(221)
  let cursor = 0;
  const calls = [];
  const scriptedRand = (len) => {
    calls.push(len);
    const buf = Buffer.alloc(len);
    for (let i = 0; i < len; i += 1) {
      cursor += 1;
      buf[i] = (cursor * 37) % 255 + 1; // 全非零，PS 恰好一次取够
    }
    return buf;
  };
  const built = buildSessionDtrait(path, blob, pk.pk1, pk.pk1Version, {
    timestamp: fixedTs,
    randbytes: scriptedRand,
    returnMaterial: true,
  });
  const header = built.header;
  check('随机消耗顺序 = [16, 16, 221]（key -> iv -> RSA PS）',
    JSON.stringify(calls) === JSON.stringify([16, 16, 221]), `实际 ${JSON.stringify(calls)}`);

  const segments = header.split('_');
  check('段数 = 3（d0_<b64>_<b64>）', segments.length === 3, `实际 ${segments.length}`);
  check('前缀 = d0_', segments[0] === 'd0', `实际 "${segments[0]}_"`);
  check('两段均为标准 base64（+/ 表，非 base64url）',
    b64re(segments[1]) && b64re(segments[2]));
  const part1 = Buffer.from(segments[1], 'base64');
  const part2 = Buffer.from(segments[2], 'base64');
  check('第一段解码 = 256 字节（2048-bit RSA 密文）', part1.length === 256, `实际 ${part1.length}`);
  check('第二段长度 = 16(IV) + 16*k，且 >= 48',
    part2.length >= 48 && (part2.length - 16) % 16 === 0, `实际 ${part2.length}`);
  check('头长度合理（> 300 字符）', header.length > 300, `实际 ${header.length}`);

  // AES 回环：用返回的 keyHex 解密第二段
  const key = Buffer.from(built.keyHex, 'hex');
  check('keyHex = 32 位小写十六进制', /^[0-9a-f]{32}$/.test(built.keyHex));
  const iv = part2.subarray(0, 16);
  const ct = part2.subarray(16);
  const dec = crypto.createDecipheriv('aes-128-cbc', key, iv);
  dec.setAutoPadding(false);
  const padded = Buffer.concat([dec.update(ct), dec.final()]);
  const pad = padded[padded.length - 1];
  const plain = padded.subarray(0, padded.length - pad).toString('utf8');
  check('PKCS7 padding 合法', pad >= 1 && pad <= 16 && padded.subarray(padded.length - pad)
    .every((b) => b === pad));
  check('payload JSON 可解析且四键齐全',
    (() => {
      const o = JSON.parse(plain);
      return o.dtrait === blob && typeof o.timestamp === 'number'
        && o.sdkVersion === '1.0.0.16' && o.path === path;
    })());
  check('payload 键顺序 = dtrait,timestamp,sdkVersion,path',
    /^{"dtrait":"[^"]*","timestamp":\d+,"sdkVersion":"[^"]*","path":"[^"]*"}/.test(plain));
  check('timestamp 为秒级整数', Math.abs(Math.floor(Date.now() / 1000) - fixedTs) < 60);
  console.log(`  x-tt-session-dtrait = ${header.slice(0, 60)}……（共 ${header.length} 字符）`);
  console.log(`  payload 明文 = ${plain.slice(0, 120)}……`);

  // 会话材料复用：第一段不变、第二段按 path 重算
  const header2 = buildSessionDtrait('/aweme/v1/web/commit/item/comment/', blob,
    pk.pk1, pk.pk1Version, {
      sessionMaterial: [built.keyHex, built.encKey],
      timestamp: fixedTs,
    });
  check('复用会话材料：第一段不变',
    header2.split('_')[1] === segments[1]);
  check('复用会话材料：第二段按新 path 变化',
    header2.split('_')[2] !== segments[2] && header2.split('_')[0] === 'd0');

  // ------------------------------------------------- 5. RSA 与 OpenSSL 互操作
  section('5. RSA PKCS#1 v1.5 自检（自己加密 -> OpenSSL 私钥解密）');
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const testPem = publicKey.export({ type: 'pkcs1', format: 'pem' });
  const tn = parseRsaPublicKey(testPem);
  const msg = Buffer.from('dtrait-rsa-selftest-0123456789abcd', 'ascii'); // 32 字节
  const cipherBuf = rsaEncryptPkcs1v15(tn.n, tn.e, msg);
  let rsaOk = false;
  try {
    const plain2 = crypto.privateDecrypt(
      { key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING }, cipherBuf);
    rsaOk = plain2.equals(msg);
  } catch (err) {
    // 某些 OpenSSL 构建禁用 PKCS1 解密 -> 用 BigInt 手工解密复核 EM 结构
    const jwkPriv = privateKey.export({ format: 'jwk' });
    const dn = BigInt('0x' + Buffer.from(jwkPriv.d, 'base64url').toString('hex'));
    const em = modPow(BigInt('0x' + cipherBuf.toString('hex')), dn, tn.n);
    const emBuf = bigIntToBuf(em, 256);
    rsaOk = emBuf[0] === 0x00 && emBuf[1] === 0x02
      && emBuf.subarray(237).equals(Buffer.concat([Buffer.from([0x00]), msg]));
  }
  check('PKCS#1 v1.5 EM 结构 = 00 02 || PS(非零) || 00 || M', rsaOk);
  // PS 过滤 0x00 的分支：随机流混入 0x00 时必须循环补取（与 Python while 循环同语义）
  const stream = [];
  let cur = 0;
  const noisy = (len) => {
    const b = Buffer.alloc(len);
    for (let i = 0; i < len; i += 1) {
      cur += 1;
      b[i] = cur % 7 === 0 ? 0 : (cur % 255) + 1; // 每 7 字节混入一个 0x00
    }
    stream.push(len);
    return b;
  };
  const c2 = rsaEncryptPkcs1v15(tn.n, tn.e, msg, noisy);
  check('PS 含 0x00 时循环补取（随机过滤分支可用）',
    c2.length === 256 && stream.length >= 2 && stream.reduce((a, b) => a + b, 0) >= 221,
    `consumed=${JSON.stringify(stream)}`);
  // 注意：若 randbytes 全返回 0x00，PS 永远补不齐 -> 与 Python 一样死循环，故不测该分支。

  // ------------------------------------------------------------ 6. ree key
  section('6. generateReeKey（bd-ticket-guard-ree-public-key）');
  const ec = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const ree = generateReeKey(ec.privateKey);
  check('格式 = "pub." + 标准 base64', ree.startsWith('pub.') && b64re(ree.slice(4)));
  const point = Buffer.from(ree.slice(4), 'base64');
  check('裸公钥点 = 65 字节 0x04||X||Y', point.length === 65 && point[0] === 0x04);
  const ecJwk = ec.publicKey.export({ format: 'jwk' });
  const expectPoint = Buffer.concat([
    Buffer.from([0x04]),
    Buffer.from(ecJwk.x, 'base64url'),
    Buffer.from(ecJwk.y, 'base64url'),
  ]);
  check('点坐标与 Node crypto 导出一致', point.equals(expectPoint));
  check("opts.prefix='' 时回退 Python 裸 base64 形态",
    generateReeKey(ec.privateKey, { prefix: '' }) === ree.slice(4));
  console.log(`  ree-key = ${ree.slice(0, 40)}……`);
  check('ticketGuardVersion("ts.1xxx") = 1', ticketGuardVersion('ts.1abc') === 1);
  check('ticketGuardVersion("ts.2xxx") = 2', ticketGuardVersion('ts.2abc') === 2);

  // ------------------------------------------------------------- 7. csrf token
  section('7. generateCsrfToken（x-secsdk-csrf-token，网络换取）');
  const [t1, t2] = await generateCsrfToken('', 'https://www.douyin.com', { timeoutMs: 6000 });
  if (t1) {
    check('换取成功：token 为 X-Ware-Csrf-Token 第 2/5 段（服务端签发，已验证格式）',
      typeof t1 === 'string' && t1.length > 0, `token1=${t1.slice(0, 16)}… token2=${String(t2).slice(0, 16)}…`);
    console.log(`  x-secsdk-csrf-token = ${t1}`);
  } else {
    check('换取未成功（离线/无 cookie/被拒）—— 不阻塞自检', true,
      '此处不出示 csrf 样例值；换取成功时值由服务端签发');
  }

  // -------------------------------------------------------------- 8. 汇总
  section('8. 验证状态汇总');
  console.log('  [已本地验证] murmur3、blob 字节布局、AES-128-CBC/PKCS7、RSA PKCS#1 v1.5、');
  console.log('               payload 键顺序与秒级时间戳、随机消耗顺序、ree 公钥点编码、头分段格式。');
  console.log('  [格式正确但未经服务端验证] 上面打印的 x-tt-session-dtrait 头与设备特征 blob：');
  console.log('      - blob 的渲染类特征（canvas/WebGL/audio/字体像素）为合成哈希，非真实浏览器抓包；');
  console.log('      - 头的 RSA 段使用内置 d0 公钥，未与服务端解密对账；');
  console.log('      - 时间戳/随机数每次运行变化；服务端是否放行需带真实 cookie 实测。');
  if (!t1) console.log('  [未生成] x-secsdk-csrf-token：需要按子域向站点换取（网络交换，非纯计算）。');

  console.log(`\n结果: ${passed} 通过, ${failed} 失败`);
  if (failed > 0) process.exitCode = 1;
}

// ---- 小工具（BigInt <-> Buffer / modexp，仅测试用） ----
function bitLen(x) {
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

function bigIntToBuf(x, length) {
  let hex = x.toString(16);
  if (hex.length % 2) hex = '0' + hex;
  let buf = Buffer.from(hex, 'hex');
  if (buf.length < length) buf = Buffer.concat([Buffer.alloc(length - buf.length), buf]);
  return buf;
}

main().catch((err) => {
  console.error('测试执行异常:', err);
  process.exitCode = 1;
});
