// KV 读写（公告：key=current）。用法：
//   node tools/cf-kv.js get <nsId> [key]
//   node tools/cf-kv.js put <nsId> <key> <jsonFile|-> 
//   node tools/cf-kv.js del <nsId> <key>
const fs = require('fs');
const cred = JSON.parse(fs.readFileSync((process.env.CF_CRED_FILE || require('path').join(__dirname, '..', 'cf-cred.json')), 'utf8'));
const base = `https://api.cloudflare.com/client/v4/accounts/${cred.accountId}/storage/kv/namespaces`;
const [op, nsId, key, arg] = process.argv.slice(2);
if (!op || !nsId) { console.error('usage: cf-kv.js get|put|del <nsId> [key] [file|-]'); process.exit(1); }
const H = { Authorization: 'Bearer ' + cred.apiToken };

(async () => {
  if (op === 'get') {
    const k = key || 'current';
    const res = await fetch(`${base}/${nsId}/values/${encodeURIComponent(k)}`, { headers: H });
    console.log('HTTP ' + res.status);
    console.log(await res.text());
  } else if (op === 'put') {
    const body = arg === '-' || !arg ? await readStdin() : fs.readFileSync(arg, 'utf8');
    const res = await fetch(`${base}/${nsId}/values/${encodeURIComponent(key)}`, {
      method: 'PUT', headers: { ...H, 'Content-Type': 'application/json' }, body,
    });
    console.log('HTTP ' + res.status + ' ' + (await res.text()).slice(0, 200));
  } else if (op === 'del') {
    const res = await fetch(`${base}/${nsId}/values/${encodeURIComponent(key)}`, { method: 'DELETE', headers: H });
    console.log('HTTP ' + res.status + ' ' + (await res.text()).slice(0, 200));
  }
})();

function readStdin() {
  return new Promise(r => { let d = ''; process.stdin.on('data', c => d += c); process.stdin.on('end', () => r(d)); });
}
