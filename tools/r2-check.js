// R2 通道体检：S3 API（上传用）与 r2.dev 公共域（下载用）的可达性。
// 结论决定 R2 镜像是否值得做（若两端都不通，镜像对国内用户无意义，只能留脚本备将来用）。
const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');
const fs = require('fs');
const cred = JSON.parse(fs.readFileSync((process.env.CF_CRED_FILE || require('path').join(__dirname, '..', 'cf-cred.json')), 'utf8'));

(async () => {
  const targets = [
    ['R2 S3 API（上传）', cred.s3Endpoint],
    ['Cloudflare API', 'https://api.cloudflare.com/client/v4/user'],
  ];
  for (const [label, url] of targets) {
    const t0 = Date.now();
    try {
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 10000);
      const res = await fetch(url, { signal: ctl.signal });
      clearTimeout(timer);
      console.log(`${label.padEnd(22)} HTTP ${res.status} ${Date.now() - t0}ms`);
    } catch (e) {
      console.log(`${label.padEnd(22)} FAIL(${e.name}${e.cause ? '/' + e.cause.code : ''}) ${Date.now() - t0}ms`);
    }
  }
  // R2 桶列表（走 Cloudflare API，不经 S3 端点）
  const token = process.env.CF_TOKEN;
  const r = await fetch(`https://api.cloudflare.com/client/v4/accounts/${cred.accountId}/r2/buckets`, {
    headers: { Authorization: 'Bearer ' + cred.apiToken },
  });
  const j = await r.json();
  console.log('R2 桶列表 HTTP ' + r.status + ' success=' + j.success);
  if (j.result) console.log('  桶: ' + (j.result.buckets || []).map(b => b.name).join(', ') || '(无)');
  if (j.errors && j.errors.length) console.log('  err: ' + j.errors.map(e => e.message).join('; '));
})();
