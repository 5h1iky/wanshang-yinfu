// CDP 远程执行工具：连接 adb forward 的 WebView 调试端口，在页面上下文里执行 JS
// 前置：adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
// 用法：node cdp-eval.js '<JS 表达式>' [url关键字]
// 例：node cdp-eval.js "document.title"
const http = require('http');

function getTargets() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch (e) {
          reject(new Error('CDP 目标解析失败: ' + body.slice(0, 120)));
        }
      });
    }).on('error', reject);
  });
}

async function main() {
  let expr = process.argv[2];
  const urlFilter = process.argv[3] || '';
  if (expr && expr.startsWith('@')) {
    expr = require('fs').readFileSync(expr.slice(1), 'utf8'); // @文件路径：避免 shell 转义之苦
  }
  if (!expr) {
    console.log('用法: node cdp-eval.js \'<JS 表达式>\'|@文件 [url关键字]');
    process.exit(1);
  }
  const targets = await getTargets();
  const pages = targets.filter((t) => t.type === 'page');
  const pick = pages.find((t) => t.url.includes(urlFilter)) || pages[0];
  if (!pick) {
    console.log('无可用页面目标。当前目标数: ' + targets.length);
    console.log(JSON.stringify(targets.map((t) => ({ type: t.type, url: t.url })), null, 1));
    process.exit(2);
  }
  console.log('# 目标: ' + pick.url.slice(0, 90));
  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params) =>
    ws.send(JSON.stringify({ id: ++id, method, params }));
  ws.onopen = () => {
    send('Runtime.evaluate', {
      expression: expr,
      returnByValue: true,
      awaitPromise: true,
    });
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id === id) {
      const r = msg.result && msg.result.result;
      if (msg.result && msg.result.exceptionDetails) {
        console.log('JS 异常: ' + JSON.stringify(msg.result.exceptionDetails.text));
        const d = msg.result.exceptionDetails.exception;
        if (d) console.log(JSON.stringify(d.description || d.value || ''));
      } else {
        console.log(typeof r.value === 'string' ? r.value : JSON.stringify(r.value, null, 1));
      }
      ws.close();
      process.exit(0);
    }
  };
  ws.onerror = (e) => {
    console.log('WS 错误: ' + (e.message || e));
    process.exit(3);
  };
  setTimeout(() => {
    console.log('超时');
    process.exit(4);
  }, 15000);
}

main().catch((e) => {
  console.log('FAIL: ' + e.message);
  process.exit(1);
});
