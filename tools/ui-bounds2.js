// 提取 dump 节点 bounds 的原始值（ui-find.js 的过滤参数是子串匹配，id 复合过滤用脚本做）。
// 用法：node ui-bounds2.js <dump.xml> <id1,id2,...>
const fs = require('fs');
const p = process.argv[2];
const ids = (process.argv[3] || '').split(',').filter(Boolean);
let buf = fs.readFileSync(p);
let t;
if (buf[0] === 0xFF && buf[1] === 0xFE) t = buf.swap16().toString('utf16le');
else t = buf.toString('utf8').replace(/^\uFEFF/, '');
const re = /<node[^>]*\/?>/g;
let m;
while ((m = re.exec(t)) !== null) {
  const node = m[0];
  const id = (node.match(/resource-id="([^"]*)"/) || [])[1] || '';
  if (!ids.some(x => id.includes(x))) continue;
  const text = (node.match(/text="([^"]*)"/) || [])[1] || '';
  const bounds = (node.match(/bounds="([^"]*)"/) || [])[1] || '';
  const [l, tp, r, b] = bounds.replace(/[\[\]]/g, ',').split(',').filter(s => s !== '').map(Number);
  const h = b - tp, w = r - l;
  console.log(`id=${id.replace('com.dywatch.app:id/', '')} text="${decodeURIComponent(text.replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(n)))}" bounds=${bounds} w=${w} h=${h}`);
}
