/* 内核版 background（2026-10-25）
 *
 * 只做一件事：把原生给的 cookie 写进 Gecko 的 cookie 仓。
 * 为什么必须由 background 做：content script 跑在页面的隔离世界，拿不到 `browser.cookies`
 * 这种特权 API；而 K0 实测过 `cookies.set` 连 httpOnly 都能写、也能回读。
 *
 * ⚠️ 必须能重连：ensureBuiltIn 是异步的，原生侧 MessageDelegate 挂上之前 connectNative
 *    可能没人接，消息会静默丢失。用断线重连 + 每次重发 bgReady 兜住。
 */
'use strict';

function connect() {
  var port = null;
  try {
    port = browser.runtime.connectNative('browser');
  } catch (e) {
    setTimeout(connect, 600);
    return;
  }
  port.onDisconnect.addListener(function () {
    setTimeout(connect, 800);
  });
  port.onMessage.addListener(function (msg) {
    if (!msg || msg.cmd !== 'setCookies') {
      return;
    }
    var list = msg.cookies || [];
    var i = 0;
    function next() {
      if (i >= list.length) {
        try {
          port.postMessage({ type: 'cookiesDone', n: list.length });
        } catch (e) { /* ignore */ }
        return;
      }
      var c = list[i++];
      var detail = {
        url: 'https://www.douyin.com/',
        name: c.name,
        value: c.value,
        path: '/',
        secure: true,
        httpOnly: !!c.httpOnly
      };
      try {
        browser.cookies.set(detail).then(next, next);
      } catch (e) {
        next();
      }
    }
    next();
  });
  try {
    port.postMessage({ type: 'bgReady' });
  } catch (e) { /* ignore */ }
}

connect();
