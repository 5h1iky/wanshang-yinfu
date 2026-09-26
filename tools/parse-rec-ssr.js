// 按归一化(camelCase)键名解析推荐页 SSR 载荷：一条推荐 = 哪些字段
const fs = require('fs');
const h = fs.readFileSync(__dirname + '/rec-page.html', 'utf8');
const esc = h.split('\\"').join('"').split('\\u0026').join('&');

function count(k) { return (esc.match(new RegExp('"' + k + '"\\s*:', 'g')) || []).length; }
['awemeId', 'aweme_id', 'videoId', 'desc', 'author', 'nickname', 'playApi', 'playAddrH265',
 'statistics', 'diggCount', 'commentCount', 'duration', 'cover', 'originCover', 'challengeIdList']
  .forEach(k => { const n = count(k); if (n) console.log(k.padEnd(18) + ' x' + n); });

const ids = [...esc.matchAll(/"awemeId":"(\d{15,})"/g)].map(x => x[1]);
console.log('\nawemeId 去重 ' + [...new Set(ids)].length + ' 条');
console.log('前 10: ' + [...new Set(ids)].slice(0, 10).join(', '));

// 抽一条完整记录看看边界
const k = esc.indexOf('"playApi"');
if (k > 0) {
  const from = Math.max(0, esc.lastIndexOf('{', k - 2500));
  const seg = esc.slice(from, k + 900);
  const descs = [...seg.matchAll(/"desc":"([^"]{0,70})"/g)].map(x => x[1]);
  const auth = [...seg.matchAll(/"nickname":"([^"]{1,24})"/g)].map(x => x[1]);
  const dur = [...seg.matchAll(/"duration":(\d+)/g)].map(x => x[1]);
  const size = [...seg.matchAll(/"playAddrSize":(\d+)/g)].map(x => +x[1]);
  console.log('\n=== 样本片段解析 ===');
  console.log('desc: ' + JSON.stringify(descs.slice(0, 3)));
  console.log('nickname: ' + JSON.stringify(auth.slice(0, 3)));
  console.log('duration(ms): ' + JSON.stringify(dur.slice(0, 5)));
  console.log('playAddrSize(bytes): ' + JSON.stringify(size.slice(0, 5)));
  const pa = /"playApi":"([^"]+)"/.exec(seg);
  if (pa) console.log('playApi 全长 ' + pa[1].length + '\n  ' + pa[1]);
}
