// PNG 结构体检 + 修复：逐 chunk 校验长度/CRC，报出第一个坏块位置。
// 背景：本机 adb exec-out 的 \n→\r\n 污染会穿透到 IDAT 内部（压缩流里任何 0x0A 都会被
// 前置 0x0D），修头没用。所以正确做法是反向清洗：把所有 \r\n 还原成 \n，再补魔数。
// 用法：node png-repair.js <in.png> [out.png]
const fs = require('fs');
const zlib = require('zlib');
const inp = process.argv[2];
const outp = process.argv[3] || inp;
const buf = fs.readFileSync(inp);
// 全量反向 CRLF 清洗（exec-out 的污染是加 0x0D，洗掉所有 \r\n 里的 \r）
let s = buf.toString('latin1');
let washed = s.replace(/\r\n/g, '\n');
let png = Buffer.from(washed, 'latin1');
// 修魔数
const SIG = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
if (!png.subarray(0, 8).equals(SIG)) {
  // 洗过之后魔数的 0D 0A 也被洗成了 0A —— 重写头 8 字节
  png = Buffer.concat([SIG, png.subarray(8)]);
}
// 逐 chunk 验证
let off = 8, chunks = [], bad = null;
while (off + 8 <= png.length) {
  const len = png.readUInt32BE(off);
  const type = png.subarray(off + 4, off + 8).toString('latin1');
  if (off + 12 + len > png.length) { bad = { off, type, len, why: 'exceeds file' }; break; }
  const crcStored = png.readUInt32BE(off + 8 + len);
  const crcCalc = require('zlib').crc32
    ? require('zlib').crc32(png.subarray(off + 4, off + 8 + len))
    : crc32manual(png.subarray(off + 4, off + 8 + len));
  chunks.push(type + ':' + len);
  if (crcStored !== (crcCalc >>> 0)) { bad = { off, type, len, why: 'CRC mismatch' }; break; }
  off += 12 + len;
  if (type === 'IEND') break;
}
if (bad) {
  console.error('bad chunk at ' + bad.off + ' type=' + bad.type + ' len=' + bad.len + ' why=' + bad.why);
  process.exit(1);
}
fs.writeFileSync(outp, png);
console.log('OK, chunks: ' + chunks.join(' '));
// 解压试读（终极校验：zlib 能把 IDAT 吹开）
let idat = [];
off = 8;
while (off + 8 <= png.length) {
  const len = png.readUInt32BE(off);
  const type = png.subarray(off + 4, off + 8).toString('latin1');
  if (type === 'IDAT') idat.push(png.subarray(off + 8, off + 8 + len));
  off += 12 + len;
  if (type === 'IEND') break;
}
try {
  const raw = zlib.inflateSync(Buffer.concat(idat));
  console.log('IDAT inflate OK, raw bytes = ' + raw.length);
} catch (e) {
  console.error('IDAT inflate FAILED: ' + e.message);
  process.exit(1);
}

function crc32manual(b) {
  let c, table = crc32manual.table;
  if (!table) {
    table = crc32manual.table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c;
    }
  }
  c = 0xFFFFFFFF;
  for (let i = 0; i < b.length; i++) c = table[(c ^ b[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}
