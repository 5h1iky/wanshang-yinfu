// 只读：看抓到的真实 feed 响应里，单个 aweme 对象到底有哪些字段可用
// （判断"能不能读标签"这件事，不用联网、不调接口）
const fs = require('fs');
const f = process.argv[2] || 'D:/dev/dywatch/tools/first-batch.json';
let root;
try {
  root = JSON.parse(fs.readFileSync(f, 'utf8'));
} catch (e) {
  console.log('解析失败:', e.message);
  process.exit(1);
}

let list = null;
if (Array.isArray(root)) list = root;
else if (root.aweme_list) list = root.aweme_list;
else if (root.data && root.data.aweme_list) list = root.data.aweme_list;
else {
  // 兜底：找第一个含 aweme_list 的键
  for (const k of Object.keys(root)) {
    if (root[k] && root[k].aweme_list) { list = root[k].aweme_list; break; }
  }
}
if (!list || !list.length) {
  console.log('没找到 aweme_list，顶层键:', Object.keys(root).slice(0, 20));
  process.exit(1);
}

console.log('样本条数 =', list.length);
const item = list[0];
console.log('\n=== 单条顶层字段 ===');
console.log(Object.keys(item).join(', '));

console.log('\n=== 与「标签/话题/分类」有关的字段 ===');
const keys = Object.keys(item);
const tagish = keys.filter(k => /tag|cha|text_extra|mix|category|label|music|interest|group|classify/i.test(k));
if (!tagish.length) console.log('（无）');
tagish.forEach(k => {
  const v = item[k];
  let brief = '';
  try {
    brief = JSON.stringify(v).slice(0, 200);
  } catch (e) { brief = String(v).slice(0, 200); }
  console.log(`\n[${k}] => ${brief}`);
});

// 逐条扫 desc 里的 #话题（这是最稳的"标签"来源，不依赖字段是否存在）
console.log('\n=== 从 desc 里抽 #话题（前 10 条）===');
let withTag = 0;
list.slice(0, 10).forEach((it, i) => {
  const desc = (it.desc || it.title || '') + '';
  const tags = (desc.match(/#([^\s#]+)/g) || []);
  if (tags.length) withTag++;
  console.log(`${i}: ${tags.join(' ') || '(无话题)'}   | ${desc.slice(0, 40)}`);
});
console.log(`\n前 10 条里有话题的 = ${withTag}`);
