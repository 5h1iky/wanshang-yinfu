// 按 resource-id 打印节点的 bounds / text / 可见性（默认 dump 文件 shots/uid.xml）
// 用法: node tools/ui-nodes.js [dump文件] [id关键字...]
//   不带 id 关键字 = 打印所有带 resource-id 的节点
// 为什么要它：验证"加载圆圈收没收""头像控件在不在"这类问题，文本 dump（ui-text.js）
// 看不到——它们没有 text，只能看 id + bounds + visibility。
const fs = require('fs');

const file = process.argv[2] || 'shots/uid.xml';
const keys = process.argv.slice(3);
const src = fs.readFileSync(file, 'utf8');
const nodes = src.split('<node').slice(1);

let hit = 0;
for (const n of nodes) {
  const id = (n.match(/resource-id="([^"]*)"/) || [])[1] || '';
  if (!id) continue;
  if (keys.length && !keys.some(k => id.includes(k))) continue;
  const b = (n.match(/bounds="([^"]*)"/) || [])[1] || '';
  const t = (n.match(/text="([^"]*)"/) || [])[1] || '';
  const cls = (n.match(/class="([^"]*)"/) || [])[1] || '';
  const short = cls.split('.').pop();
  // bounds 为 0 面积 = 实际没占位（被 GONE 或没测量）
  const m = b.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  const area = m ? (m[3] - m[1]) * (m[4] - m[2]) : 0;
  console.log(`${id.replace('com.dywatch.app:id/', '')}  <${short}>  bounds=${b}  area=${area}${t ? '  text=' + t : ''}`);
  hit++;
}
if (!hit) console.log(keys.length ? '没有匹配的 id: ' + keys.join(',') : '（这个 dump 里没有任何 resource-id）');
