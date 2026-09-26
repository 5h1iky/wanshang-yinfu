(function(){
  var e=document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
  if(!e)return '编辑器不见了';
  var txt=(e.textContent||'').replace(/[\u200b\u200c\u200d\ufeff]/g,'').trim();
  return '编辑器清空='+(txt.length===0)+' 现文=['+txt.slice(0,12)+']';
})()
