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
    if (msg.cmd === 'chatFetch') {
      chatFetch();
      return;
    }
    if (msg.cmd === 'setCookies') {
      applyCookies(msg.cookies || []);
      return;
    }
    // 2026-11-07（代码审计 H2）：退出登录时把 Gecko cookie 仓里 .douyin.com 的会话全部删掉。
    // 不删的话 "退出登录 → 换个账号登录" 会继续用上一个人的 cookie 拉私信。
    if (msg.cmd === 'clearCookies') {
      clearCookies();
      return;
    }
  });
  try {
    port.postMessage({ type: 'bgReady' });
  } catch (e) { /* ignore */ }
  flushOutbox();
}

function applyCookies(list) {
  // ⚠️ 只在 Gecko 的 cookie 仓里**还没有会话**时才灌。
  // 每次启动都覆盖会把 Gecko 自己刷新过的 __ac_signature / ttwid / msToken 冲掉，
  // 连"人机验证已通过"的状态也会被一起重置（真机现象：过了验证、重开 App 又回到"暂无会话"）。
  var run = function () {
    applyCookiesNow(list);
  };
  try {
    browser.cookies.get({ url: 'https://www.douyin.com/', name: 'sessionid' })
      .then(function (found) {
        // 已有 `.douyin.com` 域会话才跳过；如果只是旧的 host-only（www.douyin.com），
        // 必须重灌一次，否则 IM 子域永远未登录（2026-10-31 实测）。
        var domainOk = !!(found && found.domain && found.domain.charAt(0) === '.');
        if (domainOk) {
          toNative({
            type: 'cookiesDone', n: 0, ok: 0, fail: 0, firstErr: '',
            sessionid: true, skipped: true
          });
        } else {
          run();
        }
      }, run);
  } catch (e) {
    run();
  }
}

function applyCookiesNow(list) {
  var i = 0;
  var okCount = 0;
  var failCount = 0;
  var firstErr = '';
  function done() {
    // 回读一条关键 cookie：`cookies.set` 的失败会被 reject 吞掉（实测），只有回读能证明真写进去了
    var finish = function (found) {
      toNative({
        type: 'cookiesDone', n: list.length, ok: okCount, fail: failCount,
        firstErr: firstErr, sessionid: !!found
      });
    };
    try {
      browser.cookies.get({ url: 'https://www.douyin.com/', name: 'sessionid' })
        .then(finish, function () { finish(null); });
    } catch (e) {
      finish(null);
    }
  }
  function next() {
    if (i >= list.length) {
      done();
      return;
    }
    var c = list[i++];
    // ⚠️ 必须写成 `.douyin.com` 域 cookie：IM 的接口在 www-hj.douyin.com / imapi.douyin.com
    //    这些子域上；只写 www.douyin.com 是 host-only，子域请求全部"用户未登录"（2026-10-31 实测）。
    var base = {
      url: 'https://www.douyin.com/',
      name: c.name,
      value: c.value,
      path: '/',
      secure: true,
      httpOnly: !!c.httpOnly
    };
    var withDomain = {
      url: base.url, name: base.name, value: base.value, path: base.path,
      secure: base.secure, httpOnly: base.httpOnly, domain: '.douyin.com'
    };
    var ok = function () {
      okCount++;
      next();
    };
    var fail = function (e) {
      // 有些 cookie 不允许带 domain（或值不合法），退回 host-only，至少 www 主站能用
      try {
        browser.cookies.set(base).then(ok, function (e2) {
          failCount++;
          if (!firstErr) {
            firstErr = String(e2 || e).slice(0, 80);
          }
          next();
        });
      } catch (e2) {
        failCount++;
        if (!firstErr) {
          firstErr = String(e2 || e).slice(0, 80);
        }
        next();
      }
    };
    try {
      // 先删同名旧 cookie（可能是上一版写入的 host-only），再写域 cookie；
      // 否则同名的 host-only 与 .douyin.com 会同时存在，请求头里出现两份 sessionid。
      browser.cookies.remove({ url: base.url, name: c.name }).then(function () {
        try {
          browser.cookies.set(withDomain).then(ok, fail);
        } catch (e) {
          fail(e);
        }
      }, function () {
        try {
          browser.cookies.set(withDomain).then(ok, fail);
        } catch (e) {
          fail(e);
        }
      });
    } catch (e) {
      fail(e);
    }
  }
  next();
}

