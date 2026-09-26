fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&update_version_code=170400&pc_client_type=1&version_code=170400&version_name=17.4.0&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=153.0.0.0&browser_online=true&os_name=Windows&os_version=10&platform=PC',{credentials:'include'}).then(function(r){return r.text();}).then(function(t){
  try{
    var j=JSON.parse(t);
    return 'profile/self: hasUser='+(!!(j.user&&j.user.uid))+' status_code='+j.status_code+' status_msg='+(j.status_msg||'')+' nickname='+(j.user&&j.user.nickname?j.user.nickname:'-');
  }catch(e){return 'parse err len='+t.length+' 前80: '+t.slice(0,80);}
}).catch(function(e){return 'ERR '+e;})
