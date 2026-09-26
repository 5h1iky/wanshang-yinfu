// 强制整页重载（拿服务端渲染的初始态，绕开 SPA 本地状态）
location.reload();
'RELOADING ' + location.href.slice(0, 60);
