// 通用响应检查器：node pb-inspect.js <bin文件>
const fs = require('fs');
const pb = require('./pb.js');
const buf = fs.readFileSync(process.argv[2]);
console.log('大小: ' + buf.length + 'B，hex 头: ' + buf.subarray(0, 40).toString('hex'));
console.log('===== 结构树 =====');
pb.dump(buf);
console.log('===== 字符串/数值 =====');
console.log(pb.collectStrings(buf).join(' | '));
