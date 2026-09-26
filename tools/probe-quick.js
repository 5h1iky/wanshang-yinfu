(function(){
  var b=document.querySelectorAll('[class~="messageMessageBoxmessageBox"]').length;
  var ed=!!document.querySelector('[class~="messageEditorinputArea"]');
  var rows=document.querySelectorAll('[class~="conversationConversationItemwrapper"]').length;
  var wall=(document.body.innerText||'').indexOf('扫码登录')>=0;
  return 'boxes='+b+' editor='+ed+' 会话行='+rows+' 登录墙='+wall+' bridgeV3='+(window.ChatBridge&&window.ChatBridge.__v3);
})()
