// 部署 dy-announce Worker 到 Cloudflare（免费版）。
// 用法：node tools/cf-deploy-worker.js <script-file> <worker-name> [kvNamespaceId]
// 依赖：工作区 cf-cred.json（由 tools/cf-cred.js 生成，不在仓库里）
const fs = require('fs');
const path = require('path');

const cred = JSON.parse(fs.readFileSync((process.env.CF_CRED_FILE || require('path').join(__dirname, '..', 'cf-cred.json')), 'utf8'));
const scriptFile = process.argv[2];
const workerName = process.argv[3] || 'dy-announce';
const kvId = process.argv[4] || null;

if (!scriptFile) { console.error('usage: node cf-deploy-worker.js <script.js> <name> [kvId]'); process.exit(1); }
const code = fs.readFileSync(scriptFile, 'utf8');

const metadata = {
  main_module: path.basename(scriptFile),
  compatibility_date: '2026-09-27',
  bindings: kvId ? [{ type: 'kv_namespace', name: 'ANNOUNCE', namespace_id: kvId }] : [],
};

(async () => {
  const form = new FormData();
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }), 'metadata');
  form.append(metadata.main_module, new Blob([code], { type: 'application/javascript+module' }), metadata.main_module);

  const url = `https://api.cloudflare.com/client/v4/accounts/${cred.accountId}/workers/scripts/${workerName}`;
  const res = await fetch(url, {
    method: 'PUT',
    headers: { Authorization: 'Bearer ' + cred.apiToken },
    body: form,
  });
  const text = await res.text();
  console.log('upload HTTP ' + res.status);
  let json; try { json = JSON.parse(text); } catch { console.log(text.slice(0, 800)); return; }
  if (!json.success) {
    (json.errors || []).forEach(e => console.error('  err ' + e.code + ': ' + e.message));
    process.exit(1);
  }
  console.log('deployed id=' + json.result.id + ' modified=' + json.result.modified_on);

  // 开启 workers.dev 子域路由
  const sub = await fetch(url + '/subdomain', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + cred.apiToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: true }),
  });
  const subText = await sub.text();
  console.log('subdomain enable HTTP ' + sub.status + ' ' + subText.slice(0, 200));

  // 取账号子域拼出访问地址
  const sd = await fetch(`https://api.cloudflare.com/client/v4/accounts/${cred.accountId}/workers/subdomain`, {
    headers: { Authorization: 'Bearer ' + cred.apiToken },
  });
  const sdJson = await sd.json();
  const zone = sdJson.result && sdJson.result.subdomain;
  if (zone) console.log('URL: https://' + workerName + '.' + zone + '.workers.dev');
})();
