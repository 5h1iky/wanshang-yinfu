(function(){
  function info(e){
    return e.tagName + (e.id?'#'+e.id:'') +
      (e.className&&typeof e.className==='string'?'.'+e.className.trim().split(/\s+/).slice(0,4).join('.'):'');
  }
  var out=[];
  out.push('URL=' + location.href.slice(0,60));
  // 找会话列表项：可点击块 + 含头像图
  var imgs=document.querySelectorAll('img');
  out.push('img总数='+imgs.length);
  var cands=[];
  for(var i=0;i<imgs.length;i++){
    var r=imgs[i].getBoundingClientRect();
    if(r.width>=28&&r.width<=90&&Math.abs(r.width-r.height)<10){
      var p=imgs[i];
      for(var d=0;d<6&&p;d++,p=p.parentElement){
        var pr=p.getBoundingClientRect();
        if(pr.width>200&&pr.height>40&&pr.height<140){cands.push(p);break;}
      }
    }
  }
  // 去重
  var seen={},uniq=[];
  for(var i=0;i<cands.length;i++){var k=cands[i].tagName+(cands[i].className||'')+Math.round(cands[i].getBoundingClientRect().top);if(!seen[k]){seen[k]=1;uniq.push(cands[i]);}}
  out.push('会话行候选='+uniq.length);
  for(var i=0;i<Math.min(uniq.length,8);i++){
    out.push('  '+info(uniq[i])+' 「'+(uniq[i].textContent||'').replace(/\s+/g,' ').trim().slice(0,24)+'」');
  }
  return out.join('\n');
})()
