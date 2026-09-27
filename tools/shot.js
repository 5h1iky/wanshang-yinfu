// adb 截图（二进制安全：PS 5.1 管道会把 PNG 撕坏，必须用 cmd /c 重定向）。
// 用法：node shot.js <输出路径>
const { execSync } = require('child_process');
const out = process.argv[2];
if (!out) { console.error('usage: node shot.js <out.png>'); process.exit(1); }
const adb = 'E:\\sdk\\platform-tools\\adb.exe';
execSync(`"${adb}" exec-out screencap -p > "${out}"`, { shell: 'cmd.exe' });
const fs = require('fs');
const buf = fs.readFileSync(out);
// PNG 魔数校验（真机偶发 CRLF 污染：exec-out 的 \n→\r\n 转换）
if (buf[0] === 0x89 && buf[1] === 0x50) {
  const clean = Buffer.from(buf.toString('latin1').replace(/\r\n/g, '\n'), 'latin1');
  if (clean.length !== buf.length) { fs.writeFileSync(out, clean); console.log('CRLF 清理: ' + buf.length + ' -> ' + clean.length); }
  console.log('OK ' + clean.length + ' bytes, PNG 魔数正确');
} else {
  console.error('不是 PNG: 前 4 字节 = ' + buf.slice(0, 4).toString('hex'));
  process.exit(1);
}
