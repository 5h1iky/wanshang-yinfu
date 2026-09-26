// 列出 WebView CookieStore 中指定域的 cookie 键名（⚠️ 只输出名称/域/过期时间，绝不输出值）
// 用法：node cdp-cookies.js [url关键字]
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
  ws.onopen = () => send('Network.getCookies', { urls: ['https://www.douyin.com/'] });
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id === id) {
      const cookies = (msg.result && msg.result.cookies) || [];
      const names = cookies.map((c) => c.name);
      console.log('cookie键名(' + names.length + '): ' + names.join(', '));
      const sess = cookies.filter((c) => /session|sid|uid|passport/i.test(c.name));
      for (const c of sess) {
        console.log('  ' + c.name + ' domain=' + c.domain + ' path=' + c.path +
          ' httpOnly=' + !!c.httpOnly + ' session=' + !!c.session +
          ' expires=' + (c.expires > 0 ? new Date(c.expires * 1000).toISOString() : 'session'));
      }
      ws.close();
      process.exit(0);
    }
  };
  ws.onerror = (e) => { console.log('WS 错误: ' + (e.message || e)); process.exit(3); };
  setTimeout(() => { console.log('超时'); process.exit(4); }, 15000);
}

main().catch((e) => { console.log('FAIL: ' + e.message); process.exit(1); });
