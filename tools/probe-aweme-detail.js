// 探针：aweme/detail 换址实测（2026-09-27，为「看过记录可点播」做的前置验证）
// 目的：确认 ① endpoint/参数能通 ② 响应里条目到底在哪个键（aweme_detail?）③ 能解出可播 URL
// 判据：HTTP 200 + status_code=0 + play_url 非空。
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const COOKIE = fs.readFileSync(path.join(__dirname, '_session_cookie.txt'), 'utf8').trim();

function q(base) {
  const p = Object.assign({
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    update_version_code: '170400', pc_client_type: '1', pc_libra_divert: 'Windows',
    support_h265: '1', support_dash: '1',
    version_code: '170400', version_name: '17.4.0',
    cookie_enabled: 'true', screen_width: '2560', screen_height: '1440',
    browser_language: 'zh-CN', browser_platform: 'Win32', browser_name: 'Chrome',
    browser_version: '135.0.0.0', browser_online: 'true', engine_name: 'Blink',
    engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC',
    downlink: '0.55', effective_type: '3g', round_trip_time: '0',
  }, base);
  const s = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return s + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(s, 0));
}

(async () => {
  const B = 'https://www.douyin.com';
  // 先从 feed 拿一个真实 aweme_id（避免拿历史记录里已失效的 id）
  console.log('=== 取一个真实 aweme_id ===');
  const fr = await fetch(B + '/aweme/v1/web/tab/feed/?' + q({ count: '2', refresh_index: '1', tag_id: '' }), {
    headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: COOKIE, Accept: 'application/json' },
    redirect: 'manual',
  });
  const fj = JSON.parse(await fr.text());
  const list = fj.aweme_list || [];
  const it = list.find(x => x.video && x.video.play_addr);
  if (!it) { console.log('feed 里没有可用视频'); return; }
  console.log(`aweme_id = ${it.aweme_id}  desc=${String(it.desc).slice(0, 30)}`);

  console.log('\n=== aweme/detail 实测 ===');
  const dr = await fetch(B + '/aweme/v1/web/aweme/detail/?' + q({ aweme_id: it.aweme_id }), {
    headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: COOKIE, Accept: 'application/json' },
    redirect: 'manual',
  });
  const txt = await dr.text();
  console.log(`HTTP ${dr.status} ${txt.length}B`);
  let dj = null; try { dj = JSON.parse(txt); } catch (e) { console.log('非 JSON: ' + txt.slice(0, 160)); return; }
  console.log(`status_code=${dj.status_code}`);
  console.log(`顶层键 = ${Object.keys(dj).join(', ')}`);
  const d = dj.aweme_detail;
  if (!d) { console.log('⚠️ 无 aweme_detail 键'); return; }
  console.log(`\naweme_detail.aweme_id = ${d.aweme_id}`);
  console.log(`desc = ${String(d.desc).slice(0, 40)}`);
  console.log(`author = ${d.author ? d.author.nickname : '(无)'}`);
  const pu = d.video && d.video.play_addr ? (d.video.play_addr.url_list || []) : [];
  console.log(`play_addr.url_list 条数 = ${pu.length}`);
  if (pu.length) console.log(`  [0] = ${String(pu[0]).slice(0, 90)}…`);
  const hasWww = pu.some(u => String(u).startsWith('https://www.douyin.com/'));
  console.log(`含 www.douyin.com 跳转候选 = ${hasWww}`);
  console.log(`bit_rate 档数 = ${d.video && d.video.bit_rate ? d.video.bit_rate.length : 0}`);
  console.log('\n完毕。');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
