// 看服务端实际给了哪些码率档，为手表选档定依据
const fs = require('fs');
const j = JSON.parse(fs.readFileSync(__dirname + '/detail-raw.json', 'utf8'));
const v = j.aweme_detail.video;
console.log('play_addr.uri=' + v.play_addr.uri);
console.log('video 尺寸 ' + v.width + 'x' + v.height + '  duration=' + (v.duration / 1000).toFixed(1) + 's  is_h265=' + v.is_h265 + '  video_model=' + v.video_model);
console.log('ratio=' + v.ratio + '  format=' + v.format);
const brs = v.bit_rate || [];
console.log('\nbit_rate 档数: ' + brs.length);
brs.forEach(b => {
  const pa = b.play_addr || {};
  const l = pa.url_list || [];
  const hosts = [...new Set(l.map(u => (u.split('/')[2] || '')))].join(' ');
  console.log('  gear=' + String(b.gear_name).padEnd(18)
    + ' br=' + String(b.bit_rate).padStart(6) + 'kbps'
    + ' quality_type=' + b.quality_type
    + ' w=' + pa.width + 'x' + pa.height
    + ' uri=' + pa.uri
    + '\n     hosts: ' + hosts
    + '\n     键: ' + Object.keys(b).join(','));
});
console.log('\nplay_addr.url_list:');
(v.play_addr.url_list || []).forEach((u, i) => console.log('  [' + i + '] ' + u.slice(0, 120)));
