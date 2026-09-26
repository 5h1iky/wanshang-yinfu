// 把各页顶部那段"LinearLayout + btn_back + 标题 + 占位 View"的老顶栏，
// 整体换成 <include layout="@layout/include_header" />（单行页头，省约 18dp）。
// 按标签深度精确取范围，避免正则误伤。
// 用法: node tools/swap-header.js <layout文件...>
const fs = require('fs');

/** 从 start 行（必须是某元素的开标签）找到该元素的结束行 */
function blockEnd(lines, start) {
  let depth = 0;
  for (let i = start; i < lines.length; i++) {
    const tags = lines[i].match(/<\/?[A-Za-z][^>]*>/g) || [];
    for (const t of tags) {
      if (t.startsWith('</')) depth--;
      else if (!/\/>\s*$/.test(t) && !t.endsWith('/>')) depth++;
    }
    if (depth === 0 && i > start) return i;
    if (depth === 0 && i === start && tags.length >= 1) return i;   // 首行即自闭合
  }
  return -1;
}

for (const file of process.argv.slice(2)) {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const btnIdx = lines.findIndex(l => l.includes('btn_back'));
  if (btnIdx < 0) { console.log('跳过（无 btn_back）' + file); continue; }

  let start = -1;
  for (let i = btnIdx; i >= 0; i--) {
    if (/^    <[A-Za-z]/.test(lines[i])) { start = i; break; }   // 4 空格缩进 = 根的直接子元素
  }
  if (start < 0) { console.log('跳过（找不到顶栏起点）' + file); continue; }

  const end = blockEnd(lines, start);
  if (end < 0) { console.log('跳过（找不到顶栏终点）' + file); continue; }
  if (!lines.slice(start, end + 1).join('\n').includes('btn_back')) {
    console.log('跳过（范围不含 btn_back）' + file); continue;
  }

  const ind = lines[start].match(/^\s*/)[0];
  lines.splice(start, end - start + 1,
    ind + '<!-- 单行页头：返回箭头兼热区 + 页名 + 时钟；页名由代码 setPageTitle 设置 -->',
    ind + '<include layout="@layout/include_header" />');
  fs.writeFileSync(file, lines.join('\n'));
  console.log('已换 ' + file + '  顶栏 ' + (end - start + 1) + ' 行 → include');
}