/**
 * 清掉 .douyin.com 下的全部 cookie（2026-11-07，代码审计 H2）。
 *
 * 为什么必须由扩展来做：GeckoView 没有任何公开的 cookie 清理 API（11 号调研已确认），
 * 唯一能摸到 cookie 仓的就是这个内置扩展的 `browser.cookies`。
 * 退出登录时原生侧发 `{"cmd":"clearCookies"}` 过来。
 */
function clearCookies() {
  var domains = ['.douyin.com', 'www.douyin.com', 'imapi.douyin.com'];
  var done = 0;
  var removed = 0;
  var next = function () {
    if (++done >= domains.length) {
      toNative({ type: 'cookiesCleared', removed: removed });
    }
  };
  domains.forEach(function (domain) {
    var query = { domain: domain };
    try {
      browser.cookies.getAll(query).then(function (list) {
        var pending = (list || []).length;
        if (!pending) {
          next();
          return;
        }
        list.forEach(function (c) {
          var url = (c.secure ? 'https://' : 'http://') + (c.domain || 'www.douyin.com').replace(/^\./, '') + (c.path || '/');
          try {
            browser.cookies.remove({ url: url, name: c.name }).then(function () {
              removed++;
              if (--pending === 0) next();
            }, function () {
              if (--pending === 0) next();
            });
          } catch (e) {
            if (--pending === 0) next();
          }
        });
      }, function () {
        next();
      });
    } catch (e) {
      next();
    }
  });
}

function chatFetch() {
  // background 的扩展上下文不是页面：可用来区分"页面 JS/CSP 把请求吞了"与"网络/API 本身失败"。
  var url = 'https://www.douyin.com/aweme/v1/web/im/resource/list/aggregation/'
    + '?device_platform=webapp&aid=1128&channel=channel_pc_web&app_id=1128'
    + '&scenes=CUSTOM_STICKER_PAGE&custom_cursor=0&custom_limit=50&source=CUSTOM_STICKER_PAGE';
  try {
    fetch(url, { credentials: 'include' }).then(function (r) {
      return r.text().then(function (t) {
        toNative({ type: 'chatFetch', status: r.status, url: r.url, len: t.length,
                   head: String(t).slice(0, 500) });
      });
    }).catch(function (e) {
      toNative({ type: 'chatFetch', status: -1, url: '', len: 0, head: String(e) });
    });
  } catch (e) {
    toNative({ type: 'chatFetch', status: -2, url: '', len: 0, head: String(e) });
  }
}

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || !msg.type) {
    return Promise.resolve(null);
  }
  switch (msg.type) {
    case 'rawSelf':
      if (port) {
        try {
          toNative({ type: 'rawSelf', status: msg.status, len: msg.len, head: msg.head });
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'dyInfo':
      if (port) {
        try {
          toNative({ type: 'pageInfo', url: msg.url, nodes: msg.nodes, textLen: msg.textLen,
                          loginWall: msg.loginWall, digg: msg.digg, head: msg.head,
                          cookieCount: msg.cookieCount, hasSid: msg.hasSid, sidLen: msg.sidLen,
                          loginBtn: msg.loginBtn, avatar: msg.avatar });
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'dyHook':
      if (port && msg.hook) {
        try {
          toNative({ type: 'dyHook', hook: msg.hook });
        } catch (e) { /* ignore */ }
      }
      return Promise.resolve({ ok: true });
    case 'dyReady':
      // 新页面就绪 = 上一个页面（及其桥状态）已经不在，队列里的旧指令必须丢掉：
      // 否则会在新页面上执行"上一页的点赞"，还可能撞上桥的"同动作在途"保护（实测踩过：
      // 内核结果 ok=false detail=上一次同动作仍在执行中）。
      queue.length = 0;
      if (port) {
        try {
          toNative({ type: 'contentReady', url: msg.url, nodes: msg.nodes, textLen: msg.textLen, loginWall: msg.loginWall, digg: msg.digg });
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
