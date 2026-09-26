// 判定实验 v3（Fetch 域拦截）：暂停真实请求，检查请求头里的 Cookie（只打键名）
// 用法：node cdp-netcheck3.js [url关键字]
const http = require('http');

function getTargets() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve(JSON.parse(body)));
    }).on('error', reject);
  });
}

async function main() {
  const urlFilter = process.argv[2] || '';
  const targets = await getTargets();
  const pages = targets.filter((t) => t.type === 'page');
  const pick = pages.find((t) => t.url.includes(urlFilter)) || pages[0];
  if (!pick) { console.log('无页面目标'); process.exit(2); }
  console.log('# 目标: ' + pick.url.slice(0, 90));
  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params) => ws.send(JSON.stringify({ id: ++id, method, params }));
  const evalId = { i: 0 };
  ws.onopen = () => {
    send('Fetch.enable', { patterns: [{ urlPattern: '*profile/self*' }] });
    setTimeout(() => {
      evalId.i = id + 1;
      send('Runtime.evaluate', {
        expression:
          "fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC',{credentials:'include'}).then(function(r){return r.text();}).then(function(t){return 'len='+t.length+' 未登录='+(t.indexOf('未登录')>=0)+' hasUid='+/\"uid\"\\s*:\\s*\\d/.test(t);}).catch(function(e){return 'ERR '+e;})",
        returnByValue: true,
        awaitPromise: true,
      });
    }, 300);
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.method === 'Fetch.requestPaused') {
      const req = msg.params.request || {};
      const h = req.headers || {};
      const cookie = h['Cookie'] || h['cookie'] || '';
      const names = cookie.split(';').map((p) => p.split('=')[0].trim()).filter(Boolean);
      console.log('[Fetch拦截] URL含profile/self=' + String(req.url || '').includes('profile/self'));
      console.log('实发 cookie 键名(' + names.length + '): ' + names.join(','));
      console.log('带 sessionid=' + names.includes('sessionid') + ' 带 sid_tt=' + names.includes('sid_tt') + ' 带 sid_guard=' + names.includes('sid_guard') + ' 带 ttwid=' + names.includes('ttwid'));
      send('Fetch.continueRequest', { requestId: msg.params.requestId });
    }
    if (msg.id === evalId.i) {
      const r = msg.result && msg.result.result;
      console.log('fetch结果: ' + (r && r.value));
      ws.close();
      process.exit(0);
    }
  };
  ws.onerror = (e) => { console.log('WS 错误: ' + (e.message || e)); process.exit(3); };
  setTimeout(() => { console.log('超时'); process.exit(4); }, 20000);
}

main().catch((e) => { console.log('FAIL: ' + e.message); process.exit(1); });
