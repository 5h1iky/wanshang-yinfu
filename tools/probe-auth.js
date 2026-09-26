fetch('https://www.douyin.com/',{credentials:'include'}).then(function(r){return r.text();}).then(function(t){
  return 'len='+t.length+' 含用户名(明日awo)='+(t.indexOf('明日awo')>=0)+' 含登录按钮='+(t.indexOf('登录')>=0)+' sessionidHeader='+'-';
}).catch(function(e){return 'ERR '+e;})
