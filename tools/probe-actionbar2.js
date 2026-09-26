(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
  var bars=document.querySelectorAll('[class~="D0ZulesG"]');
  out.push('D0ZulesG 容器数='+bars.length);
  for(var b=0;b<bars.length;b++){
    var bar=bars[b];
    var r=bar.getBoundingClientRect();
    out.push('== bar#'+b+' rect='+Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height));
    // 每个直接子项
    for(var i=0;i<bar.children.length;i++){
      var it=bar.children[i];
      var ir=it.getBoundingClientRect();
      var t=(it.textContent||'').replace(/\s+/g,' ').trim().slice(0,10);
      var attrs='';
      ['aria-label','title','data-e2e'].forEach(function(a){
        var v=it.getAttribute(a)||(it.querySelector('['+a+']')?it.querySelector('['+a+']').getAttribute(a):null);
        if(v)attrs+=' '+a+'='+v.slice(0,20);
      });
      // svg 路径指纹（取第一段 path 的 d 前 30 字符）
      var p=it.querySelector('svg path');
      var fp=p?('path:'+(p.getAttribute('d')||'').slice(0,26)):'';
      out.push('  ['+i+'] '+it.tagName+'.'+cls(it)+' rect='+Math.round(ir.top)+' 「'+t+'」'+attrs+' '+fp);
    }
  }
  return out.join('\n');
})()
