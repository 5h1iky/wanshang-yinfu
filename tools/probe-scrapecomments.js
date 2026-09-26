(function(){
  var wraps=document.querySelectorAll('[class*="comment-item-info-wrap"]');
  var items=[];
  for(var i=0;i<wraps.length;i++){
    var col=wraps[i].parentElement;
    if(!col)continue;
    var name=(wraps[i].textContent||'').replace(/\s+/g,' ').trim();
    var text='',time='',likes='';
    for(var c=0;c<col.children.length;c++){
      var el=col.children[c];
      var t=(el.textContent||'').replace(/\s+/g,' ').trim();
      if(el.querySelector('[class*="comment-item-info-wrap"]'))continue;
      if(el.querySelector('[class*="comment-item-stats-container"]')){
        var sp=el.querySelector('p span');
        likes=sp?(sp.textContent||'').trim():'';
        continue;
      }
      if(/\d+(天|小时|分钟|秒)前|^刚刚/.test(t)){time=t;continue;}
      if(t.length>text.length)text=t;
    }
    if(name||text)items.push(name+' | '+text.slice(0,20)+' | '+time+' | ❤'+likes);
  }
  return '共'+items.length+'条:\n'+items.join('\n');
})()
