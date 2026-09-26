// 分析 app.log：只看最后一次"应用启动"之后的事件，统计重复与昵称中间态。
// 用法: node tools/log-scan.js <日志文件> [起始标记]
const fs = require('fs');
const file = process.argv[2];
const marker = process.argv[3] || '应用启动';
const lines = fs.readFileSync(file, 'utf8').split('\n');
let start = -1;
for (let i = lines.length - 1; i >= 0; i--) {
  if (lines[i].includes(marker)) { start = i; break; }
}
if (start < 0) { console.log('没找到起始标记「' + marker + '」，统计全部 ' + lines.length + ' 行'); }
const run = lines.slice(start < 0 ? 0 : start).join('\n');
const c = (re) => (run.match(re) || []).length;

console.log('统计范围行数 :', (start < 0 ? lines.length : lines.length - start));
console.log('页面加载完成 :', c(/页面加载完成/g));
console.log('同文档重复(已忽略):', c(/同文档重复/g));
console.log('auth 事件    :', c(/"type":"auth"/g));
console.log('conversations 批次:', c(/"type":"conversations"/g));
console.log('comments 批次:', c(/"type":"comments"/g));

const names = [...run.matchAll(/"name":"([^"]*)"/g)].map(m => m[1]);
const uniq = [...new Set(names)];
console.log('\n出现过的会话名 (' + uniq.length + ' 个去重):');
console.log('  ' + uniq.join(' | '));
const numeric = uniq.filter(n => /^\d{6,}$/.test(n));
console.log('  → 纯数字占位名: ' + numeric.length + (numeric.length ? ' 【仍有闪烁】 ' + numeric.join(',') : ' ✅ 无'));

// 评论正文抓空检查
const bodies = [...run.matchAll(/"text":"([^"]*)"/g)].map(m => m[1]);
console.log('\n评论 text 字段: 共 ' + bodies.length + ' 条，其中空文 ' + bodies.filter(b => !b.trim()).length + ' 条');
