(function(){
  var out=[];
  out.push('URL='+location.href.slice(0,50));
  var els=document.querySelectorAll('div,li,a,span');
  var rows=[];
  for(var i=0;i<els.length;i++){
    var e=els[i];
    var t=(e.textContent||'').replace(/\s+/g,' ').trim();
    if(!t||t.length>60) continue;
    if(e.children.length>6) continue;
    var r=e.getBoundingClientRect();
    if(r.width<60||r.height<12) continue;
    rows.push(Math.round(r.top)+','+Math.round(r.left)+' '+Math.round(r.width)+'x'+Math.round(r.height)+' '+(e.tagName)+'.'+((e.className||'').toString().trim().split(/\s+/).slice(0,2).join('.'))+' 「'+t.slice(0,20)+'」');
    if(rows.length>=25) break;
  }
  out.push('文本块('+rows.length+'):');
  for(var i=0;i<rows.length;i++) out.push('  '+rows[i]);
  return out.join('\n');
})()
