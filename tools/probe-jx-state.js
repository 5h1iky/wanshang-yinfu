// 只读：判断精选页在本 WebView 里为何不渲染列表（报错/登录门/加载中/结构缺失）
// 用法: node tools/cdp-eval.js @tools/probe-jx-state.js
(function () {
  var t = (document.body && document.body.innerText) || '';
  function has(s) { return t.indexOf(s) >= 0; }
  var roots = document.querySelectorAll('#root, #app, [id*="root"]');
  var rootInfo = [];
  for (var i = 0; i < roots.length; i++) {
    rootInfo.push({ id: roots[i].id, kids: roots[i].children.length, textLen: (roots[i].textContent || '').trim().length });
  }
  return JSON.stringify({
    url: location.href.slice(0, 60),
    title: (document.title || '').slice(0, 30),
    bodyTextLen: t.length,
    divCount: document.querySelectorAll('div').length,
    imgCount: document.querySelectorAll('img').length,
    videoCount: document.querySelectorAll('video').length,
    anchors: document.querySelectorAll('a').length,
    flags: {
      加载中: has('加载中'), 出错了: has('出错了') || has('加载失败'), 刷新: has('刷新'),
      登录: has('登录'), 推荐: has('推荐'), 请先: has('请先')
    },
    roots: rootInfo,
    // 页面里是否有 SPA 报错堆栈痕迹
    errHint: (t.match(/(error|undefined|Cannot read)/i) || [''])[0]
  });
})();
