// 资源 XML 良构性快检（不需要真解析器，专抓"删属性把闭合 > 一起删掉"这类手滑）
// 用法: node tools/xml-check.js [res目录]
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || 'app/src/main/res';

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.xml')) out.push(p);
  }
  return out;
}

let bad = 0;
for (const f of walk(root, [])) {
  const s = fs.readFileSync(f, 'utf8');
  // 逐个标签扫描：从 '<' 开始，必须在遇到下一个 '<' 之前遇到 '>' 或 '/>'
  for (let i = 0; i < s.length; i++) {
    if (s[i] !== '<') continue;
    if (s[i + 1] === '!' || s[i + 1] === '?') { i = s.indexOf('>', i); continue; }
    const nextTag = s.indexOf('<', i + 1);
    const close = s.indexOf('>', i + 1);
    if (close === -1 || (nextTag !== -1 && nextTag < close)) {
      const line = s.slice(0, i).split('\n').length;
      console.log('BAD ' + f + ':' + line + '  标签未闭合 → ' + JSON.stringify(s.slice(i, i + 40).replace(/\s+/g, ' ')));
      bad++;
      break;
    }
    i = close;
  }
}
console.log(bad ? ('共 ' + bad + ' 个文件有问题') : '全部良构');
process.exit(bad ? 1 : 0);
