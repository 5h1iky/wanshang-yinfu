// 从 uiautomator dump 里按 resource-id 或 text 取控件中心坐标，供 adb input tap 用。
// 用法: node tools/ui-bounds.js [xml文件] [id关键字]        —— 按 id 找
//       node tools/ui-bounds.js [xml文件] text:关键字       —— 按显示文本找（代码动态生成的行没有 id）
const fs = require('fs');
const file = process.argv[2] || 'tools/ui-dump.xml';
const filter = process.argv[3] || '';

function emit(re, label) {
  let m, n = 0;
  while ((m = re.exec(fs.readFileSync(file, 'utf8')))) {
    const l = +m[m.length - 4], t = +m[m.length - 3], r = +m[m.length - 2], b = +m[m.length - 1];
    console.log(label(m[1]).padEnd(18) + ' tap ' + Math.round((l + r) / 2) + ' ' + Math.round((t + b) / 2)
      + '   尺寸 ' + (r - l) + 'x' + (b - t));
    n++;
  }
  if (!n) console.log('没匹配到（' + filter + '）');
}

if (filter.indexOf('text:') === 0) {
  const want = filter.slice(5);
  emit(new RegExp('text="([^"]*' + want.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '[^"]*)"[^>]*?bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"', 'g'),
    s => 'text:' + s.slice(0, 14));
} else {
  emit(new RegExp('resource-id="com\\.dywatch\\.app:id\\/([A-Za-z_0-9]+)"[^>]*?bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"', 'g'),
    s => s);
}

