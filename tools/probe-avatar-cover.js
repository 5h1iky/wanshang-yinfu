// 探针：作者头像与视频封面字段结构实测（2026-09-27）
// 目的：修「作者头像一直没加载」前先把服务端真值看清楚，不猜字段。
//   1) author 下与头像有关的键到底叫什么（avatar_thumb / avatar_medium / avatar_larger）
//   2) 头像 URL 的宿主是谁、直连能不能拿到图（带/不带 Referer 各测一次）
//   3) video.cover 的结构——顺带验证 urlFromAddr() 给封面用是否根本取不到 URL
// 判据：HTTP 200 + content-type: image/*；403/空串都如实记录。
const fs = require('fs');
const path = require('path');
const { createSigner, UA } = require('./sign/abogus.js');

const COOKIE = fs.readFileSync(path.join(__dirname, '_session_cookie.txt'), 'utf8').trim();

function q(base) {
  const p = Object.assign({
    device_platform: 'webapp', aid: '6383', channel: 'channel_pc_web',
    update_version_code: '170400', pc_client_type: '1', pc_libra_divert: 'Windows',
    support_h265: '1', support_dash: '1',
    version_code: '170400', version_name: '17.4.0',
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

const B = 'https://www.douyin.com';

function short(s, n) { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) + '…' : s; }

// 试取一张图：分别用「不带 Referer」和「带 Referer」两种方式
async function tryImage(label, url) {
  if (!url) { console.log(`  ${label}: (空 URL，跳过)`); return; }
  for (const [tag, headers] of [
    ['裸请求', { 'User-Agent': UA }],
    ['带Referer', { 'User-Agent': UA, Referer: 'https://www.douyin.com/' }],
  ]) {
    try {
      const res = await fetch(url, { headers, redirect: 'follow' });
      const buf = await res.arrayBuffer();
      console.log(`  ${label} [${tag}] HTTP ${res.status} ${res.headers.get('content-type')} ${buf.byteLength}B`);
    } catch (e) {
      console.log(`  ${label} [${tag}] 异常 ${e.message}`);
    }
  }
}

(async () => {
  console.log('=== 拉一批真实 feed（带登录态）===');
  const fr = await fetch(B + '/aweme/v1/web/tab/feed/?' + q({ count: '3', refresh_index: '1', tag_id: '' }), {
    headers: { 'User-Agent': UA, Referer: 'https://www.douyin.com/', Cookie: COOKIE, Accept: 'application/json' },
    redirect: 'manual',
  });
  const txt = await fr.text();
  console.log(`HTTP ${fr.status} ${txt.length}B`);
  let j = null; try { j = JSON.parse(txt); } catch (e) { console.log('非 JSON: ' + txt.slice(0, 200)); return; }
  console.log(`status_code=${j.status_code} 条目=${(j.aweme_list || []).length}`);

  const list = j.aweme_list || [];
  if (!list.length) { console.log('没拿到条目，停。'); return; }

  // 逐条盘点：条目类型不同（视频贴 / 图文贴）字段差异很大，必须全批看
  console.log('\n=== 逐条盘点 ===');
  for (let i = 0; i < list.length; i++) {
    const x = list[i];
    console.log(`\n[${i}] aweme_id=${x.aweme_id}`);
    console.log(`    顶层键 = ${Object.keys(x).join(', ')}`);
    console.log(`    author  = ${x.author === undefined ? '(不存在)' : (x.author === null ? 'null' : Object.keys(x.author).join(', '))}`);
    console.log(`    video   = ${x.video === undefined ? '(不存在)' : (x.video === null ? 'null' : Object.keys(x.video).join(', '))}`);
    console.log(`    images  = ${x.images === undefined ? '(不存在)' : (x.images === null ? 'null' : '有 ' + x.images.length + ' 张')}`);
  }

  // 找一条「有头像」和一条「有封面」的条目来取证（不假定是第 0 条）
  const itAva = list.find(x => x.author && x.author.avatar_thumb);
  const itCov = list.find(x => x.video && x.video.cover);
  const it = itAva || list[0];

  if (itAva) {
    console.log('\n=== author 下与头像有关的字段（完整内容）===');
    for (const k of Object.keys(itAva.author)) {
      if (!/avatar/i.test(k)) continue;
      console.log(`--- author.${k} ---`);
      console.log(JSON.stringify(itAva.author[k], null, 1).slice(0, 700));
    }
  } else {
    console.log('\n⚠️ 全批没有任何条目带 author.avatar_thumb');
  }

  if (itCov) {
    console.log('\n=== video 下与封面有关的字段（完整内容）===');
    for (const k of Object.keys(itCov.video)) {
      if (!/cover/i.test(k)) continue;
      console.log(`--- video.${k} ---`);
      console.log(JSON.stringify(itCov.video[k], null, 1).slice(0, 700));
    }
  } else {
    console.log('\n⚠️ 全批没有任何条目带 video.cover');
  }

  console.log('\n=== 各条作者头像候选 ===');
  for (let i = 0; i < list.length; i++) {
    const a = list[i].author || {};
    for (const k of Object.keys(a)) {
      if (!/avatar/i.test(k)) continue;
      const u = a[k] && a[k].url_list ? a[k].url_list[0] : '';
      console.log(`  [${i}] ${a.nickname} ${k}: ${short(u, 110)}`);
    }
  }

  console.log('\n=== video.cover / origin_cover / dynamic_cover 完整内容 ===');
  const v = it.video || {};
  for (const k of ['cover', 'origin_cover', 'dynamic_cover', 'play_addr']) {
    if (v[k] === undefined) { console.log(`--- video.${k}: (不存在)`); continue; }
    console.log(`--- video.${k} ---`);
    console.log(JSON.stringify(v[k], null, 1).slice(0, 900));
  }

  console.log('\n=== 图片可取性实测 ===');
  if (itAva) await tryImage('头像', itAva.author.avatar_thumb.url_list[0]);
  if (itCov) {
    await tryImage('封面', itCov.video.cover.url_list[0]);
    if (itCov.video.origin_cover) await tryImage('原封面', itCov.video.origin_cover.url_list[0]);
  }

  console.log('\n完毕。');
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
