(function(){
  var out=[];
  // 1) 页面登录标志
  var bodyText=(document.body.innerText||'').replace(/\s+/g,' ');
  var hasLoginBtn=/^登录$|登录\/注册/.test('x')?false:(document.querySelectorAll('[class*="login" i],[data-e2e*="login" i]').length>0);
  out.push('URL='+location.href.slice(0,55));
  out.push('登录类元素数='+document.querySelectorAll('[class*="login" i],[data-e2e*="login" i]').length);
  // 2) 评论总数（评论图标旁的计数）
  var ci=document.querySelector('[data-e2e="feed-comment-icon"]');
  out.push('评论图标计数='+(ci?(ci.textContent||'').trim():'?'));
  out.push('当前DOM评论条数='+document.querySelectorAll('[class*="comment-item-info-wrap"]').length);
  // 3) 手动发送实验
  var box=document.querySelector('[class*="comment-input-inner-container"]');
  if(!box){ out.push('无评论框'); return out.join('\n'); }
  ['mousedown','mouseup','click'].forEach(function(t){
    box.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));
  });
  return new Promise(function(res){
    setTimeout(function(){
      var editor=document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
      if(!editor){ out.push('编辑器未出现'); res(out.join('\n')); return; }
      editor.focus();
      try{
        var dt=new DataTransfer();
        dt.setData('text/plain','ceshi4');
        editor.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));
      }catch(e){}
      setTimeout(function(){
        if((editor.textContent||'').indexOf('ceshi4')<0){
          try{document.execCommand('insertText',false,'ceshi4');}catch(e){}
        }
        out.push('注入后编辑器=['+(editor.textContent||'').trim().slice(0,12)+']');
        // 发送钮候选全列
        var icons=document.querySelectorAll('[class*="commentInput-right-ct"] span');
        out.push('发送区图标数='+icons.length);
        var target=icons[icons.length-1];
        if(target){
          ['mousedown','mouseup','click'].forEach(function(t){
            target.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));
          });
        }
        setTimeout(function(){
          var cleared=(editor.textContent||'').replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim().length===0;
          out.push('点击末位图标后: 编辑器清空='+cleared);
          var t2=(document.body.innerText||'').replace(/\s+/g,' ');
          out.push('出现登录弹层='+(/扫码登录|登录后免费|请先登录/.test(t2)));
          out.push('DOM评论条数(后)='+document.querySelectorAll('[class*="comment-item-info-wrap"]').length);
          if(!cleared&&icons.length>1){
            // 再试点倒数第二个图标
            var t2i=icons[icons.length-2];
            ['mousedown','mouseup','click'].forEach(function(t){
              t2i.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));
            });
            setTimeout(function(){
              var c2=(editor.textContent||'').replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim().length===0;
              out.push('点击次位图标后: 编辑器清空='+c2);
              res(out.join('\n'));
            },2000);
          }else{
            res(out.join('\n'));
          }
        },2500);
      },400);
    },1500);
  });
})()
