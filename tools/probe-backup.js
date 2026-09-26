// 备份会话 vs WebView 现存 cookie 对拼 + 备份会话直测 profile/self
// 只输出键名与同/异结论；接口结果输出 uid/nickname（用户已授权）
const fs = require('fs');
const path = require('path');

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

(async () => {
  const backup = parseBackup();
  const webview = {};
  for (const c of JSON.parse(fs.readFileSync(path.join(__dirname, 'webview-cookies.json'), 'utf8'))) {
    webview[c.name] = c.value;
  }
  const bk = Object.keys(backup), wv = Object.keys(webview);
  console.log('备份键数=' + bk.length + ' WebView键数=' + wv.length);
  const common = bk.filter((k) => k in webview);
  const same = common.filter((k) => backup[k] === webview[k]);
  const diff = common.filter((k) => backup[k] !== webview[k]);
  console.log('共有键=' + common.length + ' 值相同=' + same.length + ' 值不同=' + diff.length);
  console.log('值不同的键: ' + diff.join(', '));
  console.log('只在备份: ' + bk.filter((k) => !(k in webview)).join(', '));

  const cookieStr = bk.map((k) => k + '=' + backup[k]).join('; ');
  const url =
    'https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&update_version_code=170400&pc_client_type=1&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=153.0.0.0&browser_online=true&os_name=Windows&os_version=10&platform=PC';
  const res = await fetch(url, {
    headers: {
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36',
      Cookie: cookieStr,
      Referer: 'https://www.douyin.com/',
      Accept: 'application/json',
    },
    redirect: 'manual',
  });
  const text = await res.text();
  try {
    const j = JSON.parse(text);
    if (j.user && j.user.uid) {
      console.log('✅ 备份会话有效: ' + j.user.nickname + ' uid=' + j.user.uid);
    } else {
      console.log('❌ 备份会话无效: status_code=' + j.status_code + ' status_msg=' + j.status_msg);
    }
  } catch (e) {
    console.log('非JSON响应 len=' + text.length + ' 前100: ' + text.slice(0, 100));
  }
})();
