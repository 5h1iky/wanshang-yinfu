/* 内核版 content 侧 shim（2026-10-25）
 *
 * 作用：把 `chat_bridge.js` 里那套 `window.AndroidBridge.onBridgeEvent(json)` 的出口，
 * 换成 GeckoView 的 native messaging 长连接；同时接受原生下发的指令。
 *
 * 为什么要 shim 而不是重写桥：`chat_bridge.js` 是 WebView 线与内核线**共用**的同一份文件
 * （P0-1 的判据/幂等修复只在它上面做），重写一份必然漂移。这里只做接口适配：
 *   桥 → 原生：AndroidBridge.onBridgeEvent(json) → port.postMessage(obj)
 *   原生 → 桥：{cmd:'invoke', fn:'likeVideo', args:[...]} → window.ChatBridge[fn](...)
 */
(function () {
  'use strict';
  if (window.__dyShim) {
    return;
  }
  window.__dyShim = true;
  // 桥脚本用它做"文档密钥"校验（WebView 线上由原生注入）；内核线固定一个常量即可
  window.__dywatch_key = 'kernel';

  var port = null;

  function connect() {
    try {
      port = browser.runtime.connectNative('browser');
    } catch (e) {
      port = null;
      setTimeout(connect, 700);
      return;
    }
    port.onDisconnect.addListener(function () {
      port = null;
      setTimeout(connect, 900);
    });
    port.onMessage.addListener(function (msg) {
      if (!msg || msg.cmd !== 'invoke') {
        return;
      }
      try {
        var br = window.ChatBridge;
        var fn = br && br[msg.fn];
        if (typeof fn !== 'function') {
          port.postMessage({
            type: 'action', action: msg.fn, ok: false,
            detail: '桥里没有 ' + msg.fn, reqId: msg.reqId
          });
          return;
        }
        fn.apply(br, msg.args || []);
      } catch (e) {
        try {
          port.postMessage({
            type: 'action', action: msg.fn, ok: false,
            detail: String(e), reqId: msg.reqId
          });
        } catch (e2) { /* ignore */ }
      }
    });
    try {
      port.postMessage({ type: 'contentReady', url: String(location.href) });
    } catch (e) { /* ignore */ }
  }

  window.AndroidBridge = {
    onBridgeEvent: function (json) {
      try {
        if (port) {
          port.postMessage(JSON.parse(json));
        }
      } catch (e) { /* ignore */ }
    }
  };

  connect();
})();
