// 决定性对照：连续刷 10 屏（像真实用户），A 组=现在的参数，B 组=补 webid/verifyFp/uifid
// 比"累计拿到多少不重复视频"和"重复率"。上一版只拉 3 次×2 条，样本太弱没测出差异。
// 用法: node tools/feed-identity-test2.js [webid]
const fs = require('fs');
const path = require('path');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const WEBID = process.argv[2] || '7689449330423498259';
const ROUNDS = 10;

function cookieHeader() {
  const list = JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'));
  const seen = {}; const out = [];
  for (const c of list) { if (seen[c.name]) continue; seen[c.name] = 1; out.push(c.name + '=' + c.value); }
  return out.join('; ');
}
function cv(ck, k) { const m = new RegExp('(?:^|; )' + k + '=([^;]+)').exec(ck); return m ? m[1] : ''; }

function buildQuery(ck, idx, identity, seenGids) {
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
    filterGids: seenGids.join(','),            // 两组都带：这是页面自己就在用的客户端过滤
  };
  if (identity) {
    p.webid = WEBID;
    const fp = cv(ck, 's_v_web_id'); if (fp) { p.verifyFp = fp; p.fp = fp; }
    const ui = cv(ck, 'UIFID'); if (ui) p.uifid = ui;
  }
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

async function run(ck, label, identity) {
  const all = []; const seen = new Set(); let dup = 0; let perScreen = [];
  for (let i = 0; i < ROUNDS; i++) {
    const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(ck, i + 1, identity, [...seen].slice(-40));
    let j = null;
    try {
      const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ck } });
      j = await res.json().catch(() => null);
      if (!j) { console.log('  第' + (i + 1) + '屏 非JSON'); continue; }
    } catch (e) { console.log('  第' + (i + 1) + '屏 出错 ' + e.message); continue; }
    const list = (j.aweme_list || []).map(a => String(a.aweme_id));
    perScreen.push(list.length);
    for (const id of list) { if (seen.has(id)) dup++; else seen.add(id); }
    all.push(...list);
    await new Promise(r => setTimeout(r, 350));
  }
  console.log(`\n${label}: 共 ${all.length} 条 / 去重后 ${seen.size} 条 / 重复 ${dup} 条`);
  console.log(`  每屏条数: ${perScreen.join(',')}`);
  return seen.size;
}

(async () => {
  const ck = cookieHeader();
  console.log('webid=' + WEBID + '  每轮 10 屏');
  const a = await run(ck, 'A 组（现状：无设备身份，只带 filterGids）', false);
  const b = await run(ck, 'B 组（补 webid+verifyFp+uifid）', true);
  console.log('\n判读: A=' + a + ' 唯一 / B=' + b + ' 唯一 → ' + (b > a * 1.15 ? 'B 明显更好，身份参数有效' : '差异不大，身份不是重复的主因'));
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
