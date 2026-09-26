// 点推荐页的「下一条」按钮，看能不能真的翻页（决定"整页 WebView 当 UI"是否可行）
(function () {
  function ids() {
    var o = [];
    document.querySelectorAll('[data-e2e-aweme-id]').forEach(function (e) {
      o.push(e.getAttribute('data-e2e-aweme-id'));
    });
    return o;
  }
  var el = document.querySelector('[data-e2e="video-switch-next-arrow"]');
  if (!el) return 'NO NEXT BTN';
  var b = ids();
  var r = el.getBoundingClientRect();
  var cx = Math.round(r.x + r.width / 2), cy = Math.round(r.y + r.height / 2);
  ['pointerdown', 'pointerup', 'mousedown', 'mouseup', 'click'].forEach(function (t) {
    var E = t.indexOf('pointer') === 0 ? PointerEvent : MouseEvent;
    el.dispatchEvent(new E(t, { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
  });
  return new Promise(function (res) {
    setTimeout(function () {
      var a = ids();
      var v = [].slice.call(document.querySelectorAll('video')).map(function (x) {
        return { ready: x.readyState, w: x.videoWidth, t: Math.round(x.currentTime), src: (x.currentSrc || x.src || '').slice(0, 60) };
      });
      res(JSON.stringify({
        btnAt: [cx, cy], before: b, after: a,
        advanced: JSON.stringify(b) !== JSON.stringify(a), videos: v
      }, null, 1));
    }, 4500);
  });
})();
