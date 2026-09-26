(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,4).join('.'):'';}
  function tree(e,d){
    var pad=new Array(d+1).join('  ');
    var t=(e.childElementCount===0)?('「'+(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,12)+'」'):'';
    var extra='';
    if(e.tagName==='SVG'||e.tagName==='svg') extra=' [svg]';
    out.push(pad+e.tagName+'.'+cls(e)+extra+' '+t);
    for(var i=0;i<Math.min(e.childElementCount,8);i++) tree(e.children[i],d+1);
  }
  var act=document.querySelector('[class*="messageMsgInputinputAction"]');
  out.push('== inputAction 树');
  if(act) tree(act,1); else out.push('(无)');
  var row=document.querySelector('[class*="messageMsgInputinputRow"]');
  out.push('== inputRow 树(深3)');
  if(row){
    out.push('ROW.'+cls(row));
    for(var i=0;i<Math.min(row.childElementCount,8);i++) tree(row.children[i],2);
  }
  return out.join('\n');
})()
