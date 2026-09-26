// 查各档 play_addr 给的是渐进式 mp4 还是 DASH 分离流（后者 MediaPlayer 播出来会没声音）
const fs = require('fs');
const https = require('https');
const j = JSON.parse(fs.readFileSync(__dirname + '/detail-raw.json', 'utf8'));
const v = j.aweme_detail.video;

function host(u) { return (u.split('/')[2] || ''); }
function kind(u) {
  if (/\/aweme\/v1\/play\/dash\//.test(u)) return 'DASH分离流';
  if (/\/aweme\/v1\/play\/?\?/.test(u)) return '跳转式mp4';
  if (/mime_type=video_mp4/.test(u)) return '直连mp4';
  return '其他';
}
console.log('video.format=' + v.format + '  默认 play_addr 候选:');
(v.play_addr.url_list || []).forEach(u => console.log('  ' + kind(u).padEnd(12) + ' ' + host(u)));

console.log('\n各档 www 跳转候选的形态:');
const byKind = {};
(v.bit_rate || []).forEach(b => {
  const l = ((b.play_addr || {}).url_list || []);
  const www = l.find(u => u.indexOf('www.douyin.com') > 0) || l[0] || '';
  const k = kind(www);
  byKind[k] = byKind[k] || [];
  byKind[k].push(b.gear_name + '/' + (b.is_h265 ? 'h265' : 'h264'));
});
Object.keys(byKind).forEach(k => console.log('  ' + k.padEnd(12) + ' x' + byKind[k].length + '  如: ' + byKind[k].slice(0, 4).join(', ')));

// 真探活：取一个 540p H.264 档的 www 地址，看它最终回什么 content-type / 有没有音频轨
const g = (v.bit_rate || []).find(b => !b.is_h265 && /_540_/.test(b.gear_name));
if (g) {
  const u = ((g.play_addr || {}).url_list || []).find(x => x.indexOf('www.douyin.com') > 0);
  console.log('\n探针档位 ' + g.gear_name + ' → ' + (u || '').slice(0, 90));
  const req = https.get(u, { headers: { 'User-Agent': 'Mozilla/5.0', Referer: 'https://www.douyin.com/', Range: 'bytes=0-65535' } }, res => {
    console.log('  HTTP ' + res.statusCode + '  content-type=' + res.headers['content-type'] + '  location=' + (res.headers.location || '-').slice(0, 80));
    const chunks = [];
    res.on('data', c => chunks.push(c));
    res.on('end', () => {
      const buf = Buffer.concat(chunks);
      const s = buf.toString('latin1');
      // MP4 box 扫描：trak 里有 vide/soun handler 才算带音轨
      const boxes = [...s.matchAll(/[\x00\x00](\w{4})/g)].map(x => x[1]);
      console.log('  收到 ' + buf.length + ' 字节; 前 32 字节 hex=' + buf.slice(0, 32).toString('hex'));
      console.log('  含 "vide" handler: ' + (s.indexOf('vide') >= 0) + '  含 "soun": ' + (s.indexOf('soun') >= 0));
      console.log('  是 MPD/XML? ' + (s.trim().indexOf('<') === 0 || s.indexOf('MPD') >= 0));
    });
  });
  req.on('error', e => console.log('  探活失败 ' + e.message));
}
