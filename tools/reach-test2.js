// 版2：IPv4 优先 + 分别报告 DNS/连接阶段，排除本机 IPv6 干扰。
const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

const targets = [
  ['workers.dev 公告Worker', 'https://dy-announce.sharkyline.workers.dev/announce'],
  ['api.github.com', 'https://api.github.com/repos/5h1iky/wanshang-yinfu/releases/latest'],
  ['raw.githubusercontent', 'https://raw.githubusercontent.com/5h1iky/wanshang-yinfu/main/README.md'],
  ['cdn.jsdelivr.net', 'https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/README.md'],
  ['R2 S3 端点', 'https://d006217838093c6bfeaf95f81cbc805c.r2.cloudflarestorage.com'],
];

(async () => {
  for (const [name, url] of targets) {
    const host = new URL(url).hostname;
    let dnsInfo = '';
    try {
      const addrs = await dns.promises.lookup(host, { all: true });
      dnsInfo = addrs.map(a => a.address).join('/');
    } catch (e) { dnsInfo = 'DNS-FAIL'; }
    const t0 = Date.now();
    let result;
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 10000);
      const res = await fetch(url, { signal: ctl.signal });
      clearTimeout(timer);
      result = `HTTP ${res.status}`;
    } catch (e) {
      result = `FAIL(${e.name}${e.cause ? '/' + e.cause.code : ''})`;
    }
    console.log(`${name.padEnd(24)} ${result.padEnd(22)} ${(Date.now() - t0) + 'ms'}  dns=[${dnsInfo}]`);
  }
})();
