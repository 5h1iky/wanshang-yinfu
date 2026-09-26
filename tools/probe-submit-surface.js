// 精确定位评论提交面（只读：注入文本 → 摸清提交控件/事件绑定 → 不提交）
// ① commentInput-right-ct 全子树（谁可点、有没有 svg 纸飞机）
// ② 编辑器上的 React 事件绑定（有没有 onKeyDown / onBeforeInput → 回车是否可用）
// ③ 文本含"评论/发送"的近邻按钮
// 用法: node tools/cdp-eval.js @tools/probe-submit-surface.js
(function () {
  var out = {};
  function fireClick(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (t) {
      el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
    });
  }
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)];
  }
  function dump(el, depth) {
    var o = {
      tag: el.tagName,
      cls: el.className.toString ? el.className.toString().slice(0, 70) : String(el.className).slice(0, 70),
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 12),
      rect: rectOf(el),
      disp: getComputedStyle(el).display,
      cursor: getComputedStyle(el).cursor
    };
    var svg = el.tagName === 'svg' ? el : el.querySelector('svg');
    if (svg) { o.svgCls = (svg.getAttribute('class') || '').slice(0, 60); o.svgHtml = svg.outerHTML.slice(0, 90); }
    if (el.children.length && depth < 3) {
      o.kids = [];
      for (var i = 0; i < el.children.length && o.kids.length < 8; i++) o.kids.push(dump(el.children[i], depth + 1));
    }
    return o;
  }

  var box = document.querySelector('[class*="comment-input-inner-container"]');
  if (box) fireClick(box);

  return new Promise(function (resolve) {
    setTimeout(function () {
      var ed = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
      out.editor = !!ed;
      if (!ed) { resolve(JSON.stringify(out)); return; }
      ed.focus();
      var dt = new DataTransfer();
      dt.setData('text/plain', '测试X');
      ed.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));

      setTimeout(function () {
        out.text = (ed.textContent || '').replace(/\u200b/g, '').slice(0, 12);

        // ② React 事件绑定探测
        var rk = Object.keys(ed).filter(function (k) { return /^__react/.test(k); });
        out.reactKeys = rk.slice(0, 4);
        var props = null;
        for (var i = 0; i < rk.length; i++) {
          var v = ed[rk[i]];
          if (v && v.props) { props = v.props; break; }
        }
        out.editorHandlers = props ? Object.keys(props).filter(function (k) { return /^on[A-Z]/.test(k); }) : [];
        // 往上找几层，容器上可能挂 keydown
        var up = ed, chain = [];
        for (var d = 0; up && d < 5; d++) {
          var ks = Object.keys(up).filter(function (k) { return /^__react/.test(k); });
          var pr = null;
          for (var m = 0; m < ks.length; m++) { if (up[ks[m]] && up[ks[m]].props) { pr = up[ks[m]].props; break; } }
          chain.push({ depth: d, tag: up.tagName, cls: up.className.toString().slice(0, 40), handlers: pr ? Object.keys(pr).filter(function (k) { return /^on[A-Z]/.test(k); }) : [] });
          up = up.parentElement;
        }
        out.handlerChain = chain;

        // ① right-ct 全子树
        var right = document.querySelector('[class*="commentInput-right-ct"]');
        out.rightCt = right ? dump(right, 0) : null;
        var wrap = right ? right.parentElement : null;
        out.inputWrap = wrap ? dump(wrap, 0) : null;

        // ③ 文本含 发送/评论 的按钮
        var hits = [];
        var all = document.querySelectorAll('div,span,button,p');
        for (var n = 0; n < all.length && hits.length < 10; n++) {
          var t = (all[n].textContent || '').replace(/\s+/g, '').trim();
          if ((t === '发送' || t === '评论' || t === '发布') && all[n].children.length === 0) {
            hits.push({ tag: all[n].tagName, cls: all[n].className.toString().slice(0, 50), text: t, rect: rectOf(all[n]), disp: getComputedStyle(all[n]).display });
          }
        }
        out.textButtons = hits;
        resolve(JSON.stringify(out));
      }, 900);
    }, 1600);
  });
})();
