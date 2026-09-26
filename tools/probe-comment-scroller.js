// 找评论区的真实滚动容器 + 摸清评论项的可比标识（为增量分页做准备）
// 用法: node tools/cdp-eval.js @tools/probe-comment-scroller.js
(function () {
  function box(el) {
    var r = el.getBoundingClientRect();
    return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)];
  }
  var scrollers = [];
  document.querySelectorAll('*').forEach(function (el) {
    var oh = el.scrollHeight, ch = el.clientHeight;
    if (ch <= 40 || oh <= ch + 40) return;
    var st = getComputedStyle(el);
    if (!/(auto|scroll)/.test(st.overflowY)) return;
    scrollers.push({
      cls: String(el.className).slice(0, 60),
      tag: el.tagName,
      box: box(el),
      scrollH: oh, clientH: ch, scrollTop: Math.round(el.scrollTop),
      commentItemsInside: el.querySelectorAll('[class*="comment-item-info-wrap"]').length
    });
  });
  // 评论项上有没有稳定的 data-* 可用于去重
  var first = document.querySelector('[class*="comment-item"]');
  var attrs = first ? [].slice.call(first.attributes).map(function (a) { return a.name + '=' + String(a.value).slice(0, 30); }) : [];
  var all = document.querySelectorAll('[class*="comment-item-info-wrap"]').length;
  return JSON.stringify({
    href: location.href,
    commentCount: all,
    scrollers: scrollers.slice(0, 8),
    sampleItemAttrs: attrs.slice(0, 12)
  }, null, 1);
})();
