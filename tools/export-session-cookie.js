// 只读：把设备上保存的登录 cookie 导出来，供对照实验用（只写键名清单到 stdout，值写文件）
// 用法: adb shell run-as com.dywatch.app cat .../dywatch_session.xml > tools/_session.xml
//       node tools/export-session-cookie.js
const fs = require('fs');
const p = 'D:/dev/dywatch/tools/_session.xml';
if (!fs.existsSync(p)) {
  console.log('缺少 tools/_session.xml，请先 adb 导出');
  process.exit(1);
}
const buf = fs.readFileSync(p);
// adb 重定向出来的文件常是 UTF-16LE，按 BOM 判断
const xml = (buf[0] === 0xFF && buf[1] === 0xFE)
  ? buf.toString('utf16le').replace(/^﻿/, '')
  : buf.toString('utf8');
// SharedPreferences XML 里 cookie 是一个 <string name="cookies">a=b; c=d</string>
const m = xml.match(/<string name="([^"]*cookie[^"]*)"[^>]*>([\s\S]*?)<\/string>/i)
  || xml.match(/<string name="([^"]*)"[^>]*>([\s\S]*?)<\/string>/);
if (!m) { console.log('没找到 cookie 字段'); console.log(xml.slice(0, 400)); process.exit(1); }
const name = m[1];
const raw = m[2].replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
fs.writeFileSync('D:/dev/dywatch/tools/_session_cookie.txt', raw, 'utf8');
const keys = raw.split(/[;,]\s*/).map(s => s.split('=')[0].trim()).filter(Boolean);
console.log('字段名 =', name);
console.log('cookie 键数量 =', keys.length);
console.log('键名 =', keys.join(', '));
console.log('\n是否含关键登录键:');
['sessionid', 'sessionid_ss', 'sid_tt', 'sid_guard', 'uid_tt', 'passport_csrf_token', 'ttwid', 'webid', 'msToken']
  .forEach(k => console.log(`  ${k.padEnd(20)} ${keys.includes(k) ? '✅ 有' : '— 无'}`));
console.log('\n完整值已写入 tools/_session_cookie.txt（该文件含敏感值，勿入库）');
