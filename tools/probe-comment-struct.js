// 只读：摸清评论行的真实 DOM 结构与 right-ct 三个钮的可区分特征
// 目的：①修评论列表抓取（自己发的评论只显示名字不显示文本）②修发送钮识别（别再点到 @ 提及钮）
// 用法: node tools/cdp-eval.js @tools/probe-comment-struct.js
(function () {
  var out = { comments: [], sendBar: null, url: location.href.slice(0, 60) };

  // ① 评论行：取 comment-item-info-wrap 的"行根"（往上找含 comment-item 且不含 info-wrap 的祖先）
  function rowRoot(el) {
    var p = el;
    for (var i = 0; i < 8 && p; i++) {
      var c = p.className.toString();
      if (/comment-item/.test(c) && !/info-wrap/.test(c) && !/stats-container/.test(c)) {
        // 只要根（它的子树里含 info-wrap）
        if (p.querySelector('[class*="comment-item-info-wrap"]')) return p;
      }
      p = p.parentElement;
    }
    return null;
  }
  var wraps = document.querySelectorAll('[class*="comment-item-info-wrap"]');
  var seen = [], roots = [];
  for (var i = 0; i < wraps.length; i++) {
    var r = rowRoot(wraps[i]) || wraps[i].parentElement;
    if (r && seen.indexOf(r) < 0) { seen.push(r); roots.push(r); }
    if (roots.length >= 4) break;
  }
  function brief(el, depth) {
    var o = {
      tag: el.tagName,
      cls: (el.className ? el.className.toString() : '').replace(/(comment-item[\w-]*|comment[\w-]*)/g, '$1').slice(0, 80),
      kids: el.children.length,
      tLen: (el.textContent || '').trim().length,
      tHead: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 8)
    };
    if (depth < 2 && el.children.length && el.children.length <= 6) {
      o.ch = [];
      for (var k = 0; k < el.children.length; k++) o.ch.push(brief(el.children[k], depth + 1));
    }
    return o;
  }
  for (var j = 0; j < roots.length; j++) out.comments.push(brief(roots[j], 0));

  // ② 输入区三个钮的可区分特征（不点击，只读结构）
  var spans = document.querySelectorAll('[class*="commentInput-right-ct"] span');
  var bar = [];
  for (var s = 0; s < spans.length; s++) {
    var el = spans[s];
    var svg = el.querySelector('svg');
    bar.push({
      idx: s,
      cls: el.className.toString().slice(0, 50),
      svgCls: svg ? (svg.getAttribute('class') || '') : '',
      svgD: svg && svg.querySelector('path') ? (svg.querySelector('path').getAttribute('d') || '').slice(0, 30) : '',
      aria: el.getAttribute('aria-label') || '',
      title: el.getAttribute('title') || '',
      dataE2e: el.getAttribute('data-e2e') || '',
      opacity: getComputedStyle(el).opacity,
      cursor: getComputedStyle(el).cursor
    });
  }
  out.sendBar = bar;
  return JSON.stringify(out);
})();
