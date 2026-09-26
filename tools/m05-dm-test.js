// M0.5 验证②：私信收发实测 v2（完整正确链路）
// 变更：①会话 ticket 用创建响应现发的 ②identity_security_token 现领（严禁用死值）
//       ③identity_security_aid 置空串 ④剥掉模板顶层 23/24/25 遗留信封（外来静态令牌）
// 用法: node m05-dm-test.js <对方主页 sec_uid> [消息文本]
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const pb = require('./pb.js');
const { UA } = require('./net.js');
const { createSigner } = require('./sign/abogus.js');

const COOKIE_FILE = path.join(__dirname, 'dy-cookie.txt');
const JAVA = path.join(__dirname, 'dyapi-src', 'src', 'main', 'java', 'com', 'dy_web_api', 'sdk', 'message', 'handler', 'MessageSender.java');

function loadJar() {
  if (!fs.existsSync(COOKIE_FILE)) {
    console.log('❌ 缺少 ' + COOKIE_FILE);
    process.exit(1);
  }
  let raw = fs.readFileSync(COOKIE_FILE, 'utf8').trim();
  const jar = {};
  if (/curl\s/i.test(raw)) {
    const m = raw.match(/cookie:\s*([^'"\n]+)/i) || raw.match(/(?:-b|--cookie)\s+['"]?([^'"\n]+)/i);
    if (m) raw = m[1];
  }
  if (raw.startsWith('{')) {
    Object.assign(jar, JSON.parse(raw));
  } else {
    for (const part of raw.split(';')) {
      const i = part.indexOf('=');
      if (i > 0) jar[part.slice(0, i).trim()] = part.slice(i + 1).trim();
    }
  }
  return jar;
}

function template(name) {
  const src = fs.readFileSync(JAVA, 'utf8');
  return Buffer.from(src.match(new RegExp(name + '\\s*=\\s*"([^"]+)"'))[1], 'base64');
}

function uuid() {
  return crypto.randomUUID();
}

function absorb(res, jar) {
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const c of sc) {
    const kv = c.split(';')[0];
    const i = kv.indexOf('=');
    if (i > 0) jar[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
  }
  const ms = res.headers.get('x-ms-token');
  if (ms) {
    jar.msToken = ms;
    try {
      const p = path.join(__dirname, 'dy-web-params.json');
      const web = JSON.parse(fs.readFileSync(p, 'utf8'));
      web.msToken = ms;
      fs.writeFileSync(p, JSON.stringify(web, null, 2));
    } catch (e) {}
  }
}

function webQueryBase(jar, extra) {
  let web = {};
  try {
    web = JSON.parse(fs.readFileSync(path.join(__dirname, 'dy-web-params.json'), 'utf8'));
  } catch (e) {}
  return Object.assign(
    {
      device_platform: 'webapp',
      aid: '6383',
      channel: 'channel_pc_web',
      count: '10',
      update_version_code: '170400',
      pc_client_type: '1',
      version_code: '170400',
      version_name: '17.4.0',
      cookie_enabled: 'true',
      screen_width: '1920',
      screen_height: '1080',
      browser_language: 'zh-CN',
      browser_platform: 'Win32',
      browser_name: 'Chrome',
      browser_version: '153.0.0.0',
      browser_online: 'true',
      engine_name: 'Blink',
      engine_version: '153.0.0.0',
      os_name: 'Windows',
      os_version: '10',
      platform: 'PC',
      webid: web.webid || jar.webid || '7663830857493218826',
      verifyFp: jar.verifyFp || web.verifyFp || web.fp,
      fp: jar.fp || web.fp || web.verifyFp,
      msToken: jar.msToken || web.msToken,
    },
    extra || {}
  );
}

function signQuery(params) {
  const q = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => k + '=' + v)
    .join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

function imQuery(jar) {
  let web = {};
  try {
    web = JSON.parse(fs.readFileSync(path.join(__dirname, 'dy-web-params.json'), 'utf8'));
  } catch (e) {}
  const msToken = jar.msToken || web.msToken || 'x';
  const fp = jar.fp || web.fp || web.verifyFp || 'verify_x';
  const verifyFp = jar.verifyFp || web.verifyFp || fp;
  return signQuery({ msToken, verifyFp, fp });
}

function cookieStr(jar) {
  return Object.entries(jar)
    .map(([k, v]) => k + '=' + v)
    .join('; ');
}

