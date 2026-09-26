// 打印 dump 的层级结构（缩进 + class + id + bounds + 高/宽），排查"控件只有几十像素高"这类布局问题。
// 用法: node tools/ui-tree.js [dump文件] [最大深度]
const fs = require('fs');

const file = process.argv[2] || 'shots/uid.xml';
const maxDepth = +(process.argv[3] || 8);
const src = fs.readFileSync(file, 'utf8');

// uiautomator dump 是扁平的 <node ...> 序列 + </node> 收尾，按出现顺序重建缩进。
// ⚠️ 必须区分自闭合 `<node ... />`（叶子）与 `<node ...>`（有子节点）：
//    两者都被 /<node\b[^>]*>/ 命中，若一律 depth++ 就会越缩越深、几行之后整棵树都歪掉。
const tokens = src.match(/<node\b[^>]*\/?>|<\/node>/g) || [];
let depth = 0;
for (const tk of tokens) {
  if (tk === '</node>') { depth--; continue; }
  const selfClosing = /\/>$/.test(tk);
  const attr = k => (tk.match(new RegExp(k + '="([^"]*)"')) || [])[1] || '';
  const b = attr('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  const w = b ? b[3] - b[1] : 0, h = b ? b[4] - b[2] : 0;
  if (depth <= maxDepth) {
    const cls = attr('class').split('.').pop();
    const id = attr('resource-id').replace('com.dywatch.app:id/', '#');
    const tx = attr('text');
    console.log('  '.repeat(depth) + `${cls}${id ? ' ' + id : ''}`
      + `  ${w}x${h} @${b ? b[2] : '?'}`
      + (tx ? `  "${tx.slice(0, 18)}"` : ''));
  }
  if (!selfClosing) depth++;
}
