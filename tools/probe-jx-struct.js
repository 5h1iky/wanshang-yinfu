// 只读勘探精选页结构：卡片链接/标题锚点、SSR 数据是否可直接读、当前视口
// 用法: node tools/cdp-eval.js @tools/probe-jx-struct.js
(function () {
  if (location.pathname.indexOf('/jingxuan') !== 0) {
    location.href = 'https://www.douyin.com/jingxuan';
    return 'NAV->/jingxuan';
  }
  var out = { url: location.href.slice(0, 50), vw: innerWidth, vh: innerHeight, docH: document.documentElement.scrollHeight };

  // ① 视频链接（卡片→详情）
  var anchors = document.querySelectorAll('a[href*="/video/"]');
  var ids = [], seen = {};
  for (var i = 0; i < anchors.length; i++) {
    var m = (anchors[i].getAttribute('href') || '').match(/\/video\/(\d{10,})/);
    if (m && !seen[m[1]]) { seen[m[1]] = true; ids.push(m[1]); }
  }
  out.anchorCount = anchors.length;
  out.uniqueIds = ids.length;
  out.idSample = ids.slice(0, 3);

  // ② 卡片根与标题：从第一个锚点往上找带 img + 文本块的容器
  function clsOf(el) { return (el.className || '').toString().slice(0, 60); }
  if (anchors.length) {
    var chain = [], p = anchors[0];
    for (var d = 0; p && d < 7; d++) {
      chain.push({
        up: d, tag: p.tagName, cls: clsOf(p),
        textLen: (p.textContent || '').replace(/\s+/g, ' ').trim().length,
        imgs: p.querySelectorAll('img').length,
        anchors: p.querySelectorAll('a[href*="/video/"]').length
      });
      p = p.parentElement;
    }
    out.anchorChain = chain;
  }
  // 卡片容器的候选 class（含 card/feed/item/video 字样的重复元素）
  var cands = {};
  var all = document.querySelectorAll('div');
  for (var k = 0; k < all.length; k++) {
    var c = all[k].className.toString();
    if (/card|feed|item|video/i.test(c) && all[k].querySelector('a[href*="/video/"]')) {
      cands[c.slice(0, 40)] = (cands[c.slice(0, 40)] || 0) + 1;
    }
  }
  out.cardClasses = Object.keys(cands).slice(0, 8).map(function (kk) { return kk + ' x' + cands[kk]; });

  // ③ SSR/内存态数据源
  out.ssr = {
    renderData: !!document.getElementById('RENDER_DATA'),
    routerData: typeof window._ROUTER_DATA,
    paceF: typeof window.__pace_f,
    scripts: document.scripts.length
  };
  return JSON.stringify(out);
})();
