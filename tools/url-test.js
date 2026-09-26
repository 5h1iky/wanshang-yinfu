// 播放流地址实测 v2：正经解析 aweme_list[].video.play_addr.url_list，
// 逐 URL × 多请求头组合（含 Cookie）测状态码（只打状态/长度，不回显完整 URL）
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

function loadCookies() {
  try {
    const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
    const m = xml.match(/<string name="cookies">([^<]+)<\/string>/);
    return m ? m[1] : '';
  } catch (e) { return ''; }
}

(async () => {
  const j = JSON.parse(fs.readFileSync(path.join(__dirname, 'm05-feed-result.json'), 'utf8'));
  const list = j.data && j.data.aweme_list ? j.data.aweme_list : j.aweme_list;
  if (!list) { console.log('无 aweme_list，顶层键: ' + Object.keys(j).join(',')); return; }
  const cookieStr = loadCookies();
  const ttwid = (cookieStr.match(/ttwid=[^;]+/) || [''])[0];
  const combos = [
    ['无头', {}],
    ['UA+Referer', { 'User-Agent': UA, Referer: 'https://www.douyin.com/' }],
    ['UA+Referer+ttwid', { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: ttwid }],
    ['UA+Referer+全Cookie', { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: cookieStr }],
  ];
  for (let a = 0; a < Math.min(list.length, 2); a++) {
    const pa = list[a].video && list[a].video.play_addr;
    const urls = (pa && pa.url_list) || [];
    console.log('--- 视频#' + a + ' url_list 候选数=' + urls.length);
    for (let u = 0; u < urls.length; u++) {
      for (const [name, headers] of combos) {
        try {
          const res = await fetch(urls[u], { headers: { ...headers, Range: 'bytes=0-1023' }, redirect: 'manual' });
          const buf = await res.arrayBuffer();
          console.log('  [' + u + '] ' + name + ' -> HTTP ' + res.status + ' ' + buf.byteLength + 'B ' + (res.headers.get('content-type') || ''));
        } catch (e) {
          console.log('  [' + u + '] ' + name + ' -> ERR ' + e.message);
        }
      }
    }
  }
})();
