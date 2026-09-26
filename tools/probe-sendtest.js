(function(){
  // 发送实测（目标：5h1iky 小号会话）：注入文本 → 回车 → 观察气泡变化
  function msgCount(){ return document.querySelectorAll('[class*="messageMessageBoxmessageBox"]').length; }
  var editor=document.querySelector('[class*="messageEditorinputArea"][contenteditable="true"]');
  if(!editor) return 'ERR 未找到编辑器';
  var before=msgCount();
  editor.focus();
  var text='测试消息'+Math.floor(Math.random()*90+10);
  // 方法1：粘贴事件注入（Draft/Slate 都处理 paste）
  var ok1=false;
  try{
    var dt=new DataTransfer();
    dt.setData('text/plain',text);
    editor.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));
    ok1=true;
  }catch(e){}
  return new Promise(function(res){
    setTimeout(function(){
      var got=editor.textContent||'';
      var used='';
      if(got.indexOf(text)>=0){ used='paste注入'; }
      else{
        try{ document.execCommand('insertText',false,text); used='execCommand'; }catch(e){ used='ERR '+e; }
      }
      setTimeout(function(){
        var edText=(editor.textContent||'').trim();
        // 回车发送
        editor.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
        editor.dispatchEvent(new KeyboardEvent('keypress',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
        editor.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
        setTimeout(function(){
          var after=msgCount();
          var boxes=document.querySelectorAll('[class*="messageMessageBoxmessageBox"]');
          var last=boxes.length?boxes[boxes.length-1].textContent.replace(/\s+/g,' ').trim().slice(0,24):'';
          res('文本='+text+'\n注入方式='+used+'\n编辑器现文=['+edText.slice(0,20)+']\n气泡 before='+before+' after='+after+' 变化='+(after>before?'✅新消息出现':'❌无变化')+'\n最后气泡「'+last+'」');
        },3000);
      },400);
    },400);
  });
})()
