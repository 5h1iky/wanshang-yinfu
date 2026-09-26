// 真发一条自检评论（验证尾 @ 已消除 + 自己的评论正文不再被昵称覆盖）
// 目标视频=用户自己刚测试过的那条；文本很短、可随手删除
// 用法: node tools/cdp-eval.js @tools/probe-fire-comment.js
(function () {
  var id = '7658893735081676042';
  if (location.pathname.indexOf('/chat') === 0) {
    ChatBridge.sendComment(id, '自检A');
    return 'FIRED from /chat（引擎会先跳视频页自续）';
  }
  if (location.href.indexOf('/video/' + id) < 0) return 'WRONG PAGE ' + location.href.slice(0, 60);
  ChatBridge.sendComment(id, '自检A');
  return 'FIRED on video page';
})();
