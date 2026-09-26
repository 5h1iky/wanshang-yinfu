(function(){
  var ov = document.querySelector('div[style*="2147483647"]');
  var c = document.querySelectorAll('img,canvas');
  var best=null,ba=0;
  for (var i=0;i<c.length;i++){
    var r=c[i].getBoundingClientRect();
    var a=r.width*r.height;
    var sq=Math.abs(r.width-r.height)/Math.max(r.width,1);
    if(a>ba&&sq<0.4&&r.width>=50){best=c[i];ba=a;}
  }
  return 'overlay=' + (ov?'Y':'N') + ' qr=' + (best ? best.tagName+' '+Math.round(best.getBoundingClientRect().width)+'x'+Math.round(best.getBoundingClientRect().height) : 'N');
})()
