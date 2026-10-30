// Cloudflare API 客户端（读 cf-cred.json）。用法：
//   node cf-api.js GET /user/tokens/verify
//   node cf-api.js GET /accounts/{id}/workers/scripts
//   node cf-api.js PUT <path> '<json-body>'
const fs = require('fs');
const cred = JSON.parse(fs.readFileSync((process.env.CF_CRED_FILE || require('path').join(__dirname, '..', 'cf-cred.json')), 'utf8'));
const [method, rawPath, body] = process.argv.slice(2);
if (!method || !rawPath) { console.error('usage: node cf-api.js <METHOD> <path> [jsonBody]'); process.exit(1); }
const path = rawPath.replace('{id}', cred.accountId);
const url = 'https://api.cloudflare.com/client/v4' + path;

(async () => {
  const res = await fetch(url, {
    method,
    headers: {
      'Authorization': 'Bearer ' + cred.apiToken,
      'Content-Type': 'application/json'
    },
    body: body || undefined
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { console.log('HTTP ' + res.status + ' raw: ' + text.slice(0, 500)); return; }
  if (json.success === false) {
    console.log('HTTP ' + res.status + ' success=false');
    (json.errors || []).forEach(e => console.log('  err ' + e.code + ': ' + e.message));
  } else {
    console.log('HTTP ' + res.status + ' OK');
  }
  console.log(JSON.stringify(json.result, null, 2).slice(0, 3000));
})();
