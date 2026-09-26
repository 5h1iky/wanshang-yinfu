// 重载后读服务端渲染的初始已赞态（判定 Fw5w6T_O 是"持久已赞"还是"临时动画类"）
// 用法: node tools/cdp-eval.js @tools/probe-like-after-reload.js
(function () {
  var id = '7673428035504196915';
  if (location.href.indexOf('/video/' + id) < 0) {
    location.href = 'https://www.douyin.com/video/' + id;
    return 'NAVIGATING';
  }
  return new Promise(function (res) {
    setTimeout(function () {
      var b = document.querySelector('[data-e2e="video-player-digg"]');
      var cb = document.querySelector('[data-e2e="video-player-collect"]');
      res(JSON.stringify({
        reloaded: true,
        diggCls: b ? String(b.className) : '(none)',
        diggLiked: b ? String(b.className).indexOf('Fw5w6T_O') >= 0 : null,
        collectCls: cb ? String(cb.className) : '(none)',
        url: location.href.slice(0, 60)
      }));
    }, 9000);
  });
})();
