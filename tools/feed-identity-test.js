// 定判：原生 tab/feed 跨次重复，根因是不是"没带 webid 等设备身份"
// 模拟"用户重新进入 App"= 游标归零再拉一次。A 组不带身份、B 组带 webid+verifyFp+uifid，比重复率。
// 只读，不改 App。用法: node tools/feed-identity-test.js
const fs = require('fs');
const path = require('path');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const WEBID = process.argv[2] || '7689449330423498259';

function cookieHeader() {
  const f = path.join(__dirname, 'webview-cookies.json');
  const list = JSON.parse(fs.readFileSync(f, 'utf8'));
  const seen = {}; const out = [];
  for (const c of list) { if (seen[c.name]) continue; seen[c.name] = 1; out.push(c.name + '=' + c.value); }
  return out.join('; ');
}
function cv(ck, k) { const m = new RegExp('(?:^|; )' + k + '=([^;]+)').exec(ck); return m ? m[1] : ''; }

// 复刻 DouyinApi.buildFeedQuery 的参数族
function buildQuery(ck, idx, withIdentity) {
  const p = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', update_version_code: '170400',
    pc_client_type: '1', pc_libra_divert: 'Windows', support_h265: '1', support_dash: '1',
    version_code: '170400', version_name: '17.4.0', cookie_enabled: 'true',
    screen_width: '2560', screen_height: '1440', browser_language: 'zh-CN', browser_platform: 'Win32',
    browser_name: 'Chrome', browser_version: '135.0.0.0', browser_online: 'true',
    engine_name: 'Blink', engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC', downlink: '0.55',
    effective_type: '3g', round_trip_time: '500',
    count: '10', refresh_index: String(idx), tag_id: '',
  };
  if (withIdentity) {
    p.webid = WEBID;
    const fp = cv(ck, 's_v_web_id'); if (fp) { p.verifyFp = fp; p.fp = fp; }
    const ui = cv(ck, 'UIFID'); if (ui) p.uifid = ui;
    const ms = cv(ck, 'msToken'); if (ms) p.msToken = ms;
  }
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

async function pull(ck, idx, withIdentity, label) {
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(ck, idx, withIdentity);
  const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ck } });
  const j = await res.json().catch(() => null);
  const list = ((j || {}).aweme_list || []).map(a => String(a.aweme_id));
  console.log(`  ${label}: HTTP ${res.status} status_code=${(j || {}).status_code} 条数=${list.length}`);
  return list;
}
const ov = (a, b) => a.filter(x => b.includes(x)).length;

(async () => {
  const ck = cookieHeader();
  console.log('webid=' + WEBID + '  cookie项数=' + ck.split(';').length + '  含sessionid=' + /sessionid=/.test(ck));
  console.log('\n=== A 组：现状（不带任何设备身份），模拟连续 3 次"重新进入 App"（游标都归 0）');
  const a1 = await pull(ck, 0, false, '第1次');
  const a2 = await pull(ck, 0, false, '第2次');
  const a3 = await pull(ck, 0, false, '第3次');
  console.log(`  重复率: 1∩2=${ov(a1, a2)}  1∩3=${ov(a1, a3)}  2∩3=${ov(a2, a3)}  (每组 ${a1.length} 条)`);
  console.log('\n=== B 组：带 webid+verifyFp+uifid，同样 3 次');
  const b1 = await pull(ck, 0, true, '第1次');
  const b2 = await pull(ck, 0, true, '第2次');
  const b3 = await pull(ck, 0, true, '第3次');
  console.log(`  重复率: 1∩2=${ov(b1, b2)}  1∩3=${ov(b1, b3)}  2∩3=${ov(b2, b3)}  (每组 ${b1.length} 条)`);
  console.log('\n判读：若 B 组重复率显著低于 A 组 → 重复的根因就是缺设备身份，SeenStore 那层过滤是补丁不是解法');
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
