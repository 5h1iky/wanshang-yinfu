// 枚举当前视频页所有 digg/collect 节点（判断"点第一个"是否点到了没绑处理器的副本）
// 只读，不点击。用法: node tools/cdp-eval.js @tools/probe-digg-nodes.js
(function () {
  function desc(el) {
    var r = el.getBoundingClientRect();
    var p = el, hiddenAt = null, d = 0;
    while (p && d++ < 8) {
      if (getComputedStyle(p).display === 'none') { hiddenAt = p.className.toString().slice(0, 40); break; }
      p = p.parentElement;
    }
    // 有没有挂 React 事件属性（有 onClick 的节点才是真按钮）
    var hasProps = false, handlers = [];
    Object.keys(el).forEach(function (k) {
      if (k.indexOf('__reactProps$') === 0) {
        hasProps = true;
        var pr = el[k];
        if (pr) handlers = Object.keys(pr).filter(function (h) { return /^on[A-Z]/.test(h); });
      }
    });
    return {
      cls: String(el.className).slice(0, 60),
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 10),
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      selfDisplay: getComputedStyle(el).display,
      hiddenAnc: hiddenAt, handlers: handlers
    };
  }
  function all(sel) {
    var a = document.querySelectorAll(sel), r = [];
    for (var i = 0; i < a.length; i++) r.push(desc(a[i]));
    return r;
  }
  return JSON.stringify({
    url: location.href.slice(0, 62),
    diggCount: document.querySelectorAll('[data-e2e="video-player-digg"]').length,
    digg: all('[data-e2e="video-player-digg"]'),
    collect: all('[data-e2e="video-player-collect"]'),
    e2eLike: all('[data-e2e="like"], [data-e2e="feed-like-icon"]').length
  });
})();
