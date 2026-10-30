// 深挖：maven-metadata.xml 里版本的真实顺序与最新值（上一次取的"最后一个元素"可能是错的）
const ROOT = 'https://maven.mozilla.org/maven2/org/mozilla/geckoview';

function cmpVer(a, b) {
  const pa = a.split('.').map(x => parseInt(x, 10) || 0);
  const pb = b.split('.').map(x => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d;
  }
  return 0;
}

(async () => {
  for (const art of ['geckoview-nightly-armeabi-v7a', 'geckoview-armeabi-v7a']) {
    const r = await fetch(`${ROOT}/${art}/maven-metadata.xml`, { headers: { 'User-Agent': 'x' } });
    const xml = await r.text();
    const vers = [...xml.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1]);
    const latestTag = (xml.match(/<latest>([^<]+)<\/latest>/) || [])[1];
    const releaseTag = (xml.match(/<release>([^<]+)<\/release>/) || [])[1];
    const sorted = [...vers].sort(cmpVer);
    console.log('===== ' + art + ' =====');
    console.log('  <latest> 标签 : ' + latestTag);
    console.log('  <release>标签 : ' + releaseTag);
    console.log('  列表最后 3 个 : ' + vers.slice(-3).join(' | '));
    console.log('  列表最前 3 个 : ' + vers.slice(0, 3).join(' | '));
    console.log('  按版本号排序后最大的 3 个 : ' + sorted.slice(-3).join(' | '));

    // 用排好序的最大版本去 HEAD，看真实体积
    const top = sorted[sorted.length - 1];
    const h = await fetch(`${ROOT}/${art}/${top}/${art}-${top}.aar`, { method: 'HEAD', headers: { 'User-Agent': 'x' } });
    console.log(`  → 最大版本 ${top} 的 AAR: HTTP ${h.status}` +
      (h.headers.get('content-length') ? `  ${(h.headers.get('content-length') / 1048576).toFixed(1)} MB` : ''));

    // 看看有没有 2026 年的版本（按版本串里带的时间戳判断）
    const recent = vers.filter(v => /\.2026\d{6}/.test(v));
    console.log('  带 2026 时间戳的版本数: ' + recent.length + (recent.length ? '  例: ' + recent.slice(-2).join(', ') : ''));
    console.log('');
  }
})();
