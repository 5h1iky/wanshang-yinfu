// 读 uiautomator dump（可能 UTF-8 或 UTF-16），按 resource-id 过滤打印节点摘要。
// 用法：node ui-find.js <dump.xml> <id子串>
const fs = require('fs');
const p = process.argv[2];
const filter = process.argv[3] || '';
let buf = fs.readFileSync(p);
let t;
if (buf[0] === 0xFF && buf[1] === 0xFE) t = buf.swap16().toString('utf16le');
else t = buf.toString('utf8').replace(/^\uFEFF/, '');
const re = /<node[^>]*\/?>(?:<\/node>)?/g;
let m, out = [];
while ((m = re.exec(t)) !== null) {
  const node = m[0];
  if (filter && !node.includes(filter)) continue;
  const id = (node.match(/resource-id="([^"]*)"/) || [])[1] || '';
  const text = (node.match(/text="([^"]*)"/) || [])[1] || '';
  const cls = (node.match(/class="([^"]*)"/) || [])[1] || '';
  const bounds = (node.match(/bounds="([^"]*)"/) || [])[1] || '';
  if (!filter && !text) continue; // 无过滤时只列出有文本的节点
  out.push(`[${cls}] id=${id} text="${text}" ${bounds}`);
}
console.log(out.join('\n') || '(no match)');
