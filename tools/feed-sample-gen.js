// 从 M0.5① 实测响应抽样生成单测样本（真实数据，1-2 条）
const fs = require('fs');
const path = require('path');
const src = JSON.parse(fs.readFileSync(path.join(__dirname, 'm05-feed-result.json'), 'utf8'));
const sample = { status_code: src.status_code, aweme_list: (src.aweme_list || []).slice(0, 2) };
const dst = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'feed_sample.json');
fs.mkdirSync(path.dirname(dst), { recursive: true });
fs.writeFileSync(dst, JSON.stringify(sample), 'utf8');
console.log('样本条数 = ' + sample.aweme_list.length + '，大小 = ' + fs.statSync(dst).size + 'B');
