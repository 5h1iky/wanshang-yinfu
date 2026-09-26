// 隔离变量：filterGids 到底是不是"不重复"的开关
// C 组=完全不发 filterGids（App 现状），D 组=发（把已看过的 id 回填），两组都同样翻页 refresh_index 1..10
// 用法: node tools/feed-filter-test.js
const fs = require('fs');
const path = require('path');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const ROUNDS = 10;

function cookieHeader() {
  // 传 app 则用 App 自己的会话备份（LoginManager 那份），否则用引擎 WebView 导出的
  if (process.argv[2] === 'app') {
    const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
    const m = /<string name="cookies">([^<]+)<\/string>/.exec(xml);
    console.log('（用 App 自己的会话 cookie）');
    return m ? m[1] : '';
  }
  const list = JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'));
  const seen = {}; const out = [];
  for (const c of list) { if (seen[c.name]) continue; seen[c.name] = 1; out.push(c.name + '=' + c.value); }
  return out.join('; ');
}
function buildQuery(idx, gids) {
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
  if (gids) p.filterGids = gids.join(',');
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}
async function run(ck, label, useGids) {
  const seen = new Set(); const order = []; let dup = 0; let per = [];
  for (let i = 0; i < ROUNDS; i++) {
    const g = useGids ? [...seen].slice(-50) : null;
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(i + 1, g);
    let j;
    try {
      const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ck } });
      j = await res.json().catch(() => null);
    } catch (e) { console.log('  err ' + e.message); continue; }
    if (!j) { console.log('  非JSON'); continue; }
    const list = (j.aweme_list || []).map(a => String(a.aweme_id));
    per.push(list.length);
    for (const id of list) { if (seen.has(id)) dup++; else { seen.add(id); order.push(id); } }
    await new Promise(r => setTimeout(r, 350));
  }
  console.log(`${label}: 收到 ${per.reduce((a, b) => a + b, 0)} 条 / 唯一 ${seen.size} / 重复 ${dup}  每屏[${per.join(',')}]`);
  return { uniq: seen.size, dup };
}
(async () => {
  const ck = cookieHeader();
  console.log('C 组 = App 现状（不发 filterGids），D 组 = 回填已看过 id\n');
  const c = await run(ck, 'C 无 filterGids ', false);
  const d = await run(ck, 'D 有 filterGids ', true);
  console.log('\n判读: 重复条数 C=' + c.dup + ' vs D=' + d.dup + ' → ' + (d.dup < c.dup ? 'filterGids 是开关' : 'filterGids 不是决定因素'));
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
