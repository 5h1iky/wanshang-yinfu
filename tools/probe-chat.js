(function(){
  function info(e){
    return e.tagName + (e.id?'#'+e.id:'') +
      (e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\s+/).slice(0,3).join('.'):'') +
      ' ['+Math.round(e.getBoundingClientRect().width)+'x'+Math.round(e.getBoundingClientRect().height)+']';
  }
  var out=[];
  out.push('URL=' + location.href.slice(0,70));
  out.push('title=' + document.title);
  var eds=document.querySelectorAll('[contenteditable],input,textarea');
  out.push('编辑器候选('+eds.length+'):');
  for(var i=0;i<Math.min(eds.length,10);i++){
    out.push('  '+info(eds[i])+' ce='+(eds[i].getAttribute('contenteditable')||'-'));
  }
  var dc=document.querySelectorAll('[data-contents="true"]');
  out.push('DraftJS[data-contents]='+dc.length);
  var btns=document.querySelectorAll('button,[role="button"]');
  out.push('按钮('+btns.length+'):');
  for(var i=0;i<Math.min(btns.length,20);i++){
    var t=(btns[i].textContent||'').replace(/\s+/g,' ').trim().slice(0,10);
    out.push('  '+info(btns[i])+' 「'+t+'」');
  }
  return out.join('\n');
})()
