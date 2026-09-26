// 数据级判定：refresh_index 到底变不变内容？登录态到底变不变推荐？
// 拉 3 个游标（带登录态）+ 1 组匿名，打印 aweme_id+标题前 18 字，比交集。
const fs = require('fs');
const path = require('path');
const { createSigner } = require('./sign/abogus.js');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36';

function loadCookies() {
  const xml = fs.readFileSync(path.join(__dirname, 'dy-app-session.xml'), 'utf8');
  const m = xml.match(/<string name="cookies">([^<]+)<\/string>/);
  return m ? m[1] : '';
}

function buildQuery(count, idx) {
  const p = {
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web', update_version_code: '170400',
    pc_client_type: '1', pc_libra_divert: 'Windows', support_h265: '1', support_dash: '1',
    version_code: '170400', version_name: '17.4.0', cookie_enabled: 'true',
    screen_width: '2560', screen_height: '1440', browser_language: 'zh-CN', browser_platform: 'Win32',
    browser_name: 'Chrome', browser_version: '153.0.0.0', browser_online: 'true',
    os_name: 'Windows', os_version: '10', platform: 'PC', downlink: '10', effective_type: '4g', round_trip_time: '0',
    count: String(count), refresh_index: String(idx), tag_id: '',
  };
  const q = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return q + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(q, 0));
}

async function fetchFeed(cookieStr, idx, label) {
  const url = 'https://www.douyin.com/aweme/v1/web/tab/feed/?' + buildQuery(10, idx);
  const headers = { 'User-Agent': UA, Referer: 'https://www.douyin.com/' };
  if (cookieStr) headers.Cookie = cookieStr;
  const res = await fetch(url, { headers });
  const j = await res.json();
  const list = (j.aweme_list || []).map((a) => ({ id: String(a.aweme_id), desc: (a.desc || '').slice(0, 18) }));
  console.log(`--- ${label} (refresh_index=${idx}) HTTP ${res.status} 共 ${list.length} 条`);
  for (const it of list) console.log('   ' + it.id + ' 「' + it.desc + '」');
  return list.map((x) => x.id);
}

(async () => {
  const cookies = loadCookies();
  const p1 = await fetchFeed(cookies, 1, '登录态第1页');
  const p2 = await fetchFeed(cookies, 2, '登录态第2页');
  const p3 = await fetchFeed(cookies, 3, '登录态第3页');
  const anon = await fetchFeed('', 1, '匿名第1页');
  const inter = (a, b) => a.filter((x) => b.includes(x));
  console.log('\n== 交集分析');
  console.log('登录页1∩页2 = ' + inter(p1, p2).length + ' 条重复');
  console.log('登录页1∩页3 = ' + inter(p1, p3).length + ' 条重复');
  console.log('登录页1∩匿名 = ' + inter(p1, anon).length + ' 条重复');
})();
