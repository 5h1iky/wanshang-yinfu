(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
  function chain(e){
    var parts=[],p=e,d=0;
    while(p&&p!==document.body&&d<5){
      var r=p.getBoundingClientRect();
      parts.push(p.tagName+'.'+cls(p)+'@'+Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height));
      p=p.parentElement;d++;
    }
    return parts.join(' < ');
  }
  // 找叶子计数节点（文本像计数）
  var els=document.querySelectorAll('span,div');
  var found={};
  for(var i=0;i<els.length;i++){
    var e=els[i];
    if(e.childElementCount>0)continue;
    var t=(e.textContent||'').trim();
    if(!/^\d+(\.\d+)?[万w]?$/.test(t))continue;
    if(found[t])continue;
    found[t]=1;
    // 同级图标统计
    var par=e.parentElement&&e.parentElement.parentElement;
    var svgs=par?par.querySelectorAll('svg').length:0;
    out.push('「'+t+'」svg兄弟='+svgs);
    out.push('   '+chain(e));
    if(Object.keys(found).length>=8)break;
  }
  return out.join('\n');
})()
