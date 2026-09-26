// 只读实测第二组：验证排序前二的两个接口
//   1) 用户主页作品 /aweme/v1/web/aweme/post/   （调研判"低风险，最推荐"）
//   2) 评论列表     /aweme/v1/web/comment/list/ （调研判"风控最低，建议替换 DOM 抓取"）
//   3) 关注列表     /aweme/v1/web/user/following/list/ （验证 max_time 坑）
// 判据：HTTP 200 + status_code=0 + 能解出条目；403/空列表都记下来。
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const COOKIE = fs.readFileSync(path.join(__dirname, '_cookie.txt'), 'utf8').trim();
const jar = {};
COOKIE.split(/;\s*/).forEach(s => { const i = s.indexOf('='); if (i > 0) jar[s.slice(0, i).trim()] = s.slice(i + 1).trim(); });

// 该接口特有版本号（cv-cat 注释：aweme/post 用 290100，不是通用的 170400）
function q(base, ver) {
  const p = Object.assign({
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    update_version_code: ver || '170400', pc_client_type: '1', pc_libra_divert: 'Windows',
    support_h265: '1', support_dash: '1',
    version_code: ver || '170400', version_name: ver ? '29.1.0' : '17.4.0',
    cookie_enabled: 'true', screen_width: '2560', screen_height: '1440',
    browser_language: 'zh-CN', browser_platform: 'Win32', browser_name: 'Chrome',
    browser_version: '135.0.0.0', browser_online: 'true', engine_name: 'Blink',
    engine_version: '135.0.0.0', os_name: 'Windows', os_version: '10',
    cpu_core_num: '20', device_memory: '8', platform: 'PC',
    downlink: '0.55', effective_type: '3g', round_trip_time: '0',
  }, base);
  const s = Object.entries(p).map(([k, v]) => k + '=' + v).join('&');
  return s + '&a_bogus=' + encodeURIComponent(createSigner().makeABogus(s, 0));
}

async function hit(label, url, refer) {
  console.log(`\n--- ${label} ---`);
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': UA, Referer: refer, Cookie: COOKIE, Accept: 'application/json' },
      redirect: 'manual',
    });
    const t = await res.text();
    let j = null; try { j = JSON.parse(t); } catch (e) { }
    console.log(`  HTTP ${res.status}  ${t.length}B`);
    if (!j) { console.log('  非 JSON: ' + t.slice(0, 160)); return false; }
    console.log(`  status_code=${j.status_code} ${j.status_msg || ''}`);
    const list = j.aweme_list || j.comments || j.following_list || j.user_following_list || [];
    console.log(`  条目数 = ${list.length}`);
    if (list.length) {
      const it = list[0];
      const desc = String(it.desc !== undefined ? it.desc : (it.text !== undefined ? it.text : (it.nickname || '')));
      console.log(`  样例 = ${desc.slice(0, 44)}`);
      const v1 = (it.video_tag || []).filter(x => x.level === 1);
      if (v1.length) console.log(`  类目 = ${v1.map(x => x.tag_name).join('/')}`);
      if (it.statistics) console.log(`  统计 = 赞${it.statistics.digg_count} 评${it.statistics.comment_count}`);
    }
    if (j.status_code !== 0) { console.log('  ❌ status_code 非 0'); return false; }
    console.log(list.length ? '  ✅ 可用' : '  ⚠️ 通了但空（可能真没数据，或参数坑）');
    return true;
  } catch (e) { console.log('  异常: ' + e.message); return false; }
}

(async () => {
  // 先拿 sec_uid
  const sr = await fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?' + q({}, null), {
    headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: COOKIE }, redirect: 'manual',
  });
  let sj = null; try { sj = JSON.parse(await sr.text()); } catch (e) { }
  const secUid = sj && sj.user ? sj.user.sec_uid : '';
  const uid = sj && sj.user ? sj.user.uid : '';
  console.log(`自己: uid=${uid} sec_uid=${String(secUid).slice(0, 20)}... 昵称=${sj && sj.user ? sj.user.nickname : '?'}`);

  const B = 'https://www.douyin.com';

  // 1) 主页作品（自己的，from_user_page=0）
  await hit('主页作品 /aweme/post/（自己）',
    B + '/aweme/v1/web/aweme/post/?' + q({
      sec_user_id: secUid, max_cursor: '0', locate_query: 'false',
      show_live_replay_strategy: '1', need_time_list: '1', time_list_query: '0',
      whale_cut_token: '', cut_version: '1', count: '18',
      publish_video_strategy_type: '2', from_user_page: '0',
    }, '290100'),
    `https://www.douyin.com/user/${secUid}`);
  await new Promise(r => setTimeout(r, 1500));

  // 2) 评论列表：需要真实 aweme_id，先用 tab/feed 取一条
  console.log('\n[预备] 取一条真实 aweme_id 用于评论接口 ...');
  const fr = await fetch(B + '/aweme/v1/web/tab/feed/?' + q({ count: '3', refresh_index: '1', tag_id: '' }, null), {
    headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: COOKIE }, redirect: 'manual',
  });
  let fj = null; try { fj = JSON.parse(await fr.text()); } catch (e) { }
  const al = (fj && fj.aweme_list) || [];
  const aid = al.length ? al[0].aweme_id : '';
  console.log(`  aweme_id = ${aid}`);
  if (aid) {
    await hit('评论列表 /comment/list/（www-hj 域，极简 3 参数）',
      'https://www-hj.douyin.com/aweme/v1/web/comment/list/?' +
      'aweme_id=' + aid + '&cursor=0&count=20&a_bogus=' +
      encodeURIComponent(createSigner().makeABogus('aweme_id=' + aid + '&cursor=0&count=20', 0)),
      'https://www.douyin.com/');
    await new Promise(r => setTimeout(r, 1500));
  }

  // 3) 关注列表（验证 max_time 坑）
  const nowSec = Math.floor(Date.now() / 1000);
  await hit(`关注列表 /user/following/list/（max_time=${nowSec}）`,
    B + '/aweme/v1/web/user/following/list/?' + q({
      user_id: uid, sec_user_id: secUid, offset: '0', min_time: '0', max_time: String(nowSec),
      count: '20', source_type: '1', gps_access: '0', address_book_access: '0', is_top: '1',
      webcast_sdk_version: '170400', webcast_version_code: '170400',
    }, null),
    `https://www.douyin.com/user/${secUid}`);

  console.log('\n完毕。');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
