(function(){
  var out=[];
  var boxes=document.querySelectorAll('[class*="messageMessageBoxmessageBox"]');
  out.push('气泡总数='+boxes.length);
  for(var i=0;i<boxes.length;i++){
    var b=boxes[i];
    var mine=b.querySelector('[class*="isFromMe"]')?'我':'对方';
    var txt=(b.textContent||'').replace(/\s+/g,' ').trim().slice(0,30);
    out.push('  ['+i+'] '+mine+' 「'+txt+'」');
  }
  var editor=document.querySelector('[class*="messageEditorinputArea"][contenteditable="true"]');
  out.push('编辑器现文=['+((editor&&editor.textContent)||'').trim().slice(0,20)+']');
  return out.join('\n');
})()
