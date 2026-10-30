// 解析 .cf-token.txt（UTF-8），返回结构化凭据。绝不打印完整密钥值。
// 用法：node cf-cred.js  （打印结构摘要 + 写出 cf-cred.json 供后续脚本 require）
const fs = require('fs');
const TOKEN_FILE = process.env.CF_TOKEN_FILE || require('path').join(__dirname, '..', '.cf-token.txt');
const txt = fs.readFileSync(TOKEN_FILE, 'utf8');
const lines = txt.split(/\r?\n/).map(s => s.trim()).filter(Boolean);

const cred = { accountId: null, apiToken: null, r2AccessKey: null, r2Secret: null, s3Endpoint: null };
for (const l of lines) {
  if (/^[0-9a-f]{32}$/i.test(l)) {
    if (!cred.accountId) cred.accountId = l; else if (!cred.r2AccessKey) cred.r2AccessKey = l;
  } else if (/^cfat_/.test(l)) cred.apiToken = l;
  else if (/^[0-9a-f]{64}$/i.test(l)) cred.r2Secret = l;
  else if (/^https:\/\/.*r2\.cloudflarestorage\.com/.test(l)) cred.s3Endpoint = l;
  else if (/^https=/.test(l) || l.startsWith('curl') || l.startsWith('-H')) {
    // "S3 API 端点" 行在文件里被写成了 "https = //..." 形式，单独处理
    const m = l.match(/https\s*=\s*\/\/([^\s"]+)/);
    if (m && !cred.s3Endpoint) cred.s3Endpoint = 'https://' + m[1];
  }
}
// 兜底：从 curl 示例里抓 endpoint
if (!cred.s3Endpoint) {
  const m = txt.match(/https\s*=\s*\/\/([a-z0-9]+\.r2\.cloudflarestorage\.com)/i);
  if (m) cred.s3Endpoint = 'https://' + m[1];
}

const mask = v => v ? v.slice(0, 6) + '…(' + v.length + ')' : 'MISSING';
console.log('accountId   :', mask(cred.accountId));
console.log('apiToken    :', mask(cred.apiToken));
console.log('r2AccessKey :', mask(cred.r2AccessKey));
console.log('r2Secret    :', mask(cred.r2Secret));
console.log('s3Endpoint  :', cred.s3Endpoint || 'MISSING');

const missing = Object.entries(cred).filter(([, v]) => !v).map(([k]) => k);
if (missing.length) { console.error('MISSING FIELDS: ' + missing.join(', ')); process.exit(1); }

fs.writeFileSync((process.env.CF_CRED_FILE || require('path').join(__dirname, '..', 'cf-cred.json')), JSON.stringify(cred, null, 2));
console.log('-> cf-cred.json written (gitignored workspace, not the project repo)');
