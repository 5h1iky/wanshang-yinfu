// 通用语法普查：扫描任意 JS URL，判定"要哪个版本的 Chrome 才解析得了"（Chrome 83 = 手表内核）
// 改编自 js-syntax-proof.js（那次定案私信根因的工具），改为接收 URL 列表文件
const fs = require('fs');
const FEATURES = [
  { name: '逻辑赋值 ??= / ||= / &&=', re: /\?\?=|(?<![|&])\|\|=|&&=/g, chrome: 85 },
  { name: '可选链 ?.（属性）', re: /\?\.\s*[a-zA-Z_$[]/g, chrome: 80 },
  { name: '空值合并 ??', re: /\?\?(?!=)/g, chrome: 80 },
  { name: 'Array.at()', re: /\.at\(\s*-?\d+\s*\)/g, chrome: 92 },
  { name: 'String.replaceAll()', re: /\.replaceAll\(/g, chrome: 85 },
  { name: 'Object.hasOwn()', re: /Object\.hasOwn\(/g, chrome: 93 },
  { name: '类私有字段/方法 #x', re: /(^|[^\w$])#\w+\s*[=;(]/g, chrome: 84 },
  { name: '静态初始化块 static{}', re: /static\s*\{/g, chrome: 94 },
  { name: '顶层 await', re: /^\s*await\s/m, chrome: 89 },
  { name: 'structuredClone', re: /structuredClone\(/g, chrome: 98 },
  { name: 'Array.prototype.findLast', re: /\.findLast(Index)?\(/g, chrome: 97 },
  { name: '类字段声明 x = 1（类体内裸赋值，粗判）', re: /(^|\n)\s{2,}[A-Za-z_$][\w$]*\s*=[^=]/g, chrome: 72, weak: true },
];

(async () => {
  const urls = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  for (const url of urls) {
    const short = url.split('/').pop();
    let js;
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': '*/*' } });
      if (!r.ok) { console.log(`\n=== ${short} ===\n  下载失败 HTTP ${r.status}`); continue; }
      js = await r.text();
    } catch (e) { console.log(`\n=== ${short} ===\n  下载异常: ${e.message}`); continue; }
    const hits = [];
    let worst = 0;
    for (const f of FEATURES) {
      const m = js.match(f.re);
      if (m && m.length) {
        worst = Math.max(worst, f.chrome);
        hits.push(`${f.name} ×${m.length} (需 Chrome ${f.chrome}${f.weak ? '，弱判据' : ''})`);
      }
    }
    console.log(`\n=== ${short} ===`);
    console.log(`  大小: ${Math.round(js.length / 1024)}KB   最低内核: Chrome ${worst || '<72'}   ${worst > 83 ? '❌ 83 解析不了' : '✅ 83 能解析'}`);
    hits.forEach(h => console.log('    - ' + h));
  }
})();
