// 推荐页能不能"边刷边取"：记录当前 id → 触发下一次（滚轮/方向键）→ 再读 id 与带 sign 的可播地址
// 用法: node tools/cdp-eval.js @tools/probe-recommend-next.js
(function () {
  function harvest() {
    var ids = [].slice.call(document.querySelectorAll('[data-e2e-aweme-id]'))
      .map(function (e) { return e.getAttribute('data-e2e-aweme-id') + '|' + (e.getAttribute('data-e2e') || ''); });
    var signed = [];
    document.querySelectorAll('video source, video').forEach(function (v) {
      var s = v.src || v.currentSrc || '';
      if (/www\.douyin\.com\/aweme\/v1\/play\/.*[?&]sign=/.test(s)) {
        signed.push({ len: s.length, src: s });
      }
    });
    return { ids: ids, signedCount: signed.length, signed: signed.slice(0, 2) };
  }
  var before = harvest();
  // 触发切换：优先键盘 Down（页面提示说支持），失败再滚轮
  ['keydown', 'keypress', 'keyup'].forEach(function (t) {
    document.dispatchEvent(new KeyboardEvent(t, { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
  });
  try { window.dispatchEvent(new WheelEvent('wheel', { deltaY: 900, bubbles: true })); } catch (e) {}
  return new Promise(function (res) {
    setTimeout(function () {
      var after = harvest();
      res(JSON.stringify({
        before: { ids: before.ids, signedCount: before.signedCount },
        after: { ids: after.ids, signedCount: after.signedCount },
        changed: JSON.stringify(before.ids) !== JSON.stringify(after.ids),
        sampleSigned: after.signed[0] ? after.signed[0].src.slice(0, 520) : null
      }, null, 1));
    }, 4000);
  });
})();
