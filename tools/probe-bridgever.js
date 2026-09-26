// 桥版本与关键能力自检（装机后验证 v4 是否真注入）
// 用法: node tools/cdp-eval.js @tools/probe-bridgever.js
JSON.stringify({
  url: location.href.slice(0, 50),
  v4: !!(window.ChatBridge && ChatBridge.__v4),
  hasEnsureImHome: !!(window.ChatBridge && typeof ChatBridge.ensureImHome === 'function'),
  hasClickAndJudge: !!(window.ChatBridge && typeof ChatBridge.__clickAndJudge === 'function'),
  hasTrySubmit: !!(window.ChatBridge && typeof ChatBridge.__trySubmit === 'function'),
  convRows: document.querySelectorAll('[class~="conversationConversationItemwrapper"]').length
});
