// 全库扫硬编码长字符串（可能是漏网的会话真值/密钥）。
// 用法：node scan-longstr.js
const { execSync } = require('child_process');
const fs = require('fs');
const files = execSync('git ls-files', { encoding: 'utf8' })
  .split('\n').map(s => s.trim()).filter(Boolean);
// 只扫文本类
const re = /["'`]([A-Za-z0-9+/=_-]{64,})["'`]/;
const allow = /gradle-wrapper|\.jar|\.png|\.jpg|package-lock/;
let hits = [];
for (const f of files) {
  if (allow.test(f)) continue;
  let c;
  try { c = fs.readFileSync(f, 'utf8'); } catch { continue; }
  if (c.length > 2e6) continue;
  const m = c.match(re);
  if (m) {
    // 排除已知合法长串：GPL 文本、生成器 path、URL
    const sample = m[1];
    if (/^[0-9a-f]{64,}$/i.test(sample) && f.includes('LICENSE')) continue;
    hits.push(`${f}  len=${sample.length}  head=${sample.slice(0, 40)}…`);
    if (hits.length >= 15) break;
  }
}
console.log(hits.length ? hits.join('\n') : '无 64+ 长串硬编码');
