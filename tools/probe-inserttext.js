(function(){
  var e=document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
  if(!e)return '编辑器未出现';
  e.focus();
  try{document.execCommand('insertText',false,'ceshi6');}catch(x){}
  return '已插入=['+(e.textContent||'').trim().slice(0,10)+']';
})()