async function getJson(url, jar) {
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Cookie: cookieStr(jar), Referer: 'https://www.douyin.com/', Accept: 'application/json' },
    redirect: 'manual',
  });
  absorb(res, jar);
  return { status: res.status, text: await res.text() };
}

async function postProtobuf(host, api, body, jar) {
  const res = await fetch('https://' + host + api + '?' + imQuery(jar), {
    method: 'POST',
    headers: {
      'User-Agent': UA,
      Cookie: cookieStr(jar),
      accept: 'application/x-protobuf',
      'accept-language': 'zh-CN,zh;q=0.9',
      origin: 'https://www.douyin.com',
      'content-type': 'application/x-protobuf',
      referer: 'https://www.douyin.com/',
    },
    body,
    redirect: 'manual',
  });
  const buf = Buffer.from(await res.arrayBuffer());
  absorb(res, jar);
  return { status: res.status, buf };
}

// 现领身份令牌（GET /passport/safe/get_identity_security_token/，约 240 秒有效，绑会话）
async function fetchIdentityToken(jar) {
  const traceId = crypto.randomBytes(4).toString('hex');
  const q = signQuery({
    passport_jssdk_version: '4.2.3',
    passport_jssdk_type: 'lite',
    is_from_ttaccountsdk: '1',
    aid: '6383',
    language: 'zh',
    scene: 'web_im',
    auto_retry_req: '0',
    skip_verify: 'false',
    identity_token_force_get_tag: '0',
    biz_trace_id: traceId,
    id_token_version: '1.2.10',
    msToken: jar.msToken || 'x',
  });
  const res = await fetch('https://www.douyin.com/passport/safe/get_identity_security_token/?' + q, {
    headers: {
      'User-Agent': UA,
      Cookie: cookieStr(jar),
      Accept: 'application/json, text/javascript',
      Referer: 'https://www.douyin.com/chat?isPopup=1',
      'x-tt-passport-csrf-token': jar.passport_csrf_token || jar.passport_csrf_token_default || '',
      'x-tt-passport-trace-id': traceId,
    },
    redirect: 'manual',
  });
  absorb(res, jar);
  const text = await res.text();
  let j = null;
  try {
    j = JSON.parse(text);
  } catch (e) {}
  const data = (j && j.data) || {};
  return { status: res.status, token: data.identity_security_token || '', deviceId: data.device_id || '', raw: text.slice(0, 300) };
}

// 请求体头部键值对补丁：只动会话绑定字段，其余保持
function patchHeaders(buf, jar, identity) {
  buf = pb.replaceKvValue(buf, [15], 'identity_security_token', JSON.stringify({ token: identity.token }));
  buf = pb.replaceKvValue(buf, [15], 'identity_security_device_id', identity.deviceId || '0');
  buf = pb.replaceKvValue(buf, [15], 'identity_security_aid', '');
  buf = pb.replaceKvValue(buf, [15], 'user_agent', UA);
  buf = pb.replaceKvValue(buf, [15], 'webid', jar.webid || '7663830857493218826');
  buf = pb.replaceKvValue(buf, [15], 'fp', jar.fp || jar.verifyFp || '');
  // 剥掉顶层 23/24/25 遗留 web-protect 信封（外来静态令牌，参考实现已弃用）
  buf = pb.removeFieldsAtPath(buf, [23]);
  buf = pb.removeFieldsAtPath(buf, [24]);
  buf = pb.removeFieldsAtPath(buf, [25]);
  return buf;
}

