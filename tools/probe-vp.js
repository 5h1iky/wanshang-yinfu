// 读引擎真实视口与页面可见性（验证「挂载获得真视口」是否生效）
// 用法: node tools/cdp-eval.js @tools/probe-vp.js
JSON.stringify({
  url: location.href.slice(0, 46),
  innerW: window.innerWidth, innerH: window.innerHeight,
  clientW: document.documentElement.clientWidth, clientH: document.documentElement.clientHeight,
  vis: document.visibilityState, hidden: document.hidden,
  convRows: document.querySelectorAll('[class~="conversationConversationItemwrapper"]').length
});
