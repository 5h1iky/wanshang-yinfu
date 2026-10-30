// 判定 workers.dev 到底是「DNS 污染」还是「SNI 阻断」——两者含义完全不同：
//   DNS 污染 → 换个干净 DNS / DoH 就能连（用户侧有救）
//   SNI 阻断 → 换 DNS 也没用（域名本身在名单里，只能换域名）
// 方法：用国内公共 DoH 解析（223.5.5.5 阿里 / 119.29.29.29 腾讯），与本地解析结果对比。
const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

const names = [
  'dy-announce.sharkyline.workers.dev',
  'workers.dev',
  'cdn.jsdelivr.net',
  'api.github.com',
];

async function doh(server, name) {
  const url = `https://${server}/resolve?name=${encodeURIComponent(name)}&type=A`;
  try {
    const r = await fetch(url, { headers: { Accept: 'application/dns-json' } });
    const j = await r.json();
    const ips = (j.Answer || []).filter(a => a.type === 1).map(a => a.data);
    return ips.join(',') || '(无 A 记录)';
  } catch (e) {
    return 'DoH失败:' + e.message;
  }
}

(async () => {
  for (const n of names) {
    let local;
    try {
      const addrs = await dns.promises.lookup(n, { all: true });
      local = addrs.filter(a => a.family === 4).map(a => a.address).join(',') || '(无)';
    } catch (e) { local = 'DNS失败'; }
    const ali = await doh('223.5.5.5', n);
    const tx = await doh('119.29.29.29', n);
    console.log('域名: ' + n);
    console.log('  本地DNS      : ' + local);
    console.log('  阿里 DoH     : ' + ali);
    console.log('  腾讯 DoH     : ' + tx);
    console.log('');
  }
})();
