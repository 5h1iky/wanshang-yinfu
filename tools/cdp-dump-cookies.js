// 导出 WebView CookieStore 全量（含值）到本地文件，用于与备份会话对拼（仅落盘 tools/，不进日志/聊天）
const http = require('http');
const fs = require('fs');

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
  const targets = await getTargets();
  const pick = targets.filter((t) => t.type === 'page')[0];
  if (!pick) { console.log('无页面目标'); process.exit(2); }
  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params) => ws.send(JSON.stringify({ id: ++id, method, params }));
  ws.onopen = () => send('Network.getCookies', { urls: ['https://www.douyin.com/'] });
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id === id) {
      const cookies = (msg.result && msg.result.cookies) || [];
      fs.writeFileSync(__dirname + '/webview-cookies.json', JSON.stringify(cookies, null, 1));
      console.log('已导出 ' + cookies.length + ' 个 cookie -> tools/webview-cookies.json');
      ws.close();
      process.exit(0);
    }
  };
  ws.onerror = (e) => { console.log('WS 错误: ' + (e.message || e)); process.exit(3); };
  setTimeout(() => { console.log('超时'); process.exit(4); }, 15000);
}

main().catch((e) => { console.log('FAIL: ' + e.message); process.exit(1); });
