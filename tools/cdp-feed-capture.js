// 捕获 PC 推荐页真实发出的 feed 请求：完整 query 参数 + 实发请求头（Cookie 只列键名）
// 目的：把原生 DouyinApi.buildFeedQuery 的口径对齐到电脑端，让推荐内容一致
// 用法: node tools/cdp-feed-capture.js [导航URL]
const http = require('http');
const NAV = process.argv[2] || 'https://www.douyin.com/?recommend=1';

function getTargets() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async function main() {
  const targets = await getTargets();
  const pages = targets.filter((t) => t.type === 'page');
  const pick = pages[0];
  if (!pick) { console.log('无页面目标（App 是否开着？CDP 是否已 forward？）'); process.exit(2); }
  console.log('# 目标: ' + pick.url.slice(0, 80) + '\n# 导航到: ' + NAV);

  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (m, p) => ws.send(JSON.stringify({ id: ++id, method: m, params: p }));
  const seen = { urls: [], heads: [], paths: {} };

  ws.onopen = () => {
    // 引擎 WebView 是 0×0 不可见窗口，推荐页「可见才加载」→ 先造一个真实视口再导航
    // （传 noemu 则故不覆盖：用于验证 App 自己挂载后的真视口能不能驱动页面加载）
    if (process.argv[3] !== 'noemu') {
      send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
    }
    send('Network.enable', {});
    send('Fetch.enable', { patterns: [{ urlPattern: '*aweme/v1/web*' }] });
    setTimeout(() => send('Page.navigate', { url: NAV }), 500);
    // 首屏 feed 发出后，再滚几轮拉翻页（用 scrollHeight 而非固定值，适应 800 高视口）
    setTimeout(() => send('Runtime.evaluate', { expression: 'window.scrollTo(0, 1200); setTimeout(function(){window.scrollTo(0,3000);},2000); setTimeout(function(){window.scrollTo(0,6000);},5000); "scrolled"' }), 10000);
  };

  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.method === 'Network.requestWillBeSent') {
      const u = msg.params.request.url || '';
      const path = (u.split('?')[0] || '').replace('https://www.douyin.com', '');
      // 先记录所有 /aweme/v1/web/ 接口（看 PC 到底用哪个 endpoint 拉推荐），重点看 feed/recommend
      if (/\/aweme\/v1\/web\//.test(u) && !seen.paths[path]) {
        seen.paths[path] = true;
        const isFeed = /feed|recommend/.test(u);
        console.log('\n=== ' + (isFeed ? '★feed类' : '接口') + ' ' + path + ' ===');
        console.log('参数名: ' + (u.split('?')[1] || '').split('&').map((p) => p.split('=')[0]).join(','));
        if (isFeed) { seen.urls.push(u); console.log('完整URL: ' + u.slice(0, 1200)); }
      }
    }
    if (msg.method === 'Fetch.requestPaused') {
      const req = msg.params.request || {};
      const h = req.headers || {};
      if (!/feed|recommend/.test(req.url || '')) { send('Fetch.continueRequest', { requestId: msg.params.requestId }); return; }
      const cookie = h['Cookie'] || h['cookie'] || '';
      const names = cookie.split(';').map((p) => p.split('=')[0].trim()).filter(Boolean);
      console.log('\n=== 实发请求头 @ ' + String(req.url).slice(0, 70) + ' ===');
      console.log('method=' + req.method + '  hasDtrait=' + (!!h['x-tt-session-dtrait']) + '  secsdk=' + (!!h['x-secsdk-csrf-token']));
      console.log('referer=' + (h['Referer'] || h['referer'] || ''));
      console.log('cookie 键(' + names.length + '): ' + names.join(','));
      console.log('含 sessionid=' + names.includes('sessionid') + ' 含 ttwid=' + names.includes('ttwid') + ' 含 webid参数=' + /webid/.test(req.url || ''));
      seen.heads.push(names);
      send('Fetch.continueRequest', { requestId: msg.params.requestId });
    }
  };
  ws.onerror = (e) => { console.log('WS 错误: ' + (e.message || e)); process.exit(3); };
  setTimeout(() => { console.log('\n# 完成：抓到 feed URL ' + seen.urls.length + ' 条、请求头 ' + seen.heads.length + ' 组'); process.exit(0); }, 26000);
})();
