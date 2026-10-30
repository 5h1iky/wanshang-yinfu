// 探：手机版（移动 UA）页面有没有私信功能，以及它的路由/bundle 长什么样
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 11; OWW212) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.120 Mobile Safari/537.36';
const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

async function get(url, ua) {
  const r = await fetch(url, { headers: { 'User-Agent': ua, Accept: 'text/html,application/xhtml+xml' }, redirect: 'follow' });
  return { status: r.status, finalUrl: r.url, html: await r.text() };
}

(async () => {
  console.log('=== 1. 手机版各路径返回什么 ===');
  for (const path of ['/', '/chat', '/messages', '/im', '/user/self']) {
    const r = await get('https://www.douyin.com' + path, MOBILE_UA);
    const isMobileShell = r.html.length < 20000;
    console.log(`  ${path.padEnd(12)} HTTP ${r.status}  ${r.html.length} 字节  ${isMobileShell ? '(手机版轻壳)' : '(桌面版大页)'}  最终 ${r.finalUrl}`);
  }

  console.log('\n=== 2. 手机版首页的 script（看有没有 polyfill 与 im 相关）===');
  const home = await get('https://www.douyin.com/', MOBILE_UA);
  const srcs = [...home.html.matchAll(/<script[^>]+src="([^"]+)"/g)].map(m => m[1]);
  srcs.forEach(s => console.log('  ' + s));
  const titles = [...home.html.matchAll(/<title>([^<]*)<\/title>/g)].map(m => m[1]);
  console.log('  title: ' + (titles[0] || '(无)'));

  console.log('\n=== 3. 抓 lib-router / 主包，找 chat/message/im 路由 ===');
  for (const s of srcs.filter(x => /router|main|index|app|entry/i.test(x)).slice(0, 3)) {
    try {
      const r = await fetch(s.startsWith('http') ? s : 'https:' + s);
      const js = await r.text();
      const hits = [...new Set([...js.matchAll(/["'`](\/?(?:chat|message|im|conversation)[a-zA-Z0-9_\-/]*)["'`]/g)].map(m => m[1]))].slice(0, 20);
      console.log(`  ${s.split('/').pop()} (${Math.round(js.length / 1024)}KB) 命中: ${hits.length ? hits.join(', ') : '(无)'}`);
    } catch (e) {
      console.log(`  ${s} 抓取失败: ${e.message}`);
    }
  }

  console.log('\n=== 4. 手机版 HTML 里有没有"下载App/打开App"这类拦截（说明手机版是残缺的）===');
  const text = home.html.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  console.log('  可见文本片段: ' + text.slice(0, 300));
})();