async function main() {
  const secUid = process.argv[2];
  const text = process.argv[3] || '（腕上音符开发自测，可忽略）';
  if (!secUid) {
    console.log('用法: node m05-dm-test.js <对方主页 sec_uid> [消息文本]');
    process.exit(1);
  }
  const jar = loadJar();
  console.log('[0/5] cookie 键数: ' + Object.keys(jar).length);
  if (!jar.sessionid) {
    console.log('❌ cookie 缺 sessionid');
    process.exit(1);
  }

  console.log('[1/5] 查自己的 uid ...');
  const selfRes = await getJson('https://www.douyin.com/aweme/v1/web/user/profile/self/?' + signQuery(webQueryBase(jar, {})), jar);
  let selfUser = null;
  try {
    const j = JSON.parse(selfRes.text);
    selfUser = (j.data || {}).user || j.user || null;
  } catch (e) {}
  if (!selfUser || !selfUser.uid) {
    console.log('  ❌ ' + selfRes.text.slice(0, 160));
    process.exit(2);
  }
  console.log('  ✅ ' + selfUser.nickname + ' uid=' + selfUser.uid);

  console.log('[2/5] 查对方 uid ...');
  const otherRes = await getJson(
    'https://www.douyin.com/aweme/v1/web/user/profile/other/?' +
      signQuery(webQueryBase(jar, { sec_user_id: secUid, publish_video_strategy_type: '2' })),
    jar
  );
  let otherUser = null;
  try {
    const j = JSON.parse(otherRes.text);
    otherUser = (j.data || {}).user || j.user || null;
  } catch (e) {}
  if (!otherUser || !otherUser.uid) {
    console.log('  ❌ ' + otherRes.text.slice(0, 160));
    process.exit(2);
  }
  console.log('  ✅ ' + otherUser.nickname + ' uid=' + otherUser.uid);

  console.log('[3/5] 创建会话（拿 ticket）...');
  // 创建请求沿用上轮已验证通过的最小改动（仅替换双方 uid），不动其它字段
  let body = template('CREATE_CONVERSATION_TEMPLATE');
  body = pb.replaceRepeatedVarintsAtPath(body, [8, 609, 2], [otherUser.uid, selfUser.uid]);
  const cr = await postProtobuf('imapi.douyin.com', '/v2/conversation/create', body, jar);
  fs.writeFileSync(path.join(__dirname, 'dm-create-resp.bin'), cr.buf);
  const convId = pb.readLeafAtPath(cr.buf, [6, 609, 1, 1]);
  const shortId = pb.readLeafAtPath(cr.buf, [6, 609, 1, 2]);
  const ticket = pb.readLeafAtPath(cr.buf, [6, 609, 1, 4]);
  if (!convId || !ticket) {
    console.log('  ❌ 未拿到会话信息，响应已存 dm-create-resp.bin');
    process.exit(3);
  }
  console.log('  ✅ conversationId=' + convId.toString() + ' ticket=' + ticket.toString().slice(0, 12) + '…');

  console.log('[4/5] 现领身份令牌 ...');
  const identity = await fetchIdentityToken(jar);
  if (!identity.token) {
    console.log('  ❌ 未拿到 identity_security_token: ' + identity.raw);
    process.exit(4);
  }
  console.log('  ✅ token=' + identity.token.slice(0, 16) + '… device_id=' + identity.deviceId);

  console.log('[5/5] 发送私信 ...');
  const msgId = uuid();
  const now = (Date.now() + Math.random()).toFixed(4);
  const contentJson = JSON.stringify({ mention_users: [], aweType: 700, richTextInfos: [], text });
  let send = patchHeaders(template('TEXT_MESSAGE_TEMPLATE'), jar, identity);
  send = pb.replaceAtPath(send, [8, 100, 1], convId.toString());
  send = pb.replaceVarintAtPath(send, [8, 100, 2], 1);
  send = pb.replaceVarintAtPath(send, [8, 100, 3], shortId.toString());
  send = pb.replaceAtPath(send, [8, 100, 4], contentJson);
  send = pb.replaceAtPath(send, [8, 100, 7], ticket.toString());
  send = pb.replaceAtPath(send, [8, 100, 8], msgId);
  send = pb.replaceKvValue(send, [8, 100, 5], 's:client_message_id', msgId);
  send = pb.replaceKvValue(send, [8, 100, 5], 's:stime', now);
  const sr = await postProtobuf('imapi.douyin.com', '/v1/message/send', send, jar);
  fs.writeFileSync(path.join(__dirname, 'dm-send-resp.bin'), sr.buf);
  const srText = sr.buf.toString('utf8');
  const srStrings = pb.collectStrings(sr.buf);
  console.log('  HTTP ' + sr.status + '，响应 ' + sr.buf.length + 'B');
  if (srText.includes('decision')) console.log('  响应明文: ' + srText);
  if (srStrings.includes('OK')) {
    console.log('✅ M0.5 验证②（发私信）通过：服务端 OK，clientMessageId=' + msgId);
    console.log('   请让对方回一条消息，随后补验"收"（get_user_message）');
  } else {
    console.log('❌ 发送未返回 OK；可见串: ' + srStrings.slice(0, 10).join(' | ').slice(0, 200));
  }
}

main().catch((e) => {
  console.error('FAIL', e);
  process.exit(1);
});
