// 直写点赞实测（用户授权：KICK 就重登）。按 cv-cat digg() 实录形态，最小参数先行。
// 只打状态码/响应摘要，不回显 cookie 值。
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { createSigner } = require('./sign/abogus.js');

function loadJar() {
  const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
  const m = xml.match(/<string name="cookies">([^<]+)<\/string>/);
  const jar = {};
  if (!m) return jar;
  for (const p of m[1].split(';')) {
    const i = p.indexOf('=');
    if (i > 0) jar[p.slice(0, i).trim()] = p.slice(i + 1).trim();
  }
  return jar;
}

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

(async () => {
  const jar = loadJar();
  const cookieStr = Object.entries(jar).map(([k, v]) => k + '=' + v).join('; ');
  const uid = '2782210114983748';
  const uidMd5 = crypto.createHash('md5').update(uid).digest('hex');

  // 拿一个真实 aweme_id
  const feed = JSON.parse(fs.readFileSync(path.join(__dirname, 'm05-feed-result.json'), 'utf8'));
  const list = feed.data && feed.data.aweme_list ? feed.data.aweme_list : feed.aweme_list;
  const awemeId = String(list[0].aweme_id);
  console.log('aweme_id=' + awemeId);

  const msToken = Array.from({ length: 107 }, () => 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnoprstwxyz0123456789'[Math.floor(Math.random() * 58)]).join('');
  const params = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    pc_client_type: '1', update_version_code: '170400', version_code: '170400', version_name: '17.4.0',
    cookie_enabled: 'true', screen_width: '1920', screen_height: '1080', browser_language: 'zh-CN',
    browser_platform: 'Win32', browser_name: 'Chrome', browser_version: '153.0.0.0', browser_online: 'true',
    os_name: 'Windows', os_version: '10', platform: 'PC', downlink: '10', effective_type: '4g', round_trip_time: '0',
  };
  if (jar['s_v_web_id']) { params.verifyFp = jar['s_v_web_id']; params.fp = jar['s_v_web_id']; }
  if (jar['UIFID']) params.uifid = jar['UIFID'];
  params.msToken = msToken;
  params.uid = uidMd5;
  const query = Object.entries(params).map(([k, v]) => k + '=' + v).join('&');
  const ab = createSigner().makeABogus(query, 0);
  const url = 'https://www.douyin.com/aweme/v1/web/commit/item/digg/?' + query + '&a_bogus=' + encodeURIComponent(ab);

  const body = 'aweme_id=' + awemeId + '&item_type=0&type=1';
  const headers = {
    'User-Agent': UA,
    Referer: 'https://www.douyin.com/discover?modal_id=' + awemeId,
    Origin: 'https://www.douyin.com',
    'Content-Type': 'application/x-www-form-urlencoded',
    Cookie: cookieStr,
  };
  if (jar['passport_csrf_token']) headers['x-tt-passport-csrf-token'] = jar['passport_csrf_token'];

  const res = await fetch(url, { method: 'POST', headers, body });
  const text = await res.text();
  console.log('HTTP ' + res.status);
  console.log('响应前300: ' + text.slice(0, 300));
  const dtrait = res.headers.get('x-tt-verify-passport-decision');
  if (dtrait) console.log('风控头 X-Tt-Verify-Passport-Decision: ' + dtrait);
  // 会话是否还活着？
  const chk = await fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC', {
    headers: { 'User-Agent': UA, Cookie: cookieStr, Referer: 'https://www.douyin.com/' },
  });
  const cj = await chk.json();
  console.log('事后会话检查: ' + (cj.user && cj.user.uid ? '✅ 会话存活 ' + cj.user.nickname : '❌ 会话死亡 status=' + cj.status_code + ' ' + (cj.status_msg || '')));
})();
