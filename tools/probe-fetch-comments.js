// 不碰手机界面，直接驱动引擎走「评论页抓取」同一条代码路径
// 用法: node tools/cdp-eval.js @tools/probe-fetch-comments.js
(function () {
  var id = '7658893735081676042';
  ChatBridge.fetchComments(id);
  return 'FIRED fetchComments from ' + location.href.slice(0, 50);
})();
