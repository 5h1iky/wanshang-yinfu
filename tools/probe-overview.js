(function(){
  var out=[];
  out.push('URL='+location.href);
  out.push('title='+(document.title||'(空)'));
  out.push('div数='+document.querySelectorAll('div').length);
  var t=(document.body.innerText||'').replace(/\s+/g,' ').trim();
  out.push('文本前200: '+t.slice(0,200));
  // 登录墙特征
  out.push('含登录字样='+ (t.indexOf('登录')>=0) + ' 含扫码='+ (t.indexOf('扫码')>=0) + ' 含用户名(明日awo)='+ (t.indexOf('明日awo')>=0));
  out.push('img数='+document.querySelectorAll('img').length+' canvas数='+document.querySelectorAll('canvas').length);
  // 会话列表候选：左侧栏结构
  out.push('body子元素数='+(document.body?document.body.children.length:0));
  return out.join('\n');
})()
