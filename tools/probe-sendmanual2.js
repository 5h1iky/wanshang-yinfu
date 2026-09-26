(function(){
  var out=[];
  function fire(el){
    ['mousedown','mouseup','click'].forEach(function(t){
      el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));
    });
  }
  // 顶部登录标志（匿名页右上角有"登录"钮）
  var tops=[];
  document.querySelectorAll('button,div,span,a').forEach(function(e){
    if((e.textContent||'').trim()==='登录'){
      var r=e.getBoundingClientRect();
      if(r.top<220&&r.width>0)tops.push(e.tagName+'@'+Math.round(r.left)+','+Math.round(r.top));
    }
  });
  out.push('顶部登录钮='+tops.length+' '+tops.join(' '));
  var box=document.querySelector('[class*="comment-input-inner-container"]');
  fire(box);
  return new Promise(function(res){
    setTimeout(function(){
      var editor=document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
      if(!editor){res('编辑器未出现');return;}
      editor.focus();
      // 主通道：execCommand（触发真实 input 事件链，Draft.js 能进 state）
      try{ document.execCommand('insertText',false,'ceshi5'); }catch(e){ out.push('execCommand ERR '+e); }
      setTimeout(function(){
        out.push('插入后=['+(editor.textContent||'').trim().slice(0,12)+']');
        // 有内容后可能出现动态"发送"钮
        var sendBtn=null;
        document.querySelectorAll('button,div,span').forEach(function(e){
          if(sendBtn)return;
          var t=(e.textContent||'').trim();
          if((t==='发送'||t==='发布')&&e.childElementCount<=2){
            var r=e.getBoundingClientRect();
            if(r.width>0)sendBtn=e;
          }
        });
        out.push('动态发送钮='+(sendBtn?sendBtn.tagName+'.'+(sendBtn.className||'').slice(0,20):'无'));
        var methods=[];
        if(sendBtn)methods.push(['文本钮',sendBtn]);
        var icons=document.querySelectorAll('[class*="commentInput-right-ct"] span');
        for(var i=icons.length-1;i>=0;i--)methods.push(['图标'+i,icons[i]]);
        var idx=0;
        function tryNext(){
          if(idx>=methods.length){out.push('全部方法试毕，未发出');res(out.join('\n'));return;}
          var m=methods[idx++];
          if(m[1])fire(m[1]);
          setTimeout(function(){
            var cleared=(editor.textContent||'').replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim().length===0;
            out.push(m[0]+' → 清空='+cleared);
            if(cleared){res(out.join('\n')+'\n== 发送成功姿势: '+m[0]);}
            else tryNext();
          },1800);
        }
        tryNext();
      },500);
    },1500);
  });
})()
