// 跟随 302 看最终资源是否可播
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

(async () => {
  const j = JSON.parse(fs.readFileSync(path.join(__dirname, 'm05-feed-result.json'), 'utf8'));
  const list = j.data && j.data.aweme_list ? j.data.aweme_list : j.aweme_list;
  for (let a = 0; a < Math.min(list.length, 2); a++) {
    const urls = list[a].video.play_addr.url_list;
    const u = urls[2];
    console.log('--- 视频#' + a + ' 候选[2] 域名=' + new URL(u).host);
    try {
      const res = await fetch(u, {
        headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Range: 'bytes=0-1023' },
        redirect: 'follow',
      });
      const buf = await res.arrayBuffer();
      console.log('  跟随后: HTTP ' + res.status + ' ' + buf.byteLength + 'B ct=' + (res.headers.get('content-type') || '') + ' finalHost=' + new URL(res.url).host);
    } catch (e) {
      console.log('  ERR ' + e.message);
    }
  }
})();
