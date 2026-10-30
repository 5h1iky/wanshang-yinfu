// 直接检验：让手表解析失败的那两个 JS 文件里，到底有没有 Chromium 83 不支持的语法？
// 文件 URL 来自手表 logcat 里的真实报错，可自行复核。
const FILES = [
  ['私信模块入口', 'https://lf3-social.iesdouyin.com/obj/douyin-social-cdn/pcim/static/js/async/__federation_expose_default_export.0ccd0cf4.js'],
  ['私信模块 chunk', 'https://lf3-social.iesdouyin.com/obj/douyin-social-cdn/pcim/static/js/async/4187.2096e141.js'],
];

// 语法特性 → 该特性进入 Chrome 的版本
const FEATURES = [
  { name: '逻辑赋值 ??= / ||= / &&=', re: /\?\?=|(?<![|&])\|\|=|&&=/g, chrome: 85 },
  { name: '可选链 ?.（属性）', re: /\?\.\s*[a-zA-Z_$[]/g, chrome: 80 },
  { name: '空值合并 ??', re: /\?\?(?!=)/g, chrome: 80 },
  { name: 'Array.at()', re: /\.at\(\s*-?\d+\s*\)/g, chrome: 92 },
  { name: 'String.replaceAll()', re: /\.replaceAll\(/g, chrome: 85 },
  { name: 'Object.hasOwn()', re: /Object\.hasOwn\(/g, chrome: 93 },
  { name: '类私有字段 #x', re: /(^|[^\w$])#\w+\s*[=;(]/g, chrome: 74 },
  { name: '静态初始化块 static{}', re: /static\s*\{/g, chrome: 94 },
  { name: '顶层 await', re: /^\s*await\s/m, chrome: 89 },
  { name: 'structuredClone', re: /structuredClone\(/g, chrome: 98 },
  { name: 'Array.prototype.findLast', re: /\.findLast(Index)?\(/g, chrome: 97 },
];

(async () => {
  for (const [label, url] of FILES) {
    console.log('\n=== ' + label + ' ===');
    console.log('  ' + url);
    let js;
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': '*/*' } });
      if (!r.ok) { console.log('  下载失败 HTTP ' + r.status); continue; }
      js = await r.text();
    } catch (e) { console.log('  下载异常: ' + e.message); continue; }

    console.log('  大小: ' + Math.round(js.length / 1024) + 'KB');
    let worst = 0;
    for (const f of FEATURES) {
      const m = js.match(f.re);
      if (m && m.length) {
        worst = Math.max(worst, f.chrome);
        const idx = js.search(f.re);
        const snippet = js.slice(Math.max(0, idx - 45), idx + 55).replace(/\s+/g, ' ');
        console.log(`  ✗ 用了 ${f.name}  (Chrome ${f.chrome}+)：${m.length} 处`);
        console.log(`      片段: ...${snippet}...`);
      }
    }
    if (!worst) console.log('  （没找到已知的新语法，说明失败原因在别处）');
    else console.log(`  → 该文件要求的内核最低版本 ≈ Chrome ${worst}；手表是 Chrome 83 → ${worst > 83 ? '解析必然失败 ★' : '理论上能跑'}`);
  }
})();
