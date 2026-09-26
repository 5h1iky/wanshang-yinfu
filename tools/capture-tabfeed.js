// 抓推荐页首屏的 tab/feed 完整请求（含 aweme_pc_rec_raw_data 全貌），存 tools/tabfeed-full.txt
// 做法：先挂 Network 域，再重新导航，这样首屏那条 feed 不会被漏掉
const http = require('http');
const fs = require('fs');
const URL_TARGET = 'https://www.douyin.com/?recommend=1&from_nav=1';

function targets() {
  return new Promise((res, rej) => http.get('http://127.0.0.1:9222/json', r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej));
}

(async () => {
  const t = (await targets()).filter(x => x.type === 'page')[0];
  if (!t) { console.log('无页面（App 开着？CDP forward 了吗？）'); process.exit(2); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0; const pend = new Map(); const hits = [];
  const send = (m, p) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pend.has(msg.id)) { pend.get(msg.id)(msg.result || msg); pend.delete(msg.id); return; }
    if (msg.method === 'Network.requestWillBeSent') {
      const u = msg.params.request.url;
      if (/tab\/feed/.test(u)) { hits.push(u); console.log('### 抓到第 ' + hits.length + ' 条 tab/feed'); }
    }
  };
  await new Promise(r => ws.onopen = r);
  await send('Network.enable', {});
  await send('Page.navigate', { url: URL_TARGET });
  await new Promise(r => setTimeout(r, 22000));
  if (!hits.length) { console.log('没抓到 tab/feed'); process.exit(3); }
  fs.writeFileSync(__dirname + '/tabfeed-full.txt', hits[hits.length - 1]);
  const q = new URL(hits[hits.length - 1]).search.slice(1);
  console.log('=== 参数全貌（共 ' + q.split('&').length + ' 个）');
  q.split('&').forEach(p => {
    const k = p.split('=')[0];
    const v = decodeURIComponent(p.slice(k.length + 1));
    console.log(k.padEnd(24) + '= ' + (v.length > 240 ? v.slice(0, 240) + ' …[' + v.length + '字符]' : v));
  });
  ws.close(); process.exit(0);
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
