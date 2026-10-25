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
/** 发往原生的待发箱：端口重连期间的消息不能丢（跨页导航后正好会撞上重连） */
var outbox = [];

function toNative(obj) {
  if (port) {
    try {
      port.postMessage(obj);
      return;
    } catch (e) { /* 落到发件箱 */ }
  }
  outbox.push(obj);
  if (outbox.length > 50) {
    outbox.shift();
  }
}

function flushOutbox() {
  if (!port) {
    return;
  }
  while (outbox.length) {
    var obj = outbox.shift();
    try {
      port.postMessage(obj);
    } catch (e) {
      outbox.unshift(obj);
      return;
    }
  }
}

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
  flushOutbox();
}

function applyCookies(list) {
  var i = 0;
  function next() {
    if (i >= list.length) {
      try {
        toNative({ type: 'cookiesDone', n: list.length });
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
      // 新页面就绪 = 上一个页面（及其桥状态）已经不在，队列里的旧指令必须丢掉：
      // 否则会在新页面上执行"上一页的点赞"，还可能撞上桥的"同动作在途"保护（实测踩过：
      // 内核结果 ok=false detail=上一次同动作仍在执行中）。
      queue.length = 0;
      if (port) {
        try {
          toNative({ type: 'contentReady', url: msg.url });
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'dyEvent':
      if (port && msg.payload) {
        try {
          toNative(msg.payload);
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
