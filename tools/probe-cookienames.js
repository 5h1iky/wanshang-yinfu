(function(){
  // 只输出 cookie 键名，绝不输出值（敏感值纪律）
  var names=(document.cookie||'').split(';').map(function(p){return p.split('=')[0].trim();}).filter(Boolean);
  return Promise.resolve('cookie键名('+names.length+'): '+names.join(','));
})()
