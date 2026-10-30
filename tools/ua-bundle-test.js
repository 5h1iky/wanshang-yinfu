// 决定性实验：抖音是否按 UA 分发不同的前端 bundle？
// 若是 → 把 App 的 UA 改成"诚实的 83"，手表就可能拿到能解析的旧 bundle。
// 若否 → UA 这条路走不通，得换方案。
const UAS = {
  '当前(谎称135桌面)': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36',
  '诚实83桌面': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.120 Safari/537.36',
  '手表真实UA(83移动)': 'Mozilla/5.0 (Linux; Android 11; OWW212 Build/RKQ1.211102.001.11_A.142.260314152209; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.120 Mobile Safari/537.36',
};

function scripts(html) {
  return [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(m => m[1]);
}

(async () => {
  const results = {};
  for (const [label, ua] of Object.entries(UAS)) {
    try {
      const r = await fetch('https://www.douyin.com/chat', {
        headers: { 'User-Agent': ua, 'Accept': 'text/html,application/xhtml+xml' },
        redirect: 'follow',
      });
      const html = await r.text();
      const srcs = scripts(html);
      results[label] = srcs;
      console.log(`\n=== ${label} ===`);
      console.log(`  HTTP ${r.status}  长度 ${html.length}  script 数 ${srcs.length}`);
      const pcim = srcs.filter(s => /pcim|social/i.test(s));
      console.log('  私信相关 bundle: ' + (pcim.length ? pcim.join('\n                    ') : '(HTML 里没直接引用，可能是动态 import)'));
      // 找入口主包
      const entry = srcs.filter(s => /douyin_web|entry|index|main/i.test(s)).slice(0, 4);
      entry.forEach(s => console.log('  入口: ' + s.split('/').pop()));
      // 有没有"浏览器过低"提示
      if (/浏览器|升级|不支持/.test(html)) {
        const m = html.match(/.{0,40}(浏览器|升级)[^<]{0,60}/);
        if (m) console.log('  ⚠️ 页面含提示词: ' + m[0].replace(/\s+/g, ' '));
      }
    } catch (e) {
      console.log(`\n=== ${label} ===\n  失败: ${e.message}`);
    }
  }

  // 对比：三种 UA 拿到的 script 列表是否一致
  const keys = Object.keys(results);
  if (keys.length >= 2) {
    const base = results[keys[0]].join('|');
    console.log('\n=== 结论 ===');
    keys.slice(1).forEach(k => {
      const same = results[k].join('|') === base;
      console.log(`  ${keys[0]}  vs  ${k}: ${same ? '完全相同（UA 不影响分发）' : '不同 ★ UA 影响分发！'}`);
    });
  }
})();
