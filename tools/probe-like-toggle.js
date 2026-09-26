// 单假设实验：合成点击（chat_bridge.fireClick 同款事件三连）能否真实改变点赞状态
// 附带采集「评论侧栏关闭态/开启态」下输入框的 rect（纯只读，不提交任何评论）
// 判定标准：计数文本或按钮配色发生变化 = 生效；结束前自动回滚到原状态
// 用法: node tools/cdp-eval.js @tools/probe-like-toggle.js 'video'
(function () {
  var out = { env: {}, like: {}, comment: {} };

  function snap(el) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    var svg = el.querySelector('svg');
    var path = el.querySelector('path');
    return {
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 20),
      cls: (el.className && el.className.baseVal !== undefined ? el.className.baseVal : String(el.className || '')).slice(0, 90),
      color: cs.color,
      fill: svg ? (svg.getAttribute('fill') || '') : '',
      pathFill: path ? (path.getAttribute('fill') || getComputedStyle(path).fill || '') : '',
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      inViewport: r.top >= 0 && r.bottom <= innerHeight && r.left >= 0 && r.right <= innerWidth && r.width > 0,
      aria: el.getAttribute('aria-label') || ''
    };
  }

  function fireClick(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (t) {
      el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
    });
  }

  function wait(ms) { return new Promise(function (res) { setTimeout(res, ms); }); }

  out.env = {
    url: location.href.slice(0, 70),
    title: (document.title || '').slice(0, 40),
    hidden: document.hidden,
    vis: document.visibilityState,
    focus: document.hasFocus(),
    vw: innerWidth, vh: innerHeight, dpr: devicePixelRatio,
    scrollY: Math.round(scrollY)
  };

  var digg = document.querySelector('[data-e2e="video-player-digg"]');
  var collect = document.querySelector('[data-e2e="video-player-collect"]');
  var cmtIcon = document.querySelector('[data-e2e="feed-comment-icon"]');
  var editor = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
  var innerBox = document.querySelector('[class*="comment-input-inner-container"]');

  out.like.before = snap(digg);
  out.collectBefore = snap(collect);
  out.cmtIconBefore = snap(cmtIcon);
  out.editorClosed = snap(editor);
  out.innerBoxClosed = snap(innerBox);

  if (!digg) {
    out.like.verdict = 'NO_DIGG_BUTTON';
    return JSON.stringify(out);
  }

  fireClick(digg);
  return new Promise(function (resolve) {
    // 点击后等页面回报，再取快照
    wait(1300).then(function () {
      out.like.afterClick1 = snap(document.querySelector('[data-e2e="video-player-digg"]'));
      var a = out.like.before, b = out.like.afterClick1;
      var changed = !a || !b ? null : (a.text !== b.text || a.color !== b.color || a.pathFill !== b.pathFill || a.cls !== b.cls);
      out.like.changedBySyntheticClick = changed;

      // 生效则回滚（再点一次取消），保持账号原状
      var again = document.querySelector('[data-e2e="video-player-digg"]');
      if (changed && again) fireClick(again);
      return wait(1000);
    }).then(function () {
      out.like.afterRollback = snap(document.querySelector('[data-e2e="video-player-digg"]'));

      // ---- 评论侧栏：关闭态 rect 已有，现点开侧栏再看输入框位置（纯 UI 动作）----
      var icon = document.querySelector('[data-e2e="feed-comment-icon"]');
      if (icon) fireClick(icon);
      return wait(1800);
    }).then(function () {
      out.editorOpen = snap(document.querySelector('.public-DraftEditor-content[contenteditable="true"]'));
      out.innerBoxOpen = snap(document.querySelector('[class*="comment-input-inner-container"]'));
      out.cmtCountNodes = document.querySelectorAll('[class*="comment-item-info-wrap"]').length;
      out.like.verdict = 'DONE';
      resolve(JSON.stringify(out));
    });
  });
})();
