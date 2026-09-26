// 只读：找出精选页卡片的真实锚点模式（链接形态 / data-e2e / 重复卡片类名）
// 用法: node tools/cdp-eval.js @tools/probe-jx-cards.js
(function () {
  var out = {};

  // ① 所有 a 的 href 形态（把数字段掩掉，只看结构）
  var shapes = {}, samples = {};
  var as = document.querySelectorAll('a[href]');
  for (var i = 0; i < as.length; i++) {
    var h = as[i].getAttribute('href') || '';
    var shape = h.replace(/\d{6,}/g, '<ID>').replace(/\/[a-z0-9]{20,}/gi, '/<HASH>');
    shapes[shape] = (shapes[shape] || 0) + 1;
    if (!samples[shape]) samples[shape] = h.slice(0, 90);
  }
  out.hrefShapes = Object.keys(shapes).map(function (k) { return k + ' x' + shapes[k]; }).slice(0, 14);
  out.hrefSample = samples;

  // ② data-e2e 语义锚点统计（抖音常用）
  var e2e = {};
  var es = document.querySelectorAll('[data-e2e]');
  for (var j = 0; j < es.length; j++) {
    var v = es[j].getAttribute('data-e2e');
    e2e[v] = (e2e[v] || 0) + 1;
  }
  out.dataE2e = Object.keys(e2e).map(function (k) { return k + ' x' + e2e[k]; }).slice(0, 20);

  // ③ 重复卡片：找"同一 class 出现 >= 5 次且子树含 img"的容器
  var byCls = {};
  var divs = document.querySelectorAll('div');
  for (var k = 0; k < divs.length; k++) {
    var c = divs[k].className.toString().trim();
    if (!c || c.length > 60) continue;
    if (divs[k].querySelector('img')) { (byCls[c] = byCls[c] || []).push(divs[k]); }
  }
  var cards = Object.keys(byCls).filter(function (c) { return byCls[c].length >= 5; })
    .sort(function (a, b) { return byCls[b].length - byCls[a].length; })
    .slice(0, 6)
    .map(function (c) {
      var el = byCls[c][0];
      return { cls: c, count: byCls[c].length, kids: el.children.length, textLen: (el.textContent || '').trim().length, hasAnchor: !!el.querySelector('a') };
    });
  out.cardCandidates = cards;
  out.total = { a: as.length, div: divs.length, e2e: es.length };
  return JSON.stringify(out);
})();
