// GeckoView 体积曲线：量各主要版本的 armeabi-v7a AAR 实际大小，找"够用又最小"的甜点
// 版本号形如 157.0.20260924084938（major.minor.日期），取每个 major 里最新的一版
const ROOT = 'https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview-armeabi-v7a';
const MAJORS = [95, 100, 105, 110, 115, 120, 125, 130, 135, 140, 145, 150, 155, 157];

(async () => {
  const meta = await (await fetch(`${ROOT}/maven-metadata.xml`, { headers: { 'User-Agent': 'x' } })).text();
  const all = [...meta.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1]);
  console.log('可用版本总数: ' + all.length);

  // 每个 major 取"日期最大"的一版
  const pick = (maj) => {
    const list = all.filter(v => parseInt(v.split('.')[0], 10) === maj);
    if (!list.length) return null;
    return list.sort((a, b) => (a.split('.')[2] || '').localeCompare(b.split('.')[2] || '')).pop();
  };

  console.log('\n版本            AAR 体积    相对最新');
  let latestSize = 0;
  const rows = [];
  for (const maj of MAJORS) {
    const v = pick(maj);
    if (!v) { console.log(`  ${String(maj).padEnd(6)}  (无此版本)`); continue; }
    try {
      const h = await fetch(`${ROOT}/${v}/${`geckoview-armeabi-v7a-${v}.aar`}`, { method: 'HEAD', headers: { 'User-Agent': 'x' } });
      const len = Number(h.headers.get('content-length') || 0);
      if (!len) { console.log(`  ${v}  (无长度)`); continue; }
      const mb = len / 1048576;
      if (maj === 157) latestSize = mb;
      rows.push({ maj, v, mb });
    } catch (e) { console.log(`  ${v}  HEAD 失败`); }
  }
  for (const r of rows) {
    const rel = latestSize ? `  ${((r.mb / latestSize - 1) * 100).toFixed(0)}%` : '';
    console.log(`  ${String(r.maj).padEnd(6)} ${r.v.padEnd(26)} ${r.mb.toFixed(1).padStart(5)} MB${rel}`);
  }

  const min = rows.reduce((a, b) => (a.mb < b.mb ? a : b));
  const max = rows.reduce((a, b) => (a.mb > b.mb ? a : b));
  console.log(`\n最小: ${min.v} = ${min.mb.toFixed(1)}MB    最大: ${max.v} = ${max.mb.toFixed(1)}MB    差距 ${(max.mb - min.mb).toFixed(1)}MB`);
})();
