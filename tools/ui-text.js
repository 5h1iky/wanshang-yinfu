// dump 里所有可见文本 + 关键 id（验证 UI 的首选，绕开截图）
// ⚠️ 2026-09-27 修：adb pull 出来的 dump 可能是 UTF-16（BOM 判断），
//    原来固定 utf8 读中文会变成乱码（GBK 控制台 + UTF-16 文件双重坑），
//    导致"明明界面有这行、ui-text 却看不到"的假阴性。
const fs = require('fs');
const buf = fs.readFileSync('D:/dev/dywatch/shots/uid.xml');
const src = (buf[0] === 0xFF && buf[1] === 0xFE)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const texts = [];
const re = /text="([^"]*)"/g;
let m;
while ((m = re.exec(src)) !== null) {
  if (m[1] && m[1].trim()) texts.push(m[1].trim());
}
const ids = [];
const re2 = /resource-id="([^"]*)"/g;
while ((m = re2.exec(src)) !== null) {
  if (m[1] && !ids.includes(m[1])) ids.push(m[1]);
}
console.log('TEXTS:');
texts.forEach(t => console.log('  ' + t));
console.log('VISIBLE IDS (hint/header/about):');
ids.filter(i => /hint|page_name|about|back/.test(i)).forEach(i => console.log('  ' + i));
