// 判定实验 v2：看真实发出的 cookie（requestWillBeSentExtraInfo）+ 每个 cookie 的拦截原因
// 只输出键名/布尔/blockedReason，绝不输出值。
// 用法：node cdp-netcheck2.js [url关键字]
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
    send('Network.enable', {});
    setTimeout(() => {
      evalId.i = id + 1;
      send('Runtime.evaluate', {
        expression:
          "fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC',{credentials:'include'}).then(function(r){return 'HTTP '+r.status+' ';}).then(function(h){return h;}).catch(function(e){return 'ERR '+e;})",
        returnByValue: true,
        awaitPromise: true,
      });
    }, 300);
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.method === 'Network.requestWillBeSentExtraInfo' && msg.params && msg.params.request) {
      const u = msg.params.request.url;
      if (u.includes('profile/self')) {
        const h = msg.params.request.headers || {};
        const cookie = h['Cookie'] || h['cookie'] || '';
        const names = cookie.split(';').map((p) => p.split('=')[0].trim()).filter(Boolean);
        console.log('[ExtraInfo] 实发 cookie 键名(' + names.length + '): ' + names.join(','));
        console.log('带 sessionid=' + names.includes('sessionid') + ' 带 sid_tt=' + names.includes('sid_tt') + ' 带 sid_guard=' + names.includes('sid_guard') + ' 带 ttwid=' + names.includes('ttwid'));
        const ac = msg.params.associatedCookies || [];
        const blocked = ac.filter((c) => c.blockedReasons && c.blockedReasons.length);
        console.log('关联cookie总数=' + ac.length + ' 被拦截=' + blocked.length);
        for (const c of blocked) {
          console.log('  拦截: ' + (c.cookie ? c.cookie.name : '?') + ' -> ' + JSON.stringify(c.blockedReasons));
        }
      }
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
