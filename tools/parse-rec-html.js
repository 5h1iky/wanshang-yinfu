// 从已存盘的推荐页 HTML 里把首批推荐抠出来，验证"首批推荐是服务端渲染进文档"的假设
const fs = require('fs');
const h = fs.readFileSync(__dirname + '/rec-page.html', 'utf8');
// React Flight 载荷里的 JSON 是转义过的（\" 形式），先还原
const esc = h.split('\\"').join('"');
console.log('原始 ' + h.length + ' → 去转义 ' + esc.length);

const ids = [...esc.matchAll(/"aweme_id":"(\d{15,})"/g)].map(x => x[1]);
const uniq = [...new Set(ids)];
console.log('aweme_id 命中 ' + ids.length + '，去重 ' + uniq.length);
console.log('前 12 个: ' + uniq.slice(0, 12).join(', '));

const playRe = /https:\/\/www\.douyin\.com\/aweme\/v1\/play\/?[^"]*"/g;
const plays = [...esc.matchAll(playRe)].map(x => x[0].replace(/"$/, ''));
console.log('\nplay 地址命中 ' + plays.length);
plays.slice(0, 3).forEach(p => console.log('  ' + p.slice(0, 170)));

const descs = [...esc.matchAll(/"desc":"([^"]{4,60})"/g)].map(x => x[1]);
console.log('\ndesc 命中 ' + descs.length);
descs.slice(0, 8).forEach(d => console.log('  「' + d + '」'));

const authors = [...esc.matchAll(/"nickname":"([^"]{1,20})"/g)].map(x => x[1]);
console.log('\nnickname 命中 ' + authors.length + '，前 8: ' + [...new Set(authors)].slice(0, 8).join(' / '));

// 有没有码率档位信息（决定手表能不能挑低码率）
const gears = [...esc.matchAll(/"gear_name":"([^"]+)"/g)].map(x => x[1]);
console.log('\ngear_name 命中 ' + gears.length + '，种类: ' + [...new Set(gears)].join(', '));
const brs = [...esc.matchAll(/"bit_rate":(\d+)/g)].map(x => +x[1]);
if (brs.length) console.log('bit_rate 值(kbps) 分布: ' + [...new Set(brs.sort((a, b) => a - b))].join(', '));
const h265 = [...esc.matchAll(/"is_h265":(\d)/g)].map(x => x[1]);
console.log('is_h265 取值: ' + [...new Set(h265)].join(','));
