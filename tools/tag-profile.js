// 只读：统计一批真实 feed 里的标签分布，用来判断"是不是按账号个性化推的"
// 判据：如果真按账号推，标签应高度聚集在少数几个类目上；
//      如果是通用热门池，标签应该很散、每类只有 1~2 条。
const fs = require('fs');
const f = process.argv[2] || 'D:/dev/dywatch/tools/first-batch.json';
const root = JSON.parse(fs.readFileSync(f, 'utf8'));
let list = Array.isArray(root) ? root
  : (root.aweme_list || (root.data && root.data.aweme_list) || []);
if (!list.length) {
  for (const k of Object.keys(root)) if (root[k] && root[k].aweme_list) { list = root[k].aweme_list; break; }
}

const lvl1 = {};   // video_tag 里 level=1 的一级类目
const lvl2 = {};   // level=2 二级
const hash = {};   // desc 里的 #话题
let tagNull = 0, hashNone = 0;

for (const it of list) {
  const vt = it.video_tag || [];
  const has1 = vt.filter(t => t.level === 1 && t.tag_name);
  const has2 = vt.filter(t => t.level === 2 && t.tag_name);
  if (!has1.length) tagNull++;
  has1.forEach(t => lvl1[t.tag_name] = (lvl1[t.tag_name] || 0) + 1);
  has2.forEach(t => lvl2[t.tag_name] = (lvl2[t.tag_name] || 0) + 1);

  const tags = ((it.desc || '') + '').match(/#([^\s#]+)/g) || [];
  if (!tags.length) hashNone++;
  tags.forEach(t => hash[t] = (hash[t] || 0) + 1);
}

const top = (o, n) => Object.entries(o).sort((a, b) => b[1] - a[1]).slice(0, n);

console.log('样本条数 =', list.length);
console.log('video_tag 为空的条数 =', tagNull);
console.log('desc 无话题的条数 =', hashNone);

console.log('\n=== 一级类目（video_tag level=1）分布 ===');
top(lvl1, 15).forEach(([k, v]) => console.log(`  ${k.padEnd(14)} ${v}  ${'#'.repeat(v)}`));
console.log('  一级类目种类数 =', Object.keys(lvl1).length);

console.log('\n=== 二级类目（level=2）Top ===');
top(lvl2, 12).forEach(([k, v]) => console.log(`  ${k.padEnd(16)} ${v}  ${'#'.repeat(v)}`));

console.log('\n=== #话题 Top ===');
top(hash, 12).forEach(([k, v]) => console.log(`  ${k.padEnd(16)} ${v}  ${'#'.repeat(v)}`));
console.log('  话题种类数 =', Object.keys(hash).length);

// 集中度：最大类目占比
const total1 = Object.values(lvl1).reduce((a, b) => a + b, 0);
const max1 = top(lvl1, 1)[0];
console.log('\n=== 集中度判据 ===');
console.log(`  一级类目最大占比 = ${max1 ? (max1[1] / total1 * 100).toFixed(0) : 0}% (${max1 ? max1[0] : '-'} ${max1 ? max1[1] : 0}/${total1})`);
const totalH = Object.values(hash).reduce((a, b) => a + b, 0);
const maxH = top(hash, 1)[0];
console.log(`  话题最大占比     = ${maxH ? (maxH[1] / totalH * 100).toFixed(0) : 0}% (${maxH ? maxH[0] : '-'} ${maxH ? maxH[1] : 0}/${totalH})`);
