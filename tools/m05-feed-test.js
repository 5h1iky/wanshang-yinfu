// M0.5 接口验证①：抖音 Web 推荐视频流（无需登录）
// 依赖：Node 18+（本机 v24）；a_bogus 由 sign/abogus.js 生成（ylcangel/douyin_sign, Apache-2.0）
// 走通标准：能拿到 aweme_list 且首条含 desc/author/video.play_addr
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const REFERER = 'https://www.douyin.com/';

// 带 cookie 累积的手动重定向 fetch（redirect:manual 以便逐跳收集 Set-Cookie）
async function fetchCollect(url, opts = {}, maxRedirects = 5) {
  const jar = opts.jar || {};
  let current = url;
  for (let i = 0; i <= maxRedirects; i++) {
    const headers = Object.assign({ 'User-Agent': UA, Referer: REFERER }, opts.headers || {});
    const cookieStr = Object.entries(jar)
      .map(([k, v]) => k + '=' + v)
      .join('; ');
    if (cookieStr) headers.Cookie = cookieStr;
    const res = await fetch(current, {
      headers,
      redirect: 'manual',
      method: opts.method || 'GET',
      body: opts.body,
    });
    const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
    for (const c of sc) {
      const kv = c.split(';')[0];
      const idx = kv.indexOf('=');
      if (idx > 0) jar[kv.slice(0, idx).trim()] = kv.slice(idx + 1).trim();
    }
    if (res.status >= 300 && res.status < 400 && res.headers.get('location')) {
      current = new URL(res.headers.get('location'), current).toString();
      continue;
    }
    return { res, jar };
  }
  throw new Error('too many redirects');
}

function randStr(len, charset) {
  let s = '';
  for (let i = 0; i < len; i++) s += charset[Math.floor(Math.random() * charset.length)];
  return s;
}

// 参照 msToken/ms_token.py get_ttwid：访问直播间域名即可拿 ttwid（实测 www.douyin.com 两跳已不发 ttwid，live 域一跳就发）
// 顺带拿到 UIFID/UIFID_TEMP（可作 uifid 参数）
async function getTtwid() {
  const jar = {};
  await fetchCollect('https://live.douyin.com/646454278948', { jar });
  return jar;
}

// 参照 msToken/ms_token.py get_msToken：POST mssdk 换 msToken；失败退化为随机
async function getMsToken(ttwid) {
  try {
    const body = JSON.stringify({
      magic: 0x20200422,
      version: 1,
      dataType: 8,
      strData: '{}',
      tspFromClient: Date.now(),
      ulr: 0,
    });
    const jar = ttwid ? { ttwid } : {};
    await fetchCollect('https://mssdk.bytedance.com/web/r/token?ms_appid=6383', {
      method: 'POST',
      body,
      headers: { 'Content-Type': 'application/json', Referer: 'https://rmc.bytedance.com/' },
      jar,
    });
    if (jar.msToken) return jar.msToken;
    console.log('  [warn] mssdk 未回 msToken cookie，退化为随机');
  } catch (e) {
    console.log('  [warn] mssdk 请求失败(' + e.message + ')，退化为随机 msToken');
  }
  return (
    randStr(104, 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_') + '%3D'
  );
}

async function main() {
  console.log('[1/4] 获取 ttwid ...');
  const jar = await getTtwid();
  console.log('  ttwid = ' + String(jar.ttwid || '(空)').slice(0, 30) + '...');
  if (!jar.ttwid) throw new Error('ttwid 获取失败');

  console.log('[2/4] 获取 msToken ...');
  const msToken = await getMsToken(jar.ttwid);
  jar.msToken = msToken;
  console.log('  msToken = ' + String(msToken).slice(0, 30) + '...');

  console.log('[3/4] 生成 a_bogus ...');
  const webid = String(7000000000000000000n + BigInt(Math.floor(Math.random() * 1e17)));
  const params = {
    device_platform: 'webapp',
    aid: '6383',
    channel: 'channel_pc_web',
    update_version_code: '170400',
    pc_client_type: '1',
    pc_libra_divert: 'Windows',
    support_h265: '1',
    support_dash: '1',
    version_code: '170400',
    version_name: '17.4.0',
    cookie_enabled: 'true',
    screen_width: '2560',
    screen_height: '1440',
    browser_language: 'zh-CN',
    browser_platform: 'Win32',
    browser_name: 'Chrome',
    browser_version: '135.0.0.0',
    browser_online: 'true',
    engine_name: 'Blink',
    engine_version: '135.0.0.0',
    os_name: 'Windows',
    os_version: '10',
    cpu_core_num: '20',
    device_memory: '8',
    platform: 'PC',
    downlink: '0.55',
    effective_type: '3g',
    round_trip_time: '500',
    webid: webid,
    msToken: msToken,
    count: '10',
    refresh_index: '1',
    tag_id: '',
    installed_vol: '0',
  };
  if (jar.UIFID) params.uifid = jar.UIFID;
  // a_bogus 必须对最终发送的原始 query 串签名（值里含 %3D 等已编码字符，不再二次编码）
  const query = Object.entries(params)
    .map(([k, v]) => k + '=' + v)
    .join('&');
  const signer = createSigner();
  const ab = signer.makeABogus(query, 0);
  console.log('  a_bogus = ' + String(ab).slice(0, 30) + '...');

  console.log('[4/4] 请求推荐视频流 /aweme/v1/web/tab/feed/ ...');
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + query + '&a_bogus=' + encodeURIComponent(ab);
  const { res } = await fetchCollect(url, { jar });
  const text = await res.text();
  fs.writeFileSync(path.join(__dirname, 'm05-feed-result.json'), text);
  console.log('  HTTP ' + res.status + '，响应 ' + text.length + ' 字节');
  try {
    const j = JSON.parse(text);
    console.log('  status_code = ' + j.status_code + '，status_msg = ' + j.status_msg);
    const list = j.aweme_list || [];
    console.log('  aweme_list 条数 = ' + list.length);
    if (list.length) {
      const it = list[0];
      console.log('  样例: desc = ' + String(it.desc || '').slice(0, 30));
      console.log('        author = ' + (it.author && it.author.nickname));
      console.log('        play_uri = ' + (it.video && it.video.play_addr && it.video.play_addr.uri));
      console.log('✅ M0.5 验证①（推荐视频流）通过');
    } else {
      console.log('❌ 返回无视频列表，需要调参（完整响应见 tools/m05-feed-result.json）');
    }
  } catch (e) {
    console.log('❌ 非 JSON 响应，前 600 字：\n' + text.slice(0, 600));
  }
}

main().catch((e) => {
  console.error('FAIL', e);
  process.exit(1);
});
