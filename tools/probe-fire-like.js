// 直接驱动桥的点赞动作（走 v4 新判据），配合 App 侧日志验证「状态已翻转」
// 当前状态读取 + 点击：见 tools/probe-fire-collect.js 的同型用法
// 用法: node tools/cdp-eval.js @tools/probe-fire-like.js
(function () {
  var id = '7673428035504196915';
  var onVideo = location.href.indexOf('/video/' + id) >= 0;
  if (!onVideo) {
    ChatBridge.likeVideo(id, true);
    return 'FIRED like(true) from ' + location.href.slice(0, 40);
  }
  var b = document.querySelector('[data-e2e="video-player-digg"]');
  var before = b ? String(b.className) : 'none';
  ChatBridge.likeVideo(id, false);
  return 'ON-VIDEO, like(false) fired; classBefore=' + before.slice(0, 60);
})();
