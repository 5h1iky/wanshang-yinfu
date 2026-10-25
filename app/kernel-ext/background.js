/* 内核版 background（2026-10-25，v2：中继版）
 *
 * 职责两件：
 *   ① 持有到原生的 native 端口（content script 在 gv100 里连不通，只能由 background 转发）；
 *   ② 把原生下发的 `invoke` 指令排队，等 content 的 poll 来取。
 *
 * 为什么用"content 轮询"而不是 background 主动推：GeckoView 里 `tabs.query` 查不到活动标签
 * （探针工程实测），background 拿不到 content 的去向；而 `runtime.sendMessage` 的应答是可靠的。
 * 250ms 的取指令延迟，对点赞/收藏这种秒级操作无感。
 *
 * cookie：K0 实测 `browser.cookies.set` 连 httpOnly 都能写，这里只负责把原生给的列表灌进去。
 */
'use strict';

var port = null;
var queue = [];

function connect() {
  try {
    port = browser.runtime.connectNative('browser');
  } catch (e) {
    port = null;
    setTimeout(connect, 600);
    return;
  }
  port.onDisconnect.addListener(function () {
    port = null;
    setTimeout(connect, 800);
  });
  port.onMessage.addListener(function (msg) {
    if (!msg) {
      return;
    }
    if (msg.cmd === 'invoke') {
      queue.push(msg);
      return;
    }
    if (msg.cmd === 'setCookies') {
      applyCookies(msg.cookies || []);
    }
  });
  try {
    port.postMessage({ type: 'bgReady' });
  } catch (e) { /* ignore */ }
}

function applyCookies(list) {
  var i = 0;
  function next() {
    if (i >= list.length) {
      try {
        port.postMessage({ type: 'cookiesDone', n: list.length });
      } catch (e) { /* ignore */ }
      return;
    }
    var c = list[i++];
    try {
      browser.cookies.set({
        url: 'https://www.douyin.com/',
        name: c.name,
        value: c.value,
        path: '/',
        secure: true,
        httpOnly: !!c.httpOnly
      }).then(next, next);
    } catch (e) {
      next();
    }
  }
  next();
}

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || !msg.type) {
    return Promise.resolve(null);
  }
  switch (msg.type) {
    case 'dyReady':
      if (port) {
        try {
          port.postMessage({ type: 'contentReady', url: msg.url });
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'dyEvent':
      if (port && msg.payload) {
        try {
          port.postMessage(msg.payload);
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'poll':
      return Promise.resolve(queue.length ? queue.shift() : null);
    default:
      return Promise.resolve(null);
  }
});

connect();
