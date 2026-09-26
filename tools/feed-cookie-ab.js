// 只读对照实验：同一套 tab/feed 参数，分别「不带登录 cookie」与「带登录 cookie」各拉一批，
// 解出 video_tag 一级类目 + 标题，验证"登录态没发出去 → 泛池"这个根因。
//
// 判据（可证伪）：若两批的一级类目分布明显不同（匿名=影视泛池 / 登录=账号画像类目），
// 则根因成立；若两批分布相同，则假设不成立，得换方向。
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const COOKIE_FILE = path.join(__dirname, '_session_cookie.txt');
const loginCookie = fs.existsSync(COOKIE_FILE) ? fs.readFileSync(COOKIE_FILE, 'utf8').trim() : '';
if (!loginCookie) { console.log('缺少登录 cookie（tools/_session_cookie.txt）'); process.exit(1); }

const REFERER = 'https://www.douyin.com/';

async function getTtwid() {
  const jar = {};
  const res = await fetch('https://live.douyin.com/646454278948', {
    headers: { 'User-Agent': UA }, redirect: 'manual',
  });
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const c of sc) {
    const kv = c.split(';')[0]; const i = kv.indexOf('=');
    if (i > 0) jar[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
  }
  return jar;
}

function buildQuery(signer) {
  const params = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    update_version_code: '170400', pc_client_type: '1', pc_libra_divert: 'Windows',
    support_h265: '1', support_dash: '1', version_code: '170400', version_name: '17.4.0',
    cookie_enabled: 'true', screen_width: '2560', screen_height: '1440',
    browser_language: 'zh-CN', browser_platform: 'Win32', browser_name: 'Chrome',
    browser_version: '135.0.0.0', browser_online: 'true', engine_name: 'Blink',
    engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC',
    downlink: '0.55', effective_type: '3g', round_trip_time: '500',
    count: '10', refresh_index: String(1 + Math.floor(Math.random() * 50)), tag_id: '',
  };
  const q = Object.entries(params).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(signer.makeABogus(q, 0));
}

async function pull(label, cookie) {
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(createSigner());
  const headers = { 'User-Agent': UA, Referer: REFERER };
  if (cookie) headers.Cookie = cookie;
  const res = await fetch(url, { headers, redirect: 'manual' });
  const text = await res.text();
  let j = null;
  try { j = JSON.parse(text); } catch (e) { }
  const list = (j && j.aweme_list) || [];
  console.log(`\n=== ${label} ===`);
  console.log(`HTTP ${res.status}，status_code=${j ? j.status_code : '?'}，条数=${list.length}`);
  const lvl1 = {};
  list.forEach(it => {
    (it.video_tag || []).filter(t => t.level === 1 && t.tag_name)
      .forEach(t => lvl1[t.tag_name] = (lvl1[t.tag_name] || 0) + 1);
  });
  const tot = Object.values(lvl1).reduce((a, b) => a + b, 0);
  const top = Object.entries(lvl1).sort((a, b) => b[1] - a[1]);
  console.log('一级类目分布:');
  top.forEach(([k, v]) => console.log(`    ${k.padEnd(12)} ${v}/${tot}  ${'#'.repeat(v)}`));
  if (!top.length) console.log('    （video_tag 全空）');
  console.log('标题前 5 条:');
  list.slice(0, 5).forEach((it, i) => console.log(`    ${i}: ${String(it.desc || '').slice(0, 38)}`));
  const top1 = top[0];
  console.log(`最大类目占比 = ${top1 ? (top1[1] / tot * 100).toFixed(0) : 0}% (${top1 ? top1[0] : '-'})`);
  return { list, lvl1, top1, tot };
}

(async () => {
  console.log('[准备] 取匿名 ttwid ...');
  const jar = await getTtwid();
  const anon = jar.ttwid ? `ttwid=${jar.ttwid}` : '';
  console.log('  ttwid =', jar.ttwid ? '有' : '无');

  const A = await pull('A组：仅匿名 ttwid（= 修复前 App 的实际行为）', anon);
  await new Promise(r => setTimeout(r, 2500));
  const B = await pull('B组：匿名 ttwid + 登录 cookie（= 修复后）', anon + '; ' + loginCookie);

  console.log('\n=== 结论 ===');
  const aTop = A.top1 ? A.top1[0] : '(空)';
  const bTop = B.top1 ? B.top1[0] : '(空)';
  const aConc = A.tot ? (A.top1[1] / A.tot * 100).toFixed(0) : '0';
  const bConc = B.tot ? (B.top1[1] / B.tot * 100).toFixed(0) : '0';
  console.log(`  A(匿名) 主类目=${aTop} 集中度=${aConc}%`);
  console.log(`  B(登录) 主类目=${bTop} 集中度=${bConc}%`);
  console.log(aTop !== bTop
    ? '  → 两批主类目不同：登录态确实改变推荐内容，根因成立'
    : '  → 两批主类目相同：登录态没影响，根因不成立，需换方向');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
