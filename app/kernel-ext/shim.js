/* 内核版 content 侧 shim（2026-10-25，v2：中继版）
 *
 * ⚠️ 为什么不用 `browser.runtime.connectNative`：gv100 里 content script 直接连 native 端口
 *    消息到不了原生侧（探针工程 2026-10-24 实测并记录过同样的问题）。现在改成
 *      content --runtime.sendMessage--> background --connectNative--> 原生
 *    并靠 content 每 250ms 发一次 poll 从 background 取指令（GeckoView 里 tabs.query 查不到活动标签，
 *    所以 background 没法主动 push 给 content，只能让 content 来拉）。
 *
 * 桥脚本（chat_bridge.js）一行不改：它调 window.AndroidBridge.onBridgeEvent(json)，
 * 这里把 json 转成消息发给 background 即可。
 */
(function () {
  'use strict';
  if (window.__dyShim) {
    return;
  }
  window.__dyShim = true;
  // 桥脚本用它做"文档密钥"校验（WebView 线上由原生注入）；内核线固定一个常量
  window.__dywatch_key = 'kernel';

  function send(msg) {
    try {
      return browser.runtime.sendMessage(msg);
    } catch (e) {
      return null;
    }
  }

  function runCommand(cmd) {
    try {
      var br = window.ChatBridge;
      var fn = br && br[cmd.fn];
      if (typeof fn !== 'function') {
        send({
          type: 'dyEvent', payload: {
            type: 'action', action: cmd.fn, ok: false,
            detail: '桥里没有 ' + cmd.fn, reqId: cmd.reqId
          }
        });
        return;
      }
      // 原生用字符串传参（argv 形式）：Android 的 org.json 直接 put 数组会抛异常
      var args = [];
      if (cmd.argsJson) {
        try {
          args = JSON.parse(cmd.argsJson) || [];
        } catch (e) {
          args = [];
        }
      } else if (cmd.args) {
        args = cmd.args;
      }
      fn.apply(br, args);
    } catch (e) {
      send({
        type: 'dyEvent', payload: {
          type: 'action', action: cmd.fn, ok: false,
          detail: String(e), reqId: cmd.reqId
        }
      });
    }
  }

  window.AndroidBridge = {
    onBridgeEvent: function (json) {
      try {
        send({ type: 'dyEvent', payload: JSON.parse(json) });
      } catch (e) { /* ignore */ }
    }
  };

  // 告诉原生"这一页的桥已就位"（每页导航后会重跑一次）
  send({ type: 'dyReady', url: String(location.href) });

  // 拉取指令（唯一可行的下行通道：background 不能主动 push 到 content）
  setInterval(function () {
    var p = send({ type: 'poll' });
    if (!p || typeof p.then !== 'function') {
      return;
    }
    p.then(function (cmd) {
      if (cmd && cmd.cmd === 'invoke') {
        runCommand(cmd);
      }
    }, function () { /* ignore */ });
  }, 250);
})();
