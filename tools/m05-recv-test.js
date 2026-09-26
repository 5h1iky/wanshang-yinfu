// M0.5 验证②补完：收消息（POST /v1/message/get_user_message，只读）
// 请求体 = IM 通用信封 + body.field_2048{version,cmd_index,read_version,source}（游标拉取）
// schema 事实来自 GetUserMsgRequset.proto descriptor（pb-schema.js 还原），实现为干净室自写
const fs = require('fs');
const path = require('path');
const pb = require('./pb.js');
const { UA } = require('./net.js');
const { createSigner } = require('./sign/abogus.js');

const COOKIE_FILE = path.join(__dirname, 'dy-cookie.txt');

function loadJar() {
  const raw = fs.readFileSync(COOKIE_FILE, 'utf8').trim();
  const jar = {};
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) jar[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return jar;
}

// 信封组装（字段编号取自 schema）
function kv(key, value) {
  return [
    { field: 1, wire: 2, value: Buffer.from(key, 'utf8') },
    { field: 2, wire: 2, value: Buffer.from(String(value), 'utf8') },
  ];
}

function buildGetUserMsg(cmd, cursor) {
  const inner = [
    { field: 1, wire: 0, value: BigInt(cursor.version || 0) },
    { field: 2, wire: 0, value: BigInt(cursor.cmdIndex || 0) },
    { field: 4, wire: 0, value: BigInt(cursor.readVersion || 0) },
    { field: 5, wire: 2, value: Buffer.from(String(cursor.source || '0'), 'utf8') },
  ];
  const nested = [{ field: 2048, wire: 2, value: pb.encodeFields(inner) }];
  const headers = [
    kv('session_aid', '6383'),
    kv('session_did', '0'),
    kv('app_name', 'douyin_pc'),
    kv('priority_region', 'cn'),
    kv('user_agent', UA),
    kv('cookie_enabled', 'true'),
    kv('browser_language', 'zh-CN'),
    kv('browser_platform', 'Win32'),
    kv('browser_name', 'Mozilla'),
    kv('browser_version', UA.replace('Mozilla/', '')),
    kv('browser_online', 'true'),
    kv('screen_width', '1920'),
    kv('screen_height', '1080'),
    kv('referer', 'https://www.douyin.com/jingxuan'),
    kv('timezone_name', 'Asia/Shanghai'),
    kv('deviceId', '0'),
    kv('is-retry', '0'),
  ].map((fields) => ({ field: 15, wire: 2, value: pb.encodeFields(fields) }));
  const env = [
    { field: 1, wire: 0, value: BigInt(cmd) },
    { field: 2, wire: 0, value: BigInt(10000 + Math.floor(Math.random() * 1000)) },
    { field: 3, wire: 2, value: Buffer.from('0.1.8', 'utf8') },
    { field: 4, wire: 2, value: Buffer.from('', 'utf8') },
    { field: 5, wire: 0, value: 3n },
    { field: 6, wire: 0, value: 0n },
    { field: 7, wire: 2, value: Buffer.from('0d50935:feat/pc-im-groupB', 'utf8') },
    { field: 8, wire: 2, value: pb.encodeFields(nested) },
    { field: 9, wire: 2, value: Buffer.from('0', 'utf8') },
    { field: 11, wire: 2, value: Buffer.from('douyin_pc', 'utf8') },
    ...headers,
    { field: 18, wire: 0, value: 4n },
    { field: 21, wire: 2, value: Buffer.from('douyin_web', 'utf8') },
    { field: 22, wire: 2, value: Buffer.from('web_sdk', 'utf8') },
  ];
  return pb.encodeFields(env);
}

async function main() {
  const jar = loadJar();
  if (!jar.sessionid) {
    console.log('❌ 缺 sessionid');
    process.exit(1);
  }
  let web = {};
  try {
    web = JSON.parse(fs.readFileSync(path.join(__dirname, 'dy-web-params.json'), 'utf8'));
  } catch (e) {}
  const msToken = jar.msToken || web.msToken || 'x';
  const fp = jar.fp || web.fp || web.verifyFp || 'verify_x';
  const verifyFp = jar.verifyFp || web.verifyFp || fp;
  const q = 'msToken=' + msToken + '&verifyFp=' + verifyFp + '&fp=' + fp;
  const url = 'https://imapi.douyin.com/v1/message/get_user_message?' + q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));

  // cmd 未知先试 203（行业资料口径），失败可换 101/611/612
  const cmd = Number(process.argv[2] || 203);
  const body = buildGetUserMsg(cmd, { version: 0, cmdIndex: 0, readVersion: 0, source: '0' });
  console.log('[收] POST /v1/message/get_user_message cmd=' + cmd + ' body=' + body.length + 'B');
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'User-Agent': UA,
      Cookie: Object.entries(jar).map(([k, v]) => k + '=' + v).join('; '),
      accept: 'application/x-protobuf',
      'content-type': 'application/x-protobuf',
      origin: 'https://www.douyin.com',
      referer: 'https://www.douyin.com/',
    },
    body,
    redirect: 'manual',
  });
  const buf = Buffer.from(await res.arrayBuffer());
  fs.writeFileSync(path.join(__dirname, 'dm-recv-resp.bin'), buf);
  console.log('HTTP ' + res.status + '，响应 ' + buf.length + 'B');
  const text = buf.toString('utf8');
  if (text.includes('decision') || text.startsWith('{')) {
    console.log('响应明文: ' + text.slice(0, 200));
    return;
  }
  // 只打印结构概要（消息内容属于隐私，不进日志）
  const strs = pb.collectStrings(buf);
  const msgs = strs.filter((s) => s.includes('aweType'));
  console.log('结构串数: ' + strs.length + '，其中含消息内容的: ' + msgs.length);
  console.log('头 12 个串: ' + strs.slice(0, 12).map((s) => s.slice(0, 24)).join(' | '));
  if (msgs.length) console.log('✅ 收消息通道打通（拉到 ' + msgs.length + ' 条内容，明细看 dm-recv-resp.bin）');
}

main().catch((e) => {
  console.error('FAIL', e);
  process.exit(1);
});
