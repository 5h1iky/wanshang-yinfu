// 推荐页首屏到底从哪个请求拿到视频列表：抓所有 XHR/Fetch，读响应体，标出含 aweme 列表特征的那条
// 用法: node tools/capture-first-batch.js [导航URL]
const http = require('http');
const NAV = process.argv[2] || 'https://www.douyin.com/?recommend=1&from_nav=1';

function targets() {
  return new Promise((res, rej) => http.get('http://127.0.0.1:9222/json', r => {
    let b = ''; r.on('data', c => b += c); r.on('end', () => { try { res(JSON.parse(b)); } catch (e) { rej(e); } });
  }).on('error', rej));
}

(async () => {
  const t = (await targets()).filter(x => x.type === 'page')[0];
  if (!t) { console.log('无页面目标'); process.exit(2); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  let id = 0; const pend = new Map();
  const byId = {};
  const send = (m, p) => new Promise(r => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  ws.onmessage = async ev => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pend.has(msg.id)) { pend.get(msg.id)(msg.result || msg); pend.delete(msg.id); return; }
    if (msg.method === 'Network.requestWillBeSent') {
      const r = msg.params.request;
      if (msg.params.type === 'XHR' || msg.params.type === 'Fetch') byId[msg.params.requestId] = r.url;
    }
    if (msg.method === 'Network.loadingFinished') {
      const u = byId[msg.params.requestId];
      if (!u) return;
      if (/mon\.zijieapi|vcs\.zijieapi|log-sdk|slardar|applog|mcs\/|ab\/params/.test(u)) return;
      let body = '';
      try {
        const r = await send('Network.getResponseBody', { requestId: msg.params.requestId });
        body = r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : (r.body || '');
      } catch (e) { return; }
      const hasList = /"aweme_list"\s*:/.test(body);
      const idsSeen = (body.match(/"aweme_id"\s*:\s*"(\d{15,})"/g) || []).length;
      const ep = u.split('?')[0].replace(/^https?:\/\//, '');
      if (hasList || idsSeen > 0 || /feed|recommend|multi\/aweme/.test(u)) {
        console.log((hasList ? '★★★ aweme_list 在此 ' : idsSeen ? '☆ 含' + idsSeen + '个id   ' : '            ')
          + ep + '  [' + body.length + 'B]');
        if (hasList) {
          const m = /"aweme_list"\s*:\s*\[/.exec(body);
          const idlist = [...body.matchAll(/"aweme_id"\s*:\s*"(\d{15,})"/g)].map(x => x[1]).slice(0, 6);
          console.log('     前几条 id: ' + idlist.join(', '));
          require('fs').writeFileSync(__dirname + '/first-batch.json', body);
          console.log('     响应体已存 tools/first-batch.json');
        }
      }
    }
  };
  await new Promise(r => ws.onopen = r);
  await send('Network.enable', {});
  await send('Page.navigate', { url: NAV });
  await new Promise(r => setTimeout(r, 25000));
  console.log('\n完成。若上面没有 ★★★ 行，说明首屏列表不来自 XHR。');
  process.exit(0);
})().catch(e => { console.log('FAIL: ' + e.message); process.exit(1); });
