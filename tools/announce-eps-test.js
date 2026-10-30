// 公告端点候选排序（连通性实测驱动，2026-09-27）：
//   cdn.jsdelivr.net  ✅ 615ms（走真实 Cloudflare IP；服务 GitHub 仓库文件，国内可用性最好）
//   workers.dev       ❌ 本网络解析到污染 IP 150.107.3.176 超时（保留作备选：可达时 KV 改公告即时生效）
//   api.github.com    ⚠️ 本机被 hosts/代理指向 127.0.0.1；有代理的手机可达
const eps = [
  ['jsdelivr', 'https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json'],
  ['jsdelivr-alt', 'https://fastly.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json'],
  ['worker', 'https://dy-announce.sharkyline.workers.dev/announce'],
];
(async () => {
  for (const [name, url] of eps) {
    const t0 = Date.now();
    try {
      const ctl = new AbortController();
      const t = setTimeout(() => ctl.abort(), 10000);
      const res = await fetch(url, { signal: ctl.signal });
      clearTimeout(t);
      const body = await res.text();
      console.log(`${name.padEnd(14)} HTTP ${res.status} ${Date.now() - t0}ms  ${body.slice(0, 120).replace(/\n/g, ' ')}`);
    } catch (e) {
      console.log(`${name.padEnd(14)} FAIL(${e.name}) ${Date.now() - t0}ms`);
    }
  }
})();
