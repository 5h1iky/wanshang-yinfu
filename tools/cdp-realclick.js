// 用 CDP Input 域发"受信任"的真实鼠标事件（合成 JS 事件页面不认时用这个定判）
// 用法: node tools/cdp-realclick.js <x> <y> [待读选择器]
const http = require('http');
const X = parseFloat(process.argv[2]), Y = parseFloat(process.argv[3]);
const READ = process.argv[4] || '[data-e2e-aweme-id]';

function targets() {
  return new Promise((res, rej) => http.get('http://127.0.0.1:9222/json', r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej));
}

(async () => {
  const t = (await targets()).filter(x => x.type === 'page')[0];
  if (!t) { console.log('无页面'); process.exit(2); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0;
  const pend = new Map();
  const send = (m, p) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  const read = async () => {
    const r = await send('Runtime.evaluate', { expression: 'JSON.stringify([].slice.call(document.querySelectorAll(' + JSON.stringify(READ) + ')).map(function(e){return e.getAttribute("data-e2e-aweme-id")||e.getAttribute("data-e2e-vid")||e.textContent.slice(0,20)}))', returnByValue: true });
    try { return JSON.parse(r.result.result.value); } catch (e) { return r.result; }
  };
  ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pend.has(m.id)) { pend.get(m.id)(m.result || m); pend.delete(m.id); } };
  await new Promise(r => ws.onopen = r);
  await send('Runtime.enable', {});
  const before = await read();
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', {
      type, x: X, y: Y, button: 'left', clickCount: type === 'mouseMoved' ? 0 : 1,
      buttons: type === 'mouseReleased' ? 0 : 1, pointerType: 'mouse'
    });
  }
  await new Promise(r => setTimeout(r, 4500));
  const after = await read();
  console.log('点击前: ' + JSON.stringify(before));
  console.log('点击后: ' + JSON.stringify(after));
  console.log('变化: ' + (JSON.stringify(before) !== JSON.stringify(after) ? 'YES 页面认这套输入' : 'NO 页面不认'));
  ws.close(); process.exit(0);
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
