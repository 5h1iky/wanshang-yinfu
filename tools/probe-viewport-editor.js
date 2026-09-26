// 第二轮取证（只读，绝不提交评论）：
// ①引擎视口真实状态 ②digg 祖先链谁把尺寸压成 0 ③点输入框后 Draft 编辑器是否挂载
// ④枚举编辑器周边的候选提交控件（只列举不点击）
// 用法: node tools/cdp-eval.js @tools/probe-viewport-editor.js
(function () {
  var out = {};
  function rectOf(el) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), d: cs.display, v: cs.visibility, ov: cs.overflow };
  }
  function cls(el) {
    var c = el.className;
    return (c && c.baseVal !== undefined ? c.baseVal : String(c || '')).slice(0, 70);
  }

  // ① 视口度量
  out.viewport = {
    innerW: innerWidth, innerH: innerHeight,
    clientW: document.documentElement.clientWidth, clientH: document.documentElement.clientHeight,
    bodyW: document.body ? document.body.clientWidth : -1,
    screenW: screen.width, screenH: screen.height, availW: screen.availWidth,
    vvW: window.visualViewport ? Math.round(window.visualViewport.width) : -1,
    vvH: window.visualViewport ? Math.round(window.visualViewport.height) : -1,
    docH: document.documentElement.scrollHeight,
    metaViewport: (document.querySelector('meta[name=viewport]') || {}).content || '(none)'
  };

  // ② digg 祖先链尺寸塌缩点
  var digg = document.querySelector('[data-e2e="video-player-digg"]');
  var chain = [];
  var p = digg;
  for (var i = 0; p && i < 8; i++) {
    chain.push({ tag: p.tagName, cls: cls(p), rect: rectOf(p) });
    p = p.parentElement;
  }
  out.diggChain = chain;

  // ③ 点输入框（placeholder 区）看编辑器是否挂载
  var box = document.querySelector('[class*="comment-input-inner-container"]');
  out.boxBefore = rectOf(box);
  if (box) {
    ['mousedown', 'mouseup', 'click'].forEach(function (t) {
      box.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
    });
    box.click && box.click();
  }
  return new Promise(function (resolve) {
    setTimeout(function () {
      var ed = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
      out.editorMounted = !!ed;
      out.editorRect = rectOf(ed);
      out.editorParentChain = [];
      var q = ed;
      for (var k = 0; q && k < 5; k++) { out.editorParentChain.push({ tag: q.tagName, cls: cls(q), rect: rectOf(q) }); q = q.parentElement; }

      // ④ 枚举候选提交控件（只列举，不点击）
      var cands = [];
      var all = document.querySelectorAll('[class*="commentInput"], [class*="comment-input"], button, [role=button]');
      for (var n = 0; n < all.length && cands.length < 25; n++) {
        var el = all[n];
        var c = cls(el);
        var t = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 8);
        if (/send|submit|right-ct|发送|commentInput|comment-input/i.test(c + t)) {
          cands.push({ tag: el.tagName, cls: c, text: t, rect: rectOf(el), kids: el.children.length, e2e: el.getAttribute('data-e2e') || '' });
        }
      }
      out.submitCandidates = cands;
      out.commentNodes = document.querySelectorAll('[class*="comment-item-info-wrap"]').length;
      resolve(JSON.stringify(out));
    }, 2200);
  });
})();
