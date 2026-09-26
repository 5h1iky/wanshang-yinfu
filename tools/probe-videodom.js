(function(){
  var out=[];
  function cls(e){return (e.className&&typeof e.className==='string')?e.className.trim().split(/\s+/).slice(0,3).join('.'):'';}
  // 1) 关键类名快照
  var all=document.querySelectorAll('*'),names={};
  for(var i=0;i<all.length;i++){
    var c=all[i].className;
    if(typeof c!=='string')continue;
    c.split(/\s+/).forEach(function(tok){
      if(/like|digg|collect|star|favor|comment|share|follow|praise|interact/i.test(tok)&&!names[tok])names[tok]=1;
    });
  }
  out.push('== 互动类名('+Object.keys(names).length+'): '+Object.keys(names).join(' '));
  // 2) 带文字的按钮/可点元素（赞/藏/评字样）
  out.push('== 文字按钮');
  var els=document.querySelectorAll('button,[role="button"],div,span');
  var seen={},n=0;
  for(var i=0;i<els.length&&n<14;i++){
    var e=els[i];
    var t=(e.textContent||'').replace(/\s+/g,' ').trim();
    if(!t||t.length>8)continue;
    if(!/^(赞|收藏|评论|分享|关注|已赞|已收藏|回复|\d+(\.\d+)?[万w]?)$/.test(t))continue;
    var k=e.tagName+'.'+cls(e)+'|'+t;
    if(seen[k])continue;seen[k]=1;n++;
    out.push('  '+k);
  }
  // 3) 计数元素（data-e2e 或 class 含 count/num）
  out.push('== 计数元素');
  var cnts=document.querySelectorAll('[class*="count" i],[class*="num" i],[data-e2e*="like" i],[data-e2e*="comment" i]');
  var s2={},m=0;
  for(var i=0;i<cnts.length&&m<10;i++){
    var e=cnts[i],k=e.tagName+'.'+cls(e)+' 「'+(e.textContent||'').trim().slice(0,8)+'」';
    if(s2[k])continue;s2[k]=1;m++;
    out.push('  '+k);
  }
  return out.join('\n');
})()
