// 在 dump 里数有多少个 row_title（= 列表行数）并打印前几行文本。
// 用法: node tools/ui-rows-count.js [dump文件]
const fs = require('fs');
const file = process.argv[2] || 'shots/uid.xml';
const src = fs.readFileSync(file, 'utf8');
const nodes = src.split('<node').slice(1);
let n = 0;
const texts = [];
for (const nd of nodes) {
  const id = (nd.match(/resource-id="([^"]*)"/) || [])[1] || '';
  if (!/row_title/.test(id)) continue;
  const t = (nd.match(/text="([^"]*)"/) || [])[1] || '';
  texts.push(t);
  n++;
}
console.log(`row_title 行数 = ${n}`);
texts.slice(0, 6).forEach(t => console.log('  · ' + (t || '(空)')));
// 顺带报告几个关键节点的可见性
for (const key of ['tv_mine_hint', 'tv_user_hint', 'loading_overlay', 'tv_comment_hint']) {
  const hit = nodes.find(nd => ((nd.match(/resource-id="([^"]*)"/) || [])[1] || '').includes(key));
  if (hit) console.log(`${key} 存在于 dump（=可见或有 bounds）`);
}
