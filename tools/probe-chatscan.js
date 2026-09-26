// 归位验证：从视频页导航回 /chat 后，会话列表/输入框是否恢复（阶段3「引擎归位」修法的前提）
// 用法: node tools/cdp-eval.js @tools/probe-chatscan.js
(function () {
  var rows = document.querySelectorAll('[class~="conversationConversationItemwrapper"]');
  var names = [];
  for (var i = 0; i < rows.length && i < 6; i++) {
    var t = rows[i].querySelector('[class~="conversationConversationItemtitle"]');
    var d = rows[i].querySelector('[class*="ConversationItemDescwrapper"]');
    names.push((t ? t.textContent.trim() : '?') + '|' + (d ? d.textContent.trim().slice(0, 14) : ''));
  }
  return JSON.stringify({
    url: location.href.slice(0, 50),
    loginWall: ((document.body && document.body.innerText) || '').indexOf('扫码登录') >= 0,
    convRows: rows.length,
    names: names,
    editor: !!document.querySelector('[class~="messageEditorinputArea"][contenteditable="true"]'),
    msgBoxes: document.querySelectorAll('[class*="messageMessageBoxmessageBox"]').length
  });
})();
