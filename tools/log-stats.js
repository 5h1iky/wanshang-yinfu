const fs = require('fs');
const b = fs.readFileSync('D:/dev/dywatch/shots/app.log');
let s;
// PS 重定向出来的文件可能是 UTF-16LE（带 BOM FF FE）或 UTF-8
if (b[0] === 0xFF && b[1] === 0xFE) {
  s = b.toString('utf16le').replace(/^﻿/, '');
} else {
  s = b.toString('utf8');
}
const lines = s.split(/\r?\n/).filter(l => l.trim());
let comments = 0, more = 0, moreItems = 0, lastBatch = 0;
for (const l of lines) {
  if (l.indexOf('"type":"comments"') >= 0) {
    comments++;
    lastBatch = (l.match(/"name":/g) || []).length;
  }
  if (l.indexOf('"type":"commentsMore"') >= 0) {
    more++;
    moreItems += (l.match(/"name":/g) || []).length;
  }
}
console.log('comments(整批) 事件 =', comments, ' 最后一批条数 =', lastBatch);
console.log('commentsMore 事件 =', more, ' 累计追加条数 =', moreItems);
console.log('=> 适配器最终应有条数 =', lastBatch + moreItems);
console.log('--- 最后 5 条 ---');
lines.slice(-5).forEach(l => console.log(l.slice(0, 120)));
