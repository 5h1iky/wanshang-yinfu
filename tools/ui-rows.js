// 列出 app 自己 UI（引擎 WebView 之后的部分）的节点：text + bounds + clickable
const fs = require('fs');
const src = fs.readFileSync(process.argv[2] || 'shots/uid.xml', 'utf8');
const nodes = src.split('<node').slice(1);
nodes.forEach((n, i) => {
  const tm = n.match(/text="([^"]*)"/);
  const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  const cm = n.match(/clickable="([^"]*)"/);
  const idm = n.match(/resource-id="([^"]*)"/);
  const t = tm ? tm[1] : '';
  if (!t) return;
  // 只关心 app 自己的会话行：昵称 + 时间 + 摘要，或页名
  if (/^(会话|5h1iky|漫小鬼|艺薛|屿\.|一根忧郁的钢笔|麦小鼠大王|取名简直要我命)$/.test(t)
      || /^(15:16|13:21|前天)$/.test(t)) {
    console.log(`#${i} text=${t.padEnd(22)} clickable=${cm ? cm[1] : '?'} bounds=${bm ? bm[0] : '?'} id=${idm ? idm[1] : ''}`);
  }
});
console.log('--- rv_convs 容器 ---');
nodes.forEach((n) => {
  const idm = n.match(/resource-id="([^"]*)"/);
  if (idm && idm[1].includes('rv_')) {
    const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    console.log(idm[1] + ' ' + (bm ? bm[0] : '?'));
  }
});
