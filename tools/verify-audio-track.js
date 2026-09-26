// 端到端验证：按 App 的选档规则挑出来的地址，到底是不是带音轨的合成 mp4
// 流程：拉 tab/feed → 复刻 DouyinApi.pickGearUrl 规则 → 跟随 302 下载头部 → 扫 MP4 box 找 hdlr
// 用法: node tools/verify-audio-track.js
const fs = require('fs');
const path = require('path');
const https = require('https');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

function ck() {
  const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
  const m = /<string name="cookies">([^<]+)<\/string>/.exec(xml);
  return m ? m[1] : '';
}
function buildQuery(idx) {
  const p = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', update_version_code: '170400',
    pc_client_type: '1', pc_libra_divert: 'Windows', support_h265: '1', support_dash: '1',
    version_code: '170400', version_name: '17.4.0', cookie_enabled: 'true',
    screen_width: '2560', screen_height: '1440', browser_language: 'zh-CN', browser_platform: 'Win32',
    browser_name: 'Chrome', browser_version: '135.0.0.0', browser_online: 'true',
    engine_name: 'Blink', engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC', downlink: '0.55',
    effective_type: '3g', round_trip_time: '500', count: '10', refresh_index: String(idx), tag_id: ''
  };
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

/** 复刻 DouyinApi.pickGearUrl：H.264 + 非 DASH + 540p优先 + 同档最低码率 */
function pickGear(video) {
  const gears = video.bit_rate || [];
  let best = null;
  for (const g of gears) {
    if (g.is_h265 || g.is_bytevc1) continue;
    const l = (g.play_addr || {}).url_list || [];
    let url = l.find(u => u.indexOf('https://www.douyin.com/') === 0) || '';
    if (!url) {
      const uri = (g.play_addr || {}).uri;
      if (uri) url = 'https://www.douyin.com/aweme/v1/play/?video_id=' + uri + '&ratio=1080p&line=0';
    }
    if (!url || url.indexOf('/play/dash/') >= 0) continue;
    const name = g.gear_name || '';
    const rank = name.indexOf('_540_') >= 0 ? 3 : name.indexOf('_720_') >= 0 ? 2 : 1;
    if (!best || rank > best.rank || (rank === best.rank && g.bit_rate < best.br)) best = { url, rank, br: g.bit_rate, name };
  }
  return best;
}

function get(url, headers, maxBytes) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume();
        return resolve(get(new URL(res.headers.location, url).href, headers, maxBytes));
      }
      const chunks = []; let n = 0;
      res.on('data', c => { chunks.push(c); n += c.length; if (n >= maxBytes) res.destroy(); });
      res.on('end', () => resolve({ buf: Buffer.concat(chunks), headers: res.headers }));
      res.on('error', e => resolve({ buf: Buffer.concat(chunks), headers: res.headers, partial: e.message }));
    }).on('error', reject);
  });
}

/** 扫 MP4 的 hdlr box：vide=视频轨 soun=音频轨 */
function tracks(buf) {
  const s = buf.toString('latin1');
  const out = new Set();
  let i = 0;
  while ((i = s.indexOf('hdlr', i)) >= 0) {
    const seg = s.slice(i, i + 40);
    const m = /(?:vide|soun|subt|meta|text|cdsc)/.exec(seg);
    if (m) out.add(m[0]);
    i += 4;
  }
  return [...out];
}

(async () => {
  const cookie = ck();
  const res = await fetch('https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(1),
    { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: cookie } });
  const j = await res.json();
  const list = j.aweme_list || [];
  console.log('feed 回 ' + list.length + ' 条\n');
  for (const it of list.slice(0, 4)) {
    const v = it.video || {};
    const picked = pickGear(v);
    if (!picked) { console.log(it.aweme_id + '  选档失败（无合格档）'); continue; }
    const r = await get(picked.url, { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Range: 'bytes=0-524287' }, 600000);
    const t = tracks(r.buf);
    console.log(it.aweme_id + ' 「' + String(it.desc || '').slice(0, 18) + '」');
    console.log('  档=' + picked.name + '  ' + (picked.br / 1000).toFixed(0) + 'kbps  DASH=' + (picked.url.indexOf('/play/dash/') >= 0));
    console.log('  下载 ' + r.buf.length + 'B  content-type=' + (r.headers['content-type'] || '-') + '  status=' + (r.headers[':s'] || 200));
    console.log('  MP4 轨道: [' + t.join(', ') + ']  → ' + (t.includes('soun') ? '✅ 有音轨' : '❌ 无音轨（会没声音）'));
    // 对照：默认 play_addr（改动前用的那条）
    const dl = ((v.play_addr || {}).url_list || []).find(u => u.indexOf('https://www.douyin.com/') === 0);
    if (dl) {
      const r2 = await get(dl, { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Range: 'bytes=0-524287' }, 600000);
      console.log('  对照默认 play_addr 轨道: [' + tracks(r2.buf).join(', ') + ']  ' + (r2.buf.length === r.buf.length ? '（与选档同尺寸?）' : ''));
    }
    console.log('');
  }
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
