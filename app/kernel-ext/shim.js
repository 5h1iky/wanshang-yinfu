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

  // 直接看一眼 profile/self 的**原文**：判断"没登录"到底是服务端说没登录、还是我们拿到的根本不是 JSON。
  setTimeout(function () {
    try {
      fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383'
        + '&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC',
        { credentials: 'include' })
        .then(function (r) {
          return r.text().then(function (t) {
            send({ type: 'rawSelf', status: r.status, len: t.length, head: String(t).slice(0, 200) });
          });
        })
        .catch(function (e) {
          send({ type: 'rawSelf', status: -1, len: 0, head: 'fetch失败: ' + String(e) });
        });
    } catch (e) { /* ignore */ }
  }, 4000);
  //    那种读数没有任何意义，还会误导判断"页面是空的"）。3 秒后再报一次。
  setTimeout(function () {
    try {
      var bodyText = (document.body && document.body.innerText) || '';
      // 只报 cookie 名字与长度（不打值）：用来判断"会话 cookie 到底有没有到页面上"
      var jar = String(document.cookie || '');
      var names = [];
      jar.split(';').forEach(function (kv) {
        var i = kv.indexOf('=');
        if (i > 0) {
          names.push(kv.slice(0, i).trim());
        }
      });
      var sidLen = -1;
      jar.split(';').forEach(function (kv) {
        var i = kv.indexOf('=');
        if (i > 0 && kv.slice(0, i).trim() === 'sessionid') {
          sidLen = kv.length - i - 1;
        }
      });
      send({
        type: 'dyInfo',
        url: String(location.href),
        nodes: document.getElementsByTagName('*').length,
        textLen: bodyText.length,
        loginWall: /扫码登录|登录后查看|立即登录/.test(bodyText),
        digg: !!document.querySelector('[data-e2e="video-player-digg"]'),
        cookieCount: names.length,
        hasSid: names.indexOf('sessionid') >= 0,
        sidLen: sidLen,
        loginBtn: !!document.querySelector('[data-e2e="login-button"], [data-e2e="login"]'),
        avatar: !!document.querySelector('[data-e2e="user-avatar"], [data-e2e="live-avatar"]'),
        head: bodyText.replace(/\s+/g, ' ').trim().slice(0, 240)
      });
    } catch (e) { /* ignore */ }
  }, 3000);

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
