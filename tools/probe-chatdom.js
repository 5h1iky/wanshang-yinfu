(function(){
  var out=[];
  function cls(e){ return (e.className && typeof e.className==='string') ? e.className.trim().split(/\s+/).slice(0,3).join('.') : ''; }
  function info(e,depth){
    var pad=new Array(depth+1).join('  ');
    var t=(e.childElementCount===0)?('「'+(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,18)+'」'):'';
    out.push(pad+e.tagName+'.'+cls(e)+(e.id?'#'+e.id:'')+' '+t);
    for(var i=0;i<Math.min(e.childElementCount,6);i++) info(e.children[i],depth+1);
  }
  // 1) 会话行内部结构（第一行，深3层）
  var rows=document.querySelectorAll('[class*="conversationConversationItem"]');
  out.push('== 会话行数='+rows.length);
  if(rows.length) info(rows[0],1);
  // 2) 编辑器/输入区
  out.push('== 输入区候选');
  var eds=document.querySelectorAll('[contenteditable="true"],textarea,input[type="text"]');
  out.push('editable数='+eds.length);
  for(var i=0;i<Math.min(eds.length,4);i++){
    var e=eds[i];
    out.push('  '+e.tagName+'.'+cls(e)+' ce='+e.getAttribute('contenteditable')+' ph='+(e.getAttribute('data-placeholder')||e.getAttribute('placeholder')||''));
  }
  // 3) 按钮类元素
  out.push('== 按钮候选');
  var btns=document.querySelectorAll('button,[role="button"],[class*="send" i],[class*="Send"]');
  var seen={},n=0;
  for(var i=0;i<btns.length&&n<10;i++){
    var b=btns[i], k=b.tagName+cls(b);
    if(seen[k]) continue; seen[k]=1; n++;
    out.push('  '+k+' 「'+(b.textContent||'').replace(/\s+/g,' ').trim().slice(0,10)+'」');
  }
  // 4) SPA class 命名规律（含 message/chat/editor 的类名抽样）
  var all=document.querySelectorAll('*'), names={}, cnt=0;
  for(var i=0;i<all.length&&cnt<40;i++){
    var c=all[i].className;
    if(typeof c!=='string') continue;
    c.split(/\s+/).forEach(function(tok){
      if(/message|chat|editor|send|input|conversation/i.test(tok)&&!names[tok]){names[tok]=1;cnt++;}
    });
  }
  out.push('== 关键类名('+cnt+'): '+Object.keys(names).join(' '));
  return out.join('\n');
})()
