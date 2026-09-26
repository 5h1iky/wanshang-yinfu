(function(){
  var cs=(document.cookie||'').split(';').map(function(s){return s.split('=')[0].trim();}).filter(Boolean);
  return '页面可见cookie键(' + cs.length + '): ' + cs.join(',') + ' | hasSession=' + (cs.indexOf('sessionid')>=0);
})()
