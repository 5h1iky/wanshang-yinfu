(function(){
  function fire(el){
    ['mousedown','mouseup','click'].forEach(function(type){
      el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window}));
    });
  }
  var rows=document.querySelectorAll('[class*="conversationConversationItem"]');
  var target=null,idx=-1;
  for(var i=0;i<rows.length;i++){
    var t=rows[i].querySelector('[class*="conversationConversationItemtitle"]');
    if(t&&t.textContent.trim().indexOf('5h1iky')>=0){target=rows[i];idx=i;break;}
  }
  if(!target&&rows.length){target=rows[0];idx=0;}
  if(!target) return '无会话行可点';
  var title=target.querySelector('[class*="conversationConversationItemtitle"]')||target;
  fire(title);
  return new Promise(function(res){
    setTimeout(function(){
      var out=[];
      function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
      out.push('点击行#'+idx+' 后 URL='+location.href.slice(0,70));
      out.push('== 消息元素候选');
      var bub=document.querySelectorAll('[class*="message" i],[class*="Message"],[class*="bubble" i],[class*="msg" i]');
      var seen={},n=0;
      for(var i=0;i<bub.length&&n<12;i++){
        var e=bub[i],k=e.tagName+'.'+cls(e);
        if(seen[k])continue;seen[k]=1;n++;
        out.push('  '+k+' 「'+(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,16)+'」');
      }
      out.push('== 编辑器候选');
      var eds=document.querySelectorAll('[contenteditable="true"],textarea,[data-contents],[data-editor]');
      out.push('editable数='+eds.length);
      for(var i=0;i<Math.min(eds.length,5);i++){
        var e=eds[i];
        out.push('  '+e.tagName+'.'+cls(e)+' ce='+e.getAttribute('contenteditable')+' dc='+e.getAttribute('data-contents'));
      }
      out.push('== 发送钮候选');
      var btns=document.querySelectorAll('button,[role="button"],[class*="send" i]');
      var s2={},m=0;
      for(var i=0;i<btns.length&&m<12;i++){
        var b=btns[i],k=b.tagName+'.'+cls(b);
        if(s2[k])continue;s2[k]=1;m++;
        out.push('  '+k+' 「'+(b.textContent||'').replace(/\s+/g,' ').trim().slice(0,10)+'」');
      }
      // 类名快照（找新出现的 thread/message 类）
      var all=document.querySelectorAll('*'),names={};
      for(var i=0;i<all.length;i++){
        var c=all[i].className;
        if(typeof c!=='string')continue;
        c.split(/\s+/).forEach(function(tok){
          if(/thread|message|editor|input|send|chat/i.test(tok)&&!names[tok])names[tok]=1;
        });
      }
      out.push('== 关键类名: '+Object.keys(names).join(' '));
      res(out.join('\n'));
    }, 5000);
  });
})()
