// 只读：A/B 各多拉几批，累积统计一级类目分布，把 6 条的偶然性排除掉。
// 判据：匿名批应发散（多类目、集中度低）；登录批应向固定少数类目收敛。
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const loginCookie = fs.readFileSync(path.join(__dirname, '_session_cookie.txt'), 'utf8').trim();
const REFERER = 'https://www.douyin.com/';
const ROUNDS = parseInt(process.argv[2] || '5', 10);

async function getTtwid() {
  const jar = {};
  const res = await fetch('https://live.douyin.com/646454278948', { headers: { 'User-Agent': UA }, redirect: 'manual' });
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const c of sc) { const kv = c.split(';')[0]; const i = kv.indexOf('='); if (i > 0) jar[kv.slice(0, i).trim()] = kv.slice(i + 1).trim(); }
  return jar;
}

async function pull(cookie, idx) {
  const p = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    update_version_code: '170400', pc_client_type: '1', pc_libra_divert: 'Windows',
    support_h265: '1', support_dash: '1', version_code: '170400', version_name: '17.4.0',
    cookie_enabled: 'true', screen_width: '2560', screen_height: '1440',
    browser_language: 'zh-CN', browser_platform: 'Win32', browser_name: 'Chrome',
    browser_version: '135.0.0.0', browser_online: 'true', engine_name: 'Blink',
    engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC',
    downlink: '0.55', effective_type: '3g', round_trip_time: '500',
    count: '10', refresh_index: String(1000 + idx * 7 + Math.floor(Math.random() * 5)), tag_id: '',
  };
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
  const headers = { 'User-Agent': UA, Referer: REFERER };
  if (cookie) headers.Cookie = cookie;
  const res = await fetch(url, { headers, redirect: 'manual' });
  const t = await res.text();
  let j = null; try { j = JSON.parse(t); } catch (e) { return []; }
  return (j && j.aweme_list) || [];
}

function tally(list, acc) {
  list.forEach(it => {
    const v1 = (it.video_tag || []).filter(x => x.level === 1 && x.tag_name);
    const name = v1.length ? v1[0].tag_name : '(无标签)';
    acc[name] = (acc[name] || 0) + 1;
  });
}

function report(label, acc) {
  const tot = Object.values(acc).reduce((a, b) => a + b, 0);
  const top = Object.entries(acc).sort((a, b) => b[1] - a[1]);
  console.log(`\n=== ${label}（共 ${tot} 条）===`);
  top.forEach(([k, v]) => console.log(`  ${k.padEnd(12)} ${String(v).padStart(3)}  ${(v / tot * 100).toFixed(0).padStart(3)}%  ${'#'.repeat(Math.ceil(v / tot * 40))}`));
  console.log(`  类目种类数 = ${top.length}`);
  const t1 = top[0];
  const top3 = top.slice(0, 3).reduce((s, x) => s + x[1], 0);
  console.log(`  最大类目 = ${t1[0]} ${(t1[1] / tot * 100).toFixed(0)}%   |   Top3 合计 ${(top3 / tot * 100).toFixed(0)}%`);
  return { tot, kinds: top.length, top1: t1[0], c1: t1[1] / tot, c3: top3 / tot };
}

(async () => {
  const jar = await getTtwid();
  const anon = jar.ttwid ? `ttwid=${jar.ttwid}` : '';
  const A = {}, B = {};
  for (let i = 0; i < ROUNDS; i++) {
    const a = await pull(anon, i); tally(a, A);
    await new Promise(r => setTimeout(r, 1800));
    const b = await pull(anon + '; ' + loginCookie, i); tally(b, B);
    await new Promise(r => setTimeout(r, 1800));
    process.stdout.write(`\r  第 ${i + 1}/${ROUNDS} 批：匿名 ${a.length} 条 / 登录 ${b.length} 条   `);
  }
  console.log('\n');
  const ra = report('A 组：仅匿名 ttwid（修复前 App 的实际行为）', A);
  const rb = report('B 组：+ 登录 cookie（修复后）', B);
  console.log('\n=== 判读 ===');
  console.log(`  匿名：${ra.kinds} 个类目 / Top3 ${(ra.c3 * 100).toFixed(0)}%  → ${ra.c3 < 0.6 ? '发散（泛池特征）' : '收敛'}`);
  console.log(`  登录：${rb.kinds} 个类目 / Top3 ${(rb.c3 * 100).toFixed(0)}%  → ${rb.c3 < 0.6 ? '发散' : '收敛（画像特征）'}`);
  console.log(`  主类目：匿名=${ra.top1} / 登录=${rb.top1} ${ra.top1 !== rb.top1 ? '（不同 ✅）' : '（相同）'}`);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
