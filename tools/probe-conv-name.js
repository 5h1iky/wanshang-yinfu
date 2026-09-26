// 会话行为什么会先显示纯数字 uid：查昵称到底在 DOM 哪个位置、数字从哪来
// 用法: node tools/cdp-eval.js @tools/probe-conv-name.js
(function () {
  var rows = document.querySelectorAll('[class~="conversationConversationItemwrapper"]');
  var out = [];
  for (var i = 0; i < Math.min(rows.length, 6); i++) {
    var r = rows[i];
    var title = r.querySelector('[class~="conversationConversationItemtitle"]');
    var info = {};
    // 标题元素自身的属性与它的子节点，看数字是不是被一起 textContent 捞进来了
    if (title) {
      info.titleText = (title.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 40);
      info.titleAttrs = [].slice.call(title.attributes).map(function (a) { return a.name + '=' + String(a.value).slice(0, 40); });
      info.childTags = [].slice.call(title.children).map(function (c) {
        return c.tagName + '[' + (c.className || '').toString().slice(0, 30) + ']' + JSON.stringify((c.textContent || '').trim().slice(0, 24));
      });
    }
    // 整行里所有含 15+ 位数字的元素（定位 uid 从哪冒出来）
    var digitEls = [];
    r.querySelectorAll('*').forEach(function (e) {
      if (e.children.length) return;
      var t = (e.textContent || '').trim();
      if (/^\d{10,}$/.test(t)) digitEls.push(e.tagName + '.' + String(e.className).slice(0, 26) + '="' + t + '"');
    });
    // 有没有 alt / aria / data-* 带着真昵称
    var nameish = [];
    r.querySelectorAll('img[alt], [aria-label], [data-name], [data-nickname], [title]').forEach(function (e) {
      var v = e.getAttribute('alt') || e.getAttribute('aria-label') || e.getAttribute('data-name')
        || e.getAttribute('data-nickname') || e.getAttribute('title') || '';
      if (v.trim()) nameish.push(e.tagName + ':' + v.trim().slice(0, 24));
    });
    out.push({ i: i, title: info, digitEls: digitEls.slice(0, 6), nameish: nameish.slice(0, 6) });
  }
  return JSON.stringify({ href: location.href, rowCount: rows.length, rows: out }, null, 1);
})();
