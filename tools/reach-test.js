// 连通性矩阵：测各候选端点的可达性/延迟（用于决定公告与 APK 镜像的通道策略）。
// 用法：node tools/reach-test.js
const targets = [
  ['workers.dev 公告Worker', 'https://dy-announce.sharkyline.workers.dev/'],
  ['api.github.com', 'https://api.github.com/repos/5h1iky/wanshang-yinfu/releases/latest'],
  ['raw.githubusercontent', 'https://raw.githubusercontent.com/5h1iky/wanshang-yinfu/main/README.md'],
  ['cdn.jsdelivr.net', 'https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/README.md'],
  ['R2 S3 端点', 'https://d006217838093c6bfeaf95f81cbc805c.r2.cloudflarestorage.com'],
  ['gitee.com', 'https://gitee.com'],
  ['ghproxy 镜像', 'https://ghfast.top/https://raw.githubusercontent.com/5h1iky/wanshang-yinfu/main/README.md'],
];

(async () => {
  for (const [name, url] of targets) {
    const t0 = Date.now();
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 12000);
      const res = await fetch(url, { signal: ctl.signal, redirect: 'follow' });
      clearTimeout(timer);
      const ms = Date.now() - t0;
      console.log(`${name.padEnd(26)} HTTP ${res.status}  ${ms}ms`);
    } catch (e) {
      const ms = Date.now() - t0;
      console.log(`${name.padEnd(26)} FAIL(${e.name})  ${ms}ms`);
    }
  }
})();
