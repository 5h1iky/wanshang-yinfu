// 查 bd_ticket_guard_server_data cookie 的 JSON 键结构（只打键名与短前缀）
const fs = require('fs');
const path = require('path');
const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
const m = xml.match(/<string name="cookies">([^<]+)<\/string>/);
const jar = {};
for (const p of m[1].split(';')) {
  const i = p.indexOf('=');
  if (i > 0) jar[p.slice(0, i).trim()] = decodeURIComponent(p.slice(i + 1).trim());
}
const raw = jar['bd_ticket_guard_server_data'] || '';
console.log('bd_ticket_guard_server_data 前60: ' + raw.slice(0, 60));
try {
  const j = JSON.parse(raw);
  for (const [k, v] of Object.entries(j)) {
    console.log('  ' + k + ' = ' + (typeof v === 'string' ? v.slice(0, 24) + '(len' + v.length + ')' : JSON.stringify(v).slice(0, 40)));
  }
} catch (e) {
  console.log('非JSON: ' + e.message);
}
console.log('---');
for (const k of ['ts_sign', 'bd_ticket_guard_client_data', '_bd_ticket_crypt_cookie']) {
  const v = jar[k];
  console.log(k + ' = ' + (v ? v.slice(0, 24) + '(len' + v.length + ')' : '(无)'));
}
