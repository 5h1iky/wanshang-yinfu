// 会话健康探针：用最朴素参数查 profile/self，判断是"会话被踢"还是"签名问题"
const fs = require('fs');
const path = require('path');
const { UA } = require('./net.js');

function loadJar() {
  const raw = fs.readFileSync(path.join(__dirname, 'dy-cookie.txt'), 'utf8').trim();
  const jar = {};
  for (const part of raw.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) jar[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return jar;
}

(async () => {
  const jar = loadJar();
  const cookieStr = Object.entries(jar)
    .map(([k, v]) => k + '=' + v)
    .join('; ');
  const url =
    'https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&update_version_code=170400&pc_client_type=1&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=153.0.0.0&browser_online=true&os_name=Windows&os_version=10&platform=PC';
  const res = await fetch(url, {
    headers: { 'User-Agent': UA, Cookie: cookieStr, Referer: 'https://www.douyin.com/', Accept: 'application/json' },
    redirect: 'manual',
  });
  const text = await res.text();
  const j = JSON.parse(text);
  if (j.user && j.user.uid) console.log('✅ 会话有效: ' + j.user.nickname + ' uid=' + j.user.uid);
  else console.log('❌ 会话无效: status_code=' + j.status_code + ' status_msg=' + j.status_msg);
})();
