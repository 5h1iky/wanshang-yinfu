// 定判：推荐页的推荐数据是不是 SSR 在 HTML 里（#RENDER_DATA）
// 若是 → 原生带 cookie GET 该页即可拿到与网站同源同质的推荐，不需要 WebView 抓 DOM、也不需要驱动播放器
// 只读。用法: node tools/render-data-probe.js
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
function cookieHeader() {
  const list = JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'));
  const seen = {}; const out = [];
  for (const c of list) { if (seen[c.name]) continue; seen[c.name] = 1; out.push(c.name + '=' + c.value); }
  return out.join('; ');
}

(async () => {
  const url = 'https://www.douyin.com/?recommend=1&from_nav=1';
  const res = await fetch(url, { headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: cookieHeader(), Accept: 'text/html' } });
  const html = await res.text();
  console.log('HTTP ' + res.status + '  HTML 长度 ' + html.length);
  fs.writeFileSync(path.join(__dirname, 'rec-page.html'), html);

  const m = /<script id="RENDER_DATA" type="application\/json">([\s\S]*?)<\/script>/.exec(html);
  if (!m) {
    console.log('无 #RENDER_DATA。找到的内联 script id: ' +
      (html.match(/<script[^>]*\bid="[^"]*"/g) || []).slice(0, 10).join(' | '));
    console.log('页面里出现 aweme 字样次数: ' + (html.match(/aweme/g) || []).length);
    return;
  }
  const json = JSON.parse(decodeURIComponent(m[1]));
  console.log('#RENDER_DATA 解码后 ' + m[1].length + ' 字符，顶层键: ' + Object.keys(json).join(', '));
  const loader = json.loaderData || {};
  console.log('loaderData 键: ' + Object.keys(loader).join(', '));
  const home = loader.homePage || {};
  console.log('homePage 键: ' + Object.keys(home).join(', '));
  const ctx = home.videoInfoContext || home.awemeInfos || home.recommendFeeds || {};
  console.log('videoInfoContext 键: ' + Object.keys(ctx).join(', '));
  const feeds = ctx.feed || ctx.recommendFeeds || [];
  console.log('\n>>> feed 条数: ' + (Array.isArray(feeds) ? feeds.length : typeof feeds));
  if (Array.isArray(feeds) && feeds.length) {
    const f = feeds[0];
    console.log('>>> 首条键: ' + Object.keys(f).slice(0, 25).join(','));
    const v = f.video || {};
    console.log('>>> video 键: ' + Object.keys(v).join(','));
    const pa = v.play_addr || {};
    console.log('>>> play_addr.uri=' + pa.uri + '  url_list 条数=' + (pa.url_list || []).length);
    (pa.url_list || []).forEach((u, i) => console.log('    [' + i + '] ' + u.slice(0, 130)));
    console.log('>>> bit_rate 档数=' + ((v.bit_rate || []).length));
    (v.bit_rate || []).slice(0, 6).forEach(b => console.log('    gear=' + b.gear_name + ' quality_type=' + b.quality_type + ' br=' + b.bit_rate + 'H'));
    console.log('\n前 12 条 (id | 标题 | 作者):');
    feeds.slice(0, 12).forEach(x => console.log('  ' + x.aweme_id + ' 「' + String(x.desc || '').slice(0, 22) + '」 @' + ((x.author || {}).nickname || '')));
  }
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
