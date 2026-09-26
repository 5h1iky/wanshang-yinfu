// 看 play 地址在 flight 载荷里的上下文结构，搞清楚"一条推荐"到底长什么样
const fs = require('fs');
const h = fs.readFileSync(__dirname + '/rec-page.html', 'utf8');
const esc = h.split('\\"').join('"').split('\\u0026').join('&');
const i = esc.indexOf('https://www.douyin.com/aweme/v1/play/');
if (i < 0) { console.log('没找到'); process.exit(1); }
console.log('=== 第一条 play 地址前后 1400 字符 ===');
console.log(esc.slice(Math.max(0, i - 1000), i + 400));
console.log('\n=== 该窗口里出现的所有键名 ===');
const keys = [...esc.slice(Math.max(0, i - 1000), i + 400).matchAll(/"([A-Za-z_][A-Za-z0-9_]{1,28})"\s*:/g)].map(x => x[1]);
console.log([...new Set(keys)].join(', '));
