// 抓抖音 /chat 页 HTML，抽出 pcim 脚本 URL 并逐个做语法普查 + 与 JsSyntaxPatch 三规则对照
// 用法: node --use-system-ca watch-chat-probe.js
(async () => {
  const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';
  const r = await fetch('https://www.douyin.com/chat', { headers: { 'User-Agent': UA, 'Referer': 'https://www.douyin.com/' }, redirect: 'follow' });
  const html = await r.text();
  console.log('HTTP ' + r.status + '  final-url=' + r.url + '  html=' + Math.round(html.length / 1024) + 'KB');
  const urls = [...new Set((html.match(/https?:\/\/[^"'\s>]+\.js/g) || []))];
  const pcim = urls.filter(u => u.includes('pcim') || u.includes('social'));
  console.log('\n页面引用的 JS 共 ' + urls.length + ' 个，其中 pcim/social 相关 ' + pcim.length + ' 个：');
  pcim.forEach(u => console.log('  ' + u));

  // 语法普查（与 tools/js-syntax-scan.js 同一套 FEATURES 精简版）
  const FEATURES = [
    { name: '逻辑赋值 ??=', re: /\?\?=/g, chrome: 85 },
    { name: '逻辑赋值 ||= / &&=', re: /(?<![|&])\|\|=|&&=/g, chrome: 85 },
    { name: 'Array.at()', re: /\.at\(\s*-?\d+\s*\)/g, chrome: 92 },
    { name: 'Object.hasOwn()', re: /Object\.hasOwn\(/g, chrome: 93 },
    { name: '类私有方法 #x()', re: /(^|[^\w$])#[A-Za-z_$][\w$]*\s*\(/g, chrome: 84 },
    { name: '类私有字段 #x', re: /(^|[^\w$])#[A-Za-z_$][\w$]*\s*[=;]/g, chrome: 84 },
    { name: '静态初始化块 static{}', re: /static\s*\{/g, chrome: 94 },
    { name: '顶层 await', re: /^\s*await\s/m, chrome: 89 },
  ];
  for (const u of pcim.slice(0, 8)) {
    try {
      const js = await (await fetch(u, { headers: { 'User-Agent': UA } })).text();
      const hits = FEATURES.map(f => ({ f, m: js.match(f.re) })).filter(x => x.m && x.m.length);
      const worst = hits.reduce((a, x) => Math.max(a, x.f.chrome), 0);
      console.log(`\n=== ${u.split('/').pop()} (${Math.round(js.length / 1024)}KB) 最低内核 Chrome ${worst || '<84'} ${worst > 83 ? '❌83解析不了' : '✅'} ===`);
      hits.forEach(x => console.log(`    ${x.f.name} ×${x.m.length} (需 ${x.f.chrome})`));
    } catch (e) { console.log(`\n=== ${u.split('/').pop()} 下载失败 ${e.message}`); }
  }
})();
