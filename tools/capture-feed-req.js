// 抓"推荐页换一条"瞬间的全部 XHR/Fetch（不按 /aweme/v1/web/ 过滤，避免再次漏掉真正的取流 endpoint）
// 换条用受信任输入（实测只有这个能推动页面）
// 用法: node tools/capture-feed-req.js
const http = require('http');
function targets() {
  return new Promise((res, rej) => http.get('http://127.0.0.1:9222/json', r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const t = (await targets()).filter(x => x.type === 'page')[0];
  if (!t) { console.log('无页面目标'); process.exit(2); }
  console.log('# ' + t.url);
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0; const pend = new Map(); const reqs = [];
  const send = (m, p) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pend.has(msg.id)) { pend.get(msg.id)(msg.result || msg); pend.delete(msg.id); return; }
    if (msg.method === 'Network.requestWillBeSent') {
      const r = msg.params.request, ty = msg.params.type;
      if (ty === 'XHR' || ty === 'Fetch') {
        const u = r.url;
        if (/tab\/feed/.test(u)) { require('fs').writeFileSync(__dirname + '/tabfeed-url.txt', u); console.log('### 抓到 tab/feed 全文，已存盘'); }
        if (/mon\.zijieapi|vcs\.zijieapi|log-sdk|slardar|applog|ab\/params|mcs/.test(u)) return;
        reqs.push({ ty, m: r.method, host: (new URL(u)).hostname, path: (new URL(u)).pathname, q: (new URL(u)).search.slice(1, 260) });
      }
    }
  };
  await new Promise(r => ws.onopen = r);
  await send('Network.enable', {});
  await send('Runtime.enable', {});

  const fromArgv = process.argv[2] && process.argv[3] ? [parseFloat(process.argv[2]), parseFloat(process.argv[3])] : null;
  let xy = fromArgv;
  if (!xy) {
    const rect = await send('Runtime.evaluate', {
      expression: '(function(){var e=document.querySelector("[data-e2e=\\u0027video-switch-next-arrow\\u0027]");if(!e)return "NOBTN";var r=e.getBoundingClientRect();return JSON.stringify([r.x+r.width/2,r.y+r.height/2])})()',
      returnByValue: true
    });
    const val = ((rect || {}).result || {}).value;
    if (!val || val === 'NOBTN') { console.log('取不到下一条按钮坐标，改用参数传: node capture-feed-req.js <x> <y>；原始返回=' + JSON.stringify(rect)); process.exit(3); }
    xy = JSON.parse(val);
  }
  if (!xy) { console.log('找不到下一条按钮（页面是否在推荐页？）'); process.exit(3); }
  console.log('# 下一条按钮 @ ' + xy.map(Math.round));

  const mark = async (label) => { const n = reqs.length; await sleep(4000); return { label, from: n, to: reqs.length }; };
  let spans = [];
  for (let k = 0; k < 3; k++) {
    const s = reqs.length;
    for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased']) {
      await send('Input.dispatchMouseEvent', { type, x: xy[0], y: xy[1], button: 'left', clickCount: type === 'mouseMoved' ? 0 : 1, buttons: type === 'mouseReleased' ? 0 : 1, pointerType: 'mouse' });
    }
    await sleep(4500);
    spans.push({ click: k + 1, from: s, to: reqs.length });
  }
  console.log('\n每次点击新增请求数: ' + spans.map(s => '第' + s.click + '次 +' + (s.to - s.from)).join(', '));
  console.log('\n=== 换条期间出现的请求（去重）');
  const seen = {};
  for (const r of reqs) { const k = r.m + ' ' + r.host + r.path; if (seen[k]) continue; seen[k] = 1; console.log(r.m + '  ' + r.host + r.path + '\n     q: ' + r.q); }
  ws.close(); process.exit(0);
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
