// 找出"正文抓空"的评论是什么形态：复刻桥的抽取规则，把判为空的那几行的完整列结构打出来
// 用法: 先导航到视频页，再 node cdp-eval.js @tools/probe-comment-empty.js
(function () {
  function txt(el) { return (el.textContent || '').replace(/\s+/g, ' ').trim(); }
  var wraps = document.querySelectorAll('[class*="comment-item-info-wrap"]');
  var out = [], bad = [];
  for (var i = 0; i < wraps.length; i++) {
    var wrap = wraps[i];
    var col = wrap.parentElement;
    if (!col) continue;
    var name = txt(wrap).replace(/\.{2,}$/, '');
    var text = '', time = '', likes = '';
    var cols = [];
    for (var c = 0; c < col.children.length; c++) {
      var el = col.children[c];
      var t = txt(el);
      var desc = el.tagName + '[' + String(el.className).slice(0, 34) + '] len=' + t.length + ' ' + JSON.stringify(t.slice(0, 40));
      cols.push(desc);
      if (el === wrap || wrap.contains(el) || el.contains(wrap)) continue;
      if (el.querySelector('[class*="comment-item-info-wrap"]')) continue;
      if (el.querySelector('[class*="comment-item-stats-container"]')) {
        var sp = el.querySelector('p span');
        likes = sp ? txt(sp) : '';
        continue;
      }
      var mt = t.match(/^(刚刚|\d+\s?(秒|分钟|小时|天|周|月|年)前|\d{1,2}-\d{1,2})/);
      if (mt) { time = mt[1]; continue; }
      if (t.length > text.length) text = t;
    }
    var rec = { i: i, name: name.slice(0, 16), time: time, likes: likes, text: text.slice(0, 30), cols: cols };
    out.push(rec);
    if (!text) bad.push(rec);
  }
  return JSON.stringify({
    href: location.href, total: out.length, emptyText: bad.length,
    badSamples: bad.slice(0, 4),
    okSample: out.filter(function (r) { return r.text; }).slice(0, 1)
  }, null, 1);
})();
