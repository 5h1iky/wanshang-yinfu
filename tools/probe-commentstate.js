(function(){
  var t=(document.body.innerText||'').replace(/\s+/g,' ').slice(0,260);
  return 'URL='+location.href.slice(0,60)
    +' | wraps='+document.querySelectorAll('[class*="comment-item-info-wrap"]').length
    +' main='+document.querySelectorAll('[class*="comment-mainContent"]').length
    +' inputBox='+document.querySelectorAll('[class*="comment-input-inner-container"]').length
    +' | 文本: '+t;
})()
