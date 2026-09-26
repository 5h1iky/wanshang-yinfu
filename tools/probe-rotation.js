// 轮换论判定：用 WebView 当前 cookie 直测 profile/self（vs 备份已知死亡）
const fs = require('fs');
const path = require('path');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

function parseBackup() {
  const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
  const m = xml.match(/<string name="cookies">([^<]+)<\/string>/);
  const jar = {};
  if (!m) return jar;
  for (const p of m[1].split(';')) {
    const i = p.indexOf('=');
    if (i > 0) jar[p.slice(0, i).trim()] = p.slice(i + 1).trim();
  }
  return jar;
}

async function probe(cookieStr, label) {
  const res = await fetch(
    'https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC',
    { headers: { 'User-Agent': UA, Cookie: cookieStr, Referer: 'https://www.douyin.com/' } }
  );
  const j = await res.json();
  const ok = !!(j.user && j.user.uid);
  console.log(label + ': ' + (ok ? '✅ 存活 ' + j.user.nickname : '❌ 死亡 status=' + j.status_code));
  return ok;
}

(async () => {
  const backup = parseBackup();
  const webview = {};
  for (const c of JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'))) {
    webview[c.name] = c.value;
  }
  const sessKeys = ['sessionid', 'sessionid_ss', 'sid_tt', 'sid_guard', 'uid_tt', 'uid_tt_ss'];
  const same = sessKeys.filter((k) => backup[k] && webview[k] && backup[k] === webview[k]);
  const diff = sessKeys.filter((k) => backup[k] && webview[k] && backup[k] !== webview[k]);
  console.log('会话键对比: 相同=' + same.length + ' 不同=' + diff.length + ' (' + diff.join(',') + ')');
  await probe(Object.entries(webview).map(([k, v]) => k + '=' + v).join('; '), 'WebView 当前 cookie');
  await probe(Object.entries(backup).map(([k, v]) => k + '=' + v).join('; '), '备份 cookie(对照)');
})();
