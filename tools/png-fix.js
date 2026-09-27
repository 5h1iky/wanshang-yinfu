// 修正 PNG 头：本机 adb exec-out 偶发把魔数第 4 字节 \r 吃成 \n（89 50 4E 47 0A 1A 0A ——
// 正确应为 89 50 4E 47 0D 0A 1A 0A）。shot.js 的 CRLF 清理反而把 0D 0A 一起洗成 0A。
// 用法：node png-fix.js <file.png>
const fs = require('fs');
const p = process.argv[2];
const buf = fs.readFileSync(p);
const SIG = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
if (buf.subarray(0, 8).equals(SIG)) { console.log('already OK'); process.exit(0); }
// 找 IHDR 前的 8 字节魔数并重写
if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) {
  const fixed = Buffer.concat([SIG, buf.subarray(8)]);
  // 数据区若有 \r\n 污染也已无解（IDAT CRC 会崩）——先只修头试读
  fs.writeFileSync(p, fixed);
  console.log('header fixed: ' + buf.length + ' -> ' + fixed.length);
} else {
  console.error('unexpected head: ' + buf.subarray(0, 8).toString('hex'));
  process.exit(1);
}
