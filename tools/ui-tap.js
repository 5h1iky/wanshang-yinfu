// 从 dump 里取指定文本节点的中心坐标（取最后一个匹配：app 自己的 UI 在 dump 末尾，
// 前面那些是挂在底下的引擎 WebView 的节点）。
const fs = require('fs');
const file = process.argv[2] || 'shots/uid.xml';
const want = process.argv[3] || '';
const src = fs.readFileSync(file, 'utf8');
const nodes = src.split('<node').slice(1);
let last = null;
for (const n of nodes) {
  const tm = n.match(/text="([^"]*)"/);
  const bm = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (!tm || !bm) continue;
  if (tm[1].indexOf(want) < 0) continue;
  const x1 = +bm[1], y1 = +bm[2], x2 = +bm[3], y2 = +bm[4];
  last = { cx: Math.round((x1 + x2) / 2), cy: Math.round((y1 + y2) / 2), b: [x1, y1, x2, y2], t: tm[1] };
}
if (!last) { console.log('not found: ' + want); process.exit(1); }
console.log(`${last.t} -> tap ${last.cx} ${last.cy}  bounds=[${last.b.join(',')}]`);
