(function(){
  function fire(el){
    ['mousedown','mouseup','click'].forEach(function(type){
      el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window}));
    });
  }
  var ph=document.querySelector('[class*="comment-input-inner-container"]');
  if(!ph) return 'ERR 无输入区';
  fire(ph);
  return new Promise(function(res){
    setTimeout(function(){
      var out=[];
      function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
      // 点击后新出现的编辑器
      var eds=document.querySelectorAll('textarea,[contenteditable="true"]');
      out.push('编辑器数='+eds.length);
      for(var i=0;i<Math.min(eds.length,4);i++){
        var e=eds[i];
        out.push('  '+e.tagName+'.'+cls(e)+' ce='+e.getAttribute('contenteditable')+' ph='+(e.getAttribute('data-placeholder')||e.getAttribute('placeholder')||''));
      }
      // 发送按钮（文字或语义类）
      var all=document.querySelectorAll('button,div,span');
      var n=0,seen={};
      for(var i=0;i<all.length&&n<10;i++){
        var e=all[i],t=(e.textContent||'').trim();
        var isSend=(t==='发送'||t==='发布'||/send|submit/i.test(e.className||''));
        if(!isSend)continue;
        var k=e.tagName+'.'+cls(e)+'|'+t;
        if(seen[k])continue;seen[k]=1;n++;
        out.push('  发送候选 '+k);
      }
      res(out.join('\n'));
    }, 1500);
  });
})()
