// GeckoView 长期可行性核查：
//   ① release 通道停在 99.0（2022-04）——是 Mozilla 停发 release，还是我查错了？
//   ② nightly / beta 通道现在到哪一版？（决定"能不能长期跟"）
//   ③ 现在还发不发 armeabi-v7a（32 位）？—— 本表是 32 位，这条是生死线
const ROOT = 'https://maven.mozilla.org/maven2/org/mozilla/geckoview';
const ARTS = [
  'geckoview-nightly-armeabi-v7a', 'geckoview-nightly-arm64-v8a', 'geckoview-nightly',
  'geckoview-beta-armeabi-v7a', 'geckoview-beta',
  'geckoview-armeabi-v7a',
];

(async () => {
  for (const art of ARTS) {
    const url = `${ROOT}/${art}/maven-metadata.xml`;
    try {
      const r = await fetch(url, { headers: { 'User-Agent': 'x' } });
      if (!r.ok) { console.log(`✘ ${art.padEnd(32)} HTTP ${r.status}`); continue; }
      const xml = await r.text();
      const vers = [...xml.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1]);
      const last = vers[vers.length - 1];
      const updated = (xml.match(/<lastUpdated>([^<]+)<\/lastUpdated>/) || [])[1] || '?';
      console.log(`✔ ${art.padEnd(32)} 共 ${String(vers.length).padStart(4)} 版  最新 ${last}  更新于 ${updated.slice(0, 8)}`);
      // HEAD 体积（最新一版）
      const h = await fetch(`${ROOT}/${art}/${last}/${art}-${last}.aar`, { method: 'HEAD', headers: { 'User-Agent': 'x' } });
      const len = h.headers.get('content-length');
      if (len) console.log(`     AAR 体积: ${(len / 1048576).toFixed(1)} MB`);
    } catch (e) {
      console.log(`✘ ${art.padEnd(32)} 失败: ${e.message}`);
    }
  }
  console.log('\n判读：release 停在 99 且 nightly 仍在更新 → 说明 Mozilla 的「release 通道」artifact 策略变了；');
  console.log('      v7a 若在 nightly 里消失 → 32 位设备只能停留在旧版本。');
})();
