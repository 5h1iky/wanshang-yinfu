// ①清点 digg/collect 节点：几个、谁可见、哪个带"已赞"类 ②把误点的赞取消（点到达未赞态）
// ③注入评论文本但不提交，看提交钮是否随内容出现（纯本地，不发评论）
// 用法: node tools/cdp-eval.js @tools/probe-like-state.js
(function () {
  var out = {};
  function info(el) {
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    var p = el.parentElement, hiddenAnc = null, depth = 0;
    while (p && depth++ < 6) {
      if (getComputedStyle(p).display === 'none') { hiddenAnc = p.className.toString().slice(0, 50); break; }
      p = p.parentElement;
    }
    return {
      cls: el.className.toString().slice(0, 80),
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 14),
      rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
      disp: cs.display, hiddenAnc: hiddenAnc
    };
  }
  function fireClick(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (t) {
      el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
    });
  }
  function list(sel) {
    var a = document.querySelectorAll(sel), r = [];
    for (var i = 0; i < a.length; i++) r.push(info(a[i]));
    return r;
  }
  function likedSet() {
    // "已赞"特征：digg 节点 class 比未赞时多一段（对比同类兄弟节点）
    return document.querySelectorAll('[data-e2e="video-player-digg"]').length;
  }

  out.diggNodes = list('[data-e2e="video-player-digg"]');
  out.collectNodes = list('[data-e2e="video-player-collect"]');
  out.shareNodes = list('[data-e2e="video-player-share"]').length;
  out.likeCountNodes = list('[data-e2e="video-player-digg"] [class*="count"], [data-e2e="video-player-digg"] span');

  // ② 取消点赞：最多试 3 次，直到 class 回到未赞形态（无 Fw5w6T_O）
  function digg0() { return document.querySelector('[data-e2e="video-player-digg"]'); }
  var steps = [];
  function tryUnlike(n) {
    var el = digg0();
    if (!el) return Promise.resolve('no-el');
    var has = el.className.toString().indexOf('Fw5w6T_O') >= 0;
    steps.push({ try: n, liked: has, cls: el.className.toString().slice(0, 80) });
    if (!has) return Promise.resolve('CLEAN');
    fireClick(el);
    return new Promise(function (res) {
      setTimeout(function () { res(tryUnlike(n + 1)); }, 1300);
    });
  }

  return (function loop(n) {
    return Promise.resolve(tryUnlike(n));
  })(1).then(function (finalState) {
    out.unlike = { steps: steps, result: finalState, diggCount: likedSet() };

    // ③ 注入文本（不提交）
    var box = document.querySelector('[class*="comment-input-inner-container"]');
    if (box) fireClick(box);
    return new Promise(function (resolve) {
      setTimeout(function () {
        var ed = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
        out.inject = { editor: !!ed };
        if (!ed) { resolve(JSON.stringify(out)); return; }
        ed.focus();
        var dt = new DataTransfer();
        dt.setData('text/plain', '测试');
        ed.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
        setTimeout(function () {
          out.inject.afterPasteText = (ed.textContent || '').slice(0, 20);
          if (out.inject.afterPasteText.indexOf('测试') < 0) {
            try { document.execCommand('insertText', false, '测试'); } catch (e) {}
            out.inject.execCommandTried = true;
          }
          setTimeout(function () {
            out.inject.textContent = (ed.textContent || '').replace(/\u200b/g, '').slice(0, 20);
            out.inject.dataText = (ed.querySelector('[data-text="true"]') || {}).textContent || '';
            // 有内容后再枚举提交控件（找"发送"）
            var cands = [];
            var all = document.querySelectorAll('div,span,button');
            for (var i = 0; i < all.length && cands.length < 12; i++) {
              var el = all[i];
              var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
              var c = el.className.toString();
              if ((t === '发送' || /send|submit/i.test(c)) && el.children.length <= 2) {
                var r = el.getBoundingClientRect();
                cands.push({ tag: el.tagName, cls: c.slice(0, 60), text: t.slice(0, 6), rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)], disp: getComputedStyle(el).display });
              }
            }
            out.inject.submitAfterText = cands;
            // 清空编辑器（避免残留），不提交
            ed.textContent = '';
            resolve(JSON.stringify(out));
          }, 700);
        }, 700);
      }, 1500);
    });
  });
})();
