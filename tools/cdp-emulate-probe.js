// 用 CDP Emulation.setDeviceMetricsOverride 给隐藏 WebView「造」一个真实视口，
// 再跑同一个探针，看 rect / 交互区是否恢复 → 零改动验证「0×0 视口」根因假设
// 用法: node tools/cdp-emulate-probe.js <宽> <高> @探针文件
const http = require('http');
const fs = require('fs');

const W = parseInt(process.argv[2] || '1080', 10);
const H = parseInt(process.argv[3] || '1800', 10);
let exprFile = process.argv[4] || '@tools/probe-viewport-editor.js';
const expr = exprFile.startsWith('@') ? fs.readFileSync(exprFile.slice(1), 'utf8') : exprFile;

function getTargets() {
  return new Promise((resolve, reject) => {
    http.get('http://127.0.0.1:9222/json', (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async function main() {
  const targets = await getTargets();
  const pages = targets.filter((t) => t.type === 'page');
  const pick = pages[0];
  if (!pick) { console.log('无页面目标'); process.exit(2); }
  console.log('# 目标: ' + pick.url.slice(0, 90) + '  模拟视口 ' + W + 'x' + H);

  const ws = new WebSocket(pick.webSocketDebuggerUrl);
  let id = 0;
  const send = (method, params) => { const i = ++id; ws.send(JSON.stringify({ id: i, method, params })); return i; };
  let evalId = 0;

  ws.onopen = () => {
    send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 2.75, mobile: false });
    // 覆盖度量后需要一帧让布局重算，稍后再求值
    setTimeout(() => { evalId = send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); }, 1200);
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id !== evalId) return;
    if (msg.result && msg.result.exceptionDetails) {
      console.log('JS 异常: ' + JSON.stringify(msg.result.exceptionDetails.text));
      const d = msg.result.exceptionDetails.exception;
      if (d) console.log(JSON.stringify(d.description || d.value || ''));
    } else {
      const r = msg.result.result;
      console.log(typeof r.value === 'string' ? r.value : JSON.stringify(r.value));
    }
    send('Emulation.clearDeviceMetricsOverride', {});
    setTimeout(() => process.exit(0), 400);
  };
  ws.onerror = (e) => { console.log('WS 错误: ' + (e.message || e)); process.exit(3); };
  setTimeout(() => { console.log('超时'); process.exit(4); }, 25000);
})();
