// 决定"整页 WebView 当 UI"能不能成立的关键实验：
// 推荐页有没有可编程的"下一条"控件（桌面版提示说支持点屏幕上的上下按钮）
// 只读+点一次，不改 App。用法: node tools/cdp-eval.js @tools/probe-recommend-nav-btn.js
(function () {
  function ids() {
    var o = [];
    document.querySelectorAll('[data-e2e-aweme-id]').forEach(function (e) { o.push(e.getAttribute('data-e2e-aweme-id')); });
    return o;
  }
  // 找所有像"上下切换"的候选控件：data-e2e 含 pre/next/up/down，或 aria-label/文本含 下/上
  var cands = [];
  document.querySelectorAll('[data-e2e],[aria-label],button,[role="button"],svg,div,span').forEach(function (el) {
    var e2e = el.getAttribute('data-e2e') || '';
    var al = el.getAttribute('aria-label') || '';
    var t = (el.textContent || '').replace(/\s+/g, '').slice(0, 6);
    var hit = /next|down|up|prev|slide|switch/i.test(e2e) || /[上下]/.test(al) || /^[上下]$/.test(t);
    if (!hit) return;
    var r = el.getBoundingClientRect();
    cands.push({ e2e: e2e, al: al, t: t, tag: el.tagName, x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) });
  });
  var before = ids();
  // 挑一个尺寸合理、在右半屏的候选真点一次
  var pick = cands.filter(function (c) { return c.w > 8 && c.h > 8 && c.x > 400; })[0]
    || cands.filter(function (c) { return c.w > 8 && c.h > 8; })[0];
  var clicked = null;
  if (pick) {
    var el = null;
    document.querySelectorAll('[data-e2e],[aria-label],button,[role="button"],div,span').forEach(function (e) {
      if (el) return;
      var r = e.getBoundingClientRect();
      if ((e.getAttribute('data-e2e') || '') === pick.e2e && (e.getAttribute('aria-label') || '') === pick.al
        && Math.round(r.x) === pick.x && Math.round(r.y) === pick.y) el = e;
    });
    if (el) {
      ['mousedown', 'mouseup', 'click'].forEach(function (t) { el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true })); });
      clicked = pick;
    }
  }
  return new Promise(function (res) {
    setTimeout(function () {
      var after = ids();
      res(JSON.stringify({
        before: before, after: after, advanced: JSON.stringify(before) !== JSON.stringify(after),
        clicked: clicked, candidateCount: cands.length,
        candidates: cands.slice(0, 14)
      }, null, 1));
    }, 3500);
  });
})();
