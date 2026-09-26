// 头矩阵：MediaPlayer 默认什么头都不发，那到底哪些头是"能播"的必要条件？
// 对 detail 的三类候选 + 推荐页 DOM 候选，逐一在"无头/仅UA/UA+Referer/UA+Referer+Cookie/UA+Cookie"下探活
// 用法: node tools/play-header-matrix.js
const fs = require('fs');
const path = require('path');
const https = require('https');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

function cookieHeader() {
  const list = JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'));
  const seen = {}; const out = [];
  for (const c of list) { if (seen[c.name]) continue; seen[c.name] = 1; out.push(c.name + '=' + c.value); }
  return out.join('; ');
}
function req(url, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname + u.search, method: 'GET',
      headers: Object.assign({ Range: 'bytes=0-1' }, headers), timeout: 15000 }, (res) => {
      let n = 0; res.on('data', c => n += c.length);
      res.on('end', () => resolve({ status: res.statusCode, ct: res.headers['content-type'] || '', loc: res.headers.location }));
    });
    r.on('error', e => resolve({ status: 'ERR', ct: e.code || e.message }));
    r.on('timeout', () => { r.destroy(); resolve({ status: 'TIMEOUT', ct: '' }); });
    r.end();
  });
}
async function probe(url, headers) {
  let cur = url;
  for (let hop = 0; hop < 5; hop++) {
    const r = await req(cur, headers);
    if (r.status >= 300 && r.status < 400 && r.loc) {
      cur = new URL(r.loc, cur).href;
      continue;
    }
    const ok = (r.status === 200 || r.status === 206) && /video|octet-stream/.test(r.ct);
    return { ok, code: r.status, ct: r.ct.split(';')[0], hop, host: (new URL(cur).hostname || '').slice(0, 26) };
  }
  return { ok: false, code: 'REDIRECTLOOP' };
}

(async () => {
  const ck = cookieHeader();
  const raw = fs.readFileSync(path.join(__dirname, 'detail-raw.json'), 'utf8');
  const v = (JSON.parse(raw).aweme_detail || {}).video || {};
  const list = (v.play_addr || {}).url_list || [];
  const dom = JSON.parse(fs.readFileSync(path.join(__dirname, 'dom-signed-urls.json'), 'utf8'));
  const cases = {
    'A 无头(MediaPlayer现状)': {},
    'B 仅UA': { 'User-Agent': UA },
    'C UA+Referer': { 'User-Agent': UA, Referer: 'https://www.douyin.com/' },
    'D UA+Referer+Cookie': { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ck },
    'E UA+Cookie(无Referer)': { 'User-Agent': UA, Cookie: ck }
  };
  const targets = [
    ['detail[0] 直连CDN v11', list[0]],
    ['detail[1] 直连CDN v26', list[1]],
    ['detail[2] www跳转+sign(AWEME_DETAIL)', list[2]],
    ['推荐页DOM[0] 短签名', dom[0]],
    ['推荐页DOM[1] 长签名+biz_sign', dom[1]]
  ].filter(t => t[1]);
  for (const [name, url] of targets) {
    const line = [];
    for (const [hn, h] of Object.entries(cases)) {
      const r = await probe(url, h);
      line.push(hn.slice(0, 1) + ':' + (r.ok ? 'OK' : r.code + '/' + (r.ct || '').slice(0, 9)) + (r.host ? '@' + r.host.split('.')[0] : ''));
    }
    console.log(name.padEnd(38) + ' | ' + line.join('  '));
  }
  console.log('\n列义: A无头 B仅UA C UA+Referer D UA+Referer+Cookie E UA+Cookie');
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
