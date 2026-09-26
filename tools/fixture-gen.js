// 从 M0.5① 实测的 feed 响应生成固件（真实数据快照，供 M2 页面开发用）
const fs = require('fs');
const path = require('path');

const src = JSON.parse(fs.readFileSync(path.join(__dirname, 'm05-feed-result.json'), 'utf8'));
const out = (src.aweme_list || []).slice(0, 5).map((it) => ({
  title: it.desc || '',
  playUrl:
    (it.video && it.video.play_addr && it.video.play_addr.url_list && it.video.play_addr.url_list[0]) ||
    '',
  coverUrl:
    (it.video && it.video.cover && it.video.cover.url_list && it.video.cover.url_list[0]) ||
    (it.video && it.video.origin_cover && it.video.origin_cover.url_list && it.video.origin_cover.url_list[0]) ||
    '',
}));
const dst = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'feed_fixture.json');
fs.mkdirSync(path.dirname(dst), { recursive: true });
fs.writeFileSync(dst, JSON.stringify(out, null, 2), 'utf8');
console.log('items = ' + out.length + '，playUrl 齐全 = ' + out.filter((o) => o.playUrl).length + '，cover 齐全 = ' + out.filter((o) => o.coverUrl).length);
