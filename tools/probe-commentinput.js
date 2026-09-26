(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
  function tree(e,d){
    var pad=new Array(d+1).join('  ');
    var attrs='';
    ['contenteditable','data-placeholder','placeholder','role','aria-label','data-e2e'].forEach(function(a){
      var v=e.getAttribute(a);
      if(v!==null)attrs+=' '+a+'='+String(v).slice(0,18);
    });
    var t=(e.childElementCount===0)?('「'+(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,12)+'」'):'';
    out.push(pad+e.tagName+'.'+cls(e)+attrs+' '+t);
    for(var i=0;i<Math.min(e.childElementCount,8);i++) tree(e.children[i],d+1);
  }
  var box=document.querySelector('[class*="comment-input-inner-container"]');
  out.push('== comment-input-inner-container 树');
  if(box) tree(box,1); else out.push('(无)');
  var right=document.querySelector('[class*="commentInput-right-ct"]');
  out.push('== commentInput-right-ct 树');
  if(right) tree(right,1); else out.push('(无)');
  return out.join('\n');
})()
