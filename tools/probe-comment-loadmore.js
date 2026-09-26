// 验证：滚 route-scroll-container 能不能把评论加载更多出来（增量分页的前提）
// 用法: node tools/cdp-eval.js @tools/probe-comment-loadmore.js
(function () {
  function snap() {
    var wraps = document.querySelectorAll('[class*="comment-item-info-wrap"]');
    var keys = [];
    wraps.forEach(function (w) {
      var col = w.parentElement;
      var t = col ? (col.textContent || '').replace(/\s+/g, ' ').trim() : '';
      keys.push(t.slice(0, 60));
    });
    return { n: wraps.length, keys: keys };
  }
  var sc = document.querySelector('[class*="route-scroll-container"]') || document.scrollingElement;
  if (!sc) return 'NO SCROLLER';
  var before = snap();
  var steps = [];
  function scrollMore() {
    sc.scrollTop = sc.scrollTop + sc.clientHeight * 0.9;
  }
  return new Promise(function (res) {
    var i = 0;
    (function round() {
      if (i++ >= 6) {
        var after = snap();
        var setB = {};
        before.keys.forEach(function (k) { setB[k] = 1; });
        var fresh = after.keys.filter(function (k) { return !setB[k]; }).length;
        res(JSON.stringify({
          before: before.n, after: after.n, brandNew: fresh,
          scrollTop: Math.round(sc.scrollTop), scrollH: sc.scrollHeight, clientH: sc.clientHeight,
          grew: after.n > before.n
        }, null, 1));
        return;
      }
      scrollMore();
      setTimeout(round, 2200);
    })();
  });
})();
