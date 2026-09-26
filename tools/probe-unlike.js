// 撤销实验留下的赞：读当前已赞态 → 点一下 → 复读；只操作 App 测试用过的这条视频
// 用法: node tools/cdp-eval.js @tools/probe-unlike.js
(function () {
  var id = '7689450903249636648';
  if (location.href.indexOf('/video/' + id) < 0) return 'WRONG PAGE: ' + location.href.slice(0, 60);
  var b = document.querySelector('[data-e2e="video-player-digg"]');
  if (!b) return 'NO BUTTON';
  var before = String(b.className);
  var liked = before.split(/\s+/).length >= 4;
  if (!liked) return 'ALREADY UNLIKED: ' + before;
  ['mousedown', 'mouseup', 'click'].forEach(function (t) {
    b.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
  });
  return new Promise(function (res) {
    setTimeout(function () {
      var now = String(document.querySelector('[data-e2e="video-player-digg"]').className);
      res(JSON.stringify({ before: before, after: now, tokens: now.split(/\s+/).length }));
    }, 3000);
  });
})();
