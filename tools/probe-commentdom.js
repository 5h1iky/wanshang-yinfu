(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
  // 1) 评论列表结构：comment-mainContent 内部（昵称/文本/点赞）
  var items=document.querySelectorAll('[class*="comment-mainContent"]');
  out.push('comment-mainContent 数='+items.length);
  if(items.length){
    var it=items[0];
    out.push('== 第一条评论树');
    (function tree(e,d){
      var pad=new Array(d+1).join('  ');
      var t=(e.childElementCount===0)?('「'+(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,16)+'」'):'';
      out.push(pad+e.tagName+'.'+cls(e)+' '+t);
      for(var i=0;i<Math.min(e.childElementCount,8);i++) tree(e.children[i],d+1);
    })(it,1);
  }
  // 2) 评论输入区
  out.push('== 输入区候选');
  var eds=document.querySelectorAll('[class*="commentInput" i],[class*="comment-input" i],textarea,[contenteditable="true"]');
  for(var i=0;i<Math.min(eds.length,6);i++){
    var e=eds[i];
    out.push('  '+e.tagName+'.'+cls(e)+' ce='+e.getAttribute('contenteditable')+' ph='+(e.getAttribute('data-placeholder')||e.getAttribute('placeholder')||''));
  }
  // 3) 发送按钮（含"发送"文字或 commentInput 内按钮）
  out.push('== 发送候选');
  var all=document.querySelectorAll('button,div,span');
  var n=0,seen={};
  for(var i=0;i<all.length&&n<10;i++){
    var e=all[i],t=(e.textContent||'').trim();
    if(t!=='发送'&&t!=='发布'&&t!=='评论')continue;
    var k=e.tagName+'.'+cls(e)+'|'+t;
    if(seen[k])continue;seen[k]=1;n++;
    out.push('  '+k);
  }
  return out.join('\n');
})()
