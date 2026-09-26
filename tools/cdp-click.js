// CDP 真实点击工具：用 Input.dispatchMouseEvent 点元素中心（可信事件，SPA 认）
// 用法：node cdp-click.js '<CSS选择器>' [url过滤]
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
  const selector = process.argv[2];
  const urlFilter = process.argv[3] || '';
  if (!selector) { console.log('用法: node cdp-click.js <选择器> [url]'); process.exit(1); }
  const targets = await getTargets();
  const pick = targets.filter((t) => t.type === 'page').find((t) => t.url.includes(urlFilter))
    || targets.filter((t) => t.type === 'page')[0];
  if (!pick) { console.log('无页面目标'); process.exit(2); }
  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params) => ws.send(JSON.stringify({ id: ++id, method, params }));
  ws.onopen = () => {
    send('Runtime.evaluate', {
      expression: `(function(){var e=document.querySelector(${JSON.stringify(selector)});
        if(!e)return 'no';e.scrollIntoView({block:'center'});return 'ok';})()`,
      returnByValue: true,
    });
    setTimeout(() => {
      send('Runtime.evaluate', {
        expression: `(function(){var e=document.querySelector(${JSON.stringify(selector)});
          if(!e)return null;var r=e.getBoundingClientRect();
          return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,w:r.width,h:r.height});})()`,
        returnByValue: true,
      });
    }, 700);
  };
  let rectDone = false;
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id === 2 && !rectDone) {
      rectDone = true;
      const v = msg.result && msg.result.result && msg.result.result.value;
      if (!v) { console.log('元素未找到'); ws.close(); process.exit(3); }
      const r = JSON.parse(v);
      console.log('元素中心 ' + Math.round(r.x) + ',' + Math.round(r.y) + ' (' + Math.round(r.w) + 'x' + Math.round(r.h) + ')');
      if (r.y < 0 || r.y > 2400) { console.log('元素不在视口内，放弃点击'); ws.close(); process.exit(6); }
      const p = { x: r.x, y: r.y, button: 'left', clickCount: 1 };
      send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
      send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
      setTimeout(() => { console.log('已真实点击'); ws.close(); process.exit(0); }, 400);
    }
  };
  ws.onerror = (e) => { console.log('WS 错误'); process.exit(4); };
  setTimeout(() => { console.log('超时'); process.exit(5); }, 15000);
}

main().catch((e) => { console.log('FAIL: ' + e.message); process.exit(1); });
