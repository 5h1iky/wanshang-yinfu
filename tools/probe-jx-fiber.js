// 只读：从 discover-video-card-item 的 React fiber 里找 aweme id 所在的属性路径
// 用法: node tools/cdp-eval.js @tools/probe-jx-fiber.js
(function () {
  var cards = document.querySelectorAll('[class*="discover-video-card-item"]');
  var out = { cardCount: cards.length, found: [], tried: 0 };
  if (!cards.length) return JSON.stringify(out);

  function fiberOf(el) {
    var ks = Object.keys(el);
    for (var i = 0; i < ks.length; i++) if (ks[i].indexOf('__reactFiber$') === 0) return el[ks[i]];
    return null;
  }
  // 在对象上浅层找"像 aweme id"的键（值形如 19 位数字）
  function scanObj(o, path, depth, hits) {
    if (!o || typeof o !== 'object' || depth > 4 || hits.length >= 6) return;
    var ks;
    try { ks = Object.keys(o); } catch (e) { return; }
    for (var i = 0; i < ks.length; i++) {
      var k = ks[i], v;
      try { v = o[k]; } catch (e) { continue; }
      if (typeof v === 'string' && /^\d{15,20}$/.test(v)) hits.push(path + '.' + k + ' = str(' + v.length + ')');
      else if (typeof v === 'number' && v > 1e14) hits.push(path + '.' + k + ' = num');
      else if (v && typeof v === 'object' && !Array.isArray(v) && /aweme|item|vid|modal/i.test(k)) {
        scanObj(v, path + '.' + k, depth + 1, hits);
      }
    }
  }

  for (var c = 0; c < Math.min(cards.length, 3); c++) {
    var f = fiberOf(cards[c]);
    var hops = 0, hits = [];
    while (f && hops < 14 && hits.length < 6) {
      if (f.memoizedProps) scanObj(f.memoizedProps, 'p' + hops + '.props', 0, hits);
      if (f.memoizedState) scanObj(f.memoizedState, 'p' + hops + '.state', 0, hits);
      f = f.return; hops++;
    }
    out.tried++;
    out.found.push({ card: c, hits: hits });
  }
  return JSON.stringify(out);
})();
