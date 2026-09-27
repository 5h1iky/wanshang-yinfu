// 在 uiautomator dump 里反查 sb_progress 的祖先链（谁把它裁成 0×0）。
// 用法：node ui-ancestors.js <dump.xml> <id子串>
const fs = require('fs');
const p = process.argv[2], filter = process.argv[3];
let buf = fs.readFileSync(p);
let t;
if (buf[0] === 0xFF && buf[1] === 0xFE) t = buf.swap16().toString('utf16le');
else t = buf.toString('utf8').replace(/^\uFEFF/, '');
// 简化处理：XML 层级由嵌套 <node> 表达。用栈配对还原祖先链。
const tokens = t.match(/<node[^>]*\/?>|<\/node>/g) || [];
const stack = [];
for (const tk of tokens) {
  if (tk.startsWith('</')) { stack.pop(); continue; }
  const selfClose = tk.endsWith('/>');
  const id = (tk.match(/resource-id="([^"]*)"/) || [])[1] || '';
  const cls = (tk.match(/class="([^"]*)"/) || [])[1] || '';
  const bounds = (tk.match(/bounds="([^"]*)"/) || [])[1] || '';
  if (filter && id.includes(filter)) {
    console.log('TARGET ' + cls + ' id=' + id + ' ' + bounds);
    console.log('  ancestors:');
    stack.forEach((n, i) => console.log('  ' + ' '.repeat(i) + '^ ' + n.cls + ' ' + n.bounds));
    return;
  }
  const entry = { cls, bounds };
  stack.push(entry);
  if (selfClose) stack.pop();
}
function nope() { console.log('(not found)'); }
