// 卡点 A 定判：把 DouyinApi.fetchDetail 的原始响应打到磁盘，再逐条候选做 Range 探活（跟 302 链）
// 只读取证，不改 App。签名走 a_bogus；cookie 用刚导出的 webview-cookies.json；Referer=www.douyin.com
const fs = require('fs');
const path = require('path');
const https = require('https');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
const RAW = path.join(__dirname, 'detail-raw.json');

function cookieHeader() {
  const list = JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'));
  const seen = {};
  const out = [];
  for (const c of list) {
    if (seen[c.name]) continue;
    seen[c.name] = 1;
    out.push(c.name + '=' + c.value);
  }
  return out.join('; ');
}

// 与 DouyinApi.buildDetailQuery 完全一致的参数族 + a_bogus
function detailQuery(awemeId) {
  const p = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', aweme_id: awemeId,
    request_source: '1', origin_type: 'pc_feed', update_version_code: '170400', pc_client_type: '1',
    pc_libra_divert: 'Windows', support_h265: '1', support_dash: '1', version_code: '170400',
    version_name: '17.4.0', cookie_enabled: 'true', screen_width: '2560', screen_height: '1440',
    browser_language: 'zh-CN', browser_platform: 'Win32', browser_name: 'Chrome',
    browser_version: '135.0.0.0', browser_online: 'true', engine_name: 'Blink', engine_version: '135.0.0.0',
    os_name: 'Windows', os_version: '10', cpu_core_num: '20', device_memory: '8', platform: 'PC',
    downlink: '0.55', effective_type: '3g', round_trip_time: '500',
  };
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

function req(url, headers) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request(
      { hostname: u.hostname, path: u.pathname + u.search, method: 'GET', headers, timeout: 15000 },
      (res) => {
        let n = 0;
        res.on('data', (c) => (n += c.length));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, bytes: n }));
      }
    );
    r.on('error', reject);
    r.on('timeout', () => { r.destroy(new Error('timeout')); });
    r.end();
  });
}

async function follow(url, headers, label) {
  const chain = [];
  let cur = url;
  for (let hop = 0; hop < 5; hop++) {
    const host = new URL(cur).hostname;
    const h = Object.assign({}, headers, { Range: 'bytes=0-1', Host: host });
    if (hop > 0) delete h.Referer; // 只给首跳带 Referer
    let r;
    try {
      r = await req(cur, h);
    } catch (e) {
      chain.push('hop' + hop + ' ERR:' + e.message);
      return { label, verdict: 'neterr', chain };
    }
    chain.push('hop' + hop + ' ' + r.status + ' ' + (r.headers['content-type'] || '-') + ' ' + (r.headers.location ? '->' + new URL(r.headers.location, cur).hostname : r.bytes + 'B'));
    if (r.status >= 300 && r.status < 400 && r.headers.location) { cur = new URL(r.headers.location, cur).href; continue; }
    const ok = (r.status === 200 || r.status === 206) && /video|octet-stream/.test(r.headers['content-type'] || '');
    return { label, verdict: ok ? 'PLAYABLE' : 'DEAD', chain, finalStatus: r.status, ct: r.headers['content-type'] };
  }
  return { label, verdict: 'toomanyredirects', chain };
}

(async () => {
  const ck = cookieHeader();
  console.log('cookie 项数: ' + ck.split(';').length);
  let raw;
  if (process.argv[3] === 'reuse' && fs.existsSync(RAW)) {
    raw = fs.readFileSync(RAW, 'utf8');
    console.log('复用已抓好的响应磁盘副本: detail-raw.json');
  } else {
    const ids = (fs.readFileSync(path.join(__dirname, 'jxids.txt'), 'utf8').match(/\d{15,}/g) || []);
    if (!ids.length) { console.log('缺 tools/jxids.txt'); process.exit(1); }
    const id = ids[0];
    console.log('请求 aweme/detail id=' + id);
    raw = await new Promise((resolve, reject) => {
      const url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?' + detailQuery(id);
      const u = new URL(url);
      const r = https.request(
        { hostname: u.hostname, path: u.pathname + u.search, method: 'GET',
          headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/jingxuan', Cookie: ck, Accept: 'application/json, text/plain, */*' },
          timeout: 20000 },
        (res) => {
          let b = '';
          res.on('data', (c) => (b += c));
          res.on('end', () => { console.log('HTTP ' + res.statusCode + ' 长度 ' + b.length); resolve(b); });
        }
      );
      r.on('error', reject);
      r.end();
    });
    fs.writeFileSync(RAW, raw);
  }
  const j = JSON.parse(raw);
  console.log('字节数: ' + raw.length + '  status_code=' + j.status_code + '  有 detail=' + !!j.aweme_detail);
  const v = (j.aweme_detail || {}).video || {};
  const pa = v.play_addr || {};
  const list = pa.url_list || [];
  console.log('uri=' + pa.uri + '  候选数=' + list.length);
  list.forEach((u, i) => console.log('  [' + i + '] ' + u.slice(0, 150)));

  const H = { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ck, Accept: '*/*' };
  for (let i = 0; i < list.length; i++) {
    const r = await follow(list[i], H, 'url_list[' + i + '] ' + new URL(list[i]).hostname);
    console.log('=> ' + r.verdict + '  ' + r.label + '  ' + r.chain.join(' | '));
  }
  const selfBuilt = 'https://www.douyin.com/aweme/v1/play/?video_id=' + pa.uri + '&ratio=1080p&line=0&item_id=' + j.aweme_detail.aweme_id;
  const rb = await follow(selfBuilt, H, 'selfBuilt(现 App 回退分支)');
  console.log('=> ' + rb.verdict + '  ' + rb.label + '  ' + rb.chain.join(' | '));

  // 推荐页 DOM 里页面自己在用的签名地址（带 biz_sign）
  const domFile = path.join(__dirname, 'dom-signed-urls.json');
  if (fs.existsSync(domFile)) {
    const domUrls = JSON.parse(fs.readFileSync(domFile, 'utf8'));
    for (let i = 0; i < domUrls.length; i++) {
      const s = domUrls[i];
      const r = await follow(s, H, 'DOM推荐页[' + i + '] len=' + s.length + ' biz_sign=' + (/[?&]biz_sign=/.test(s)));
      console.log('=> ' + r.verdict + '  ' + r.label + '  ' + r.chain.join(' | '));
    }
  }
  console.log('\n注意: 探活用的 Range/头与 MediaPlayer 实际发的不完全相同，最终结论以真机 PLAYING 为准');
})().catch((e) => { console.log('FAIL: ' + e.message); process.exit(1); });
