// 聊天 JS 桥 v4（架构 A）：抖音私信页 + 视频页互动 DOM 校准版。
// 2026-09-25 CDP 真机标定（选择器均有实证）：
//   会话行   div.conversationConversationItemwrapper（名字 .conversationConversationItemtitle
//            时间 .ConversationItemTagNextToTitletimeStr 描述 .ConversationItemDescwrapper）
//   消息列表 div.messageMessageListlist / 气泡 div.messageMessageBoxmessageBox
//   方向     气泡内 div.messageMessageBoxcontentBox.isFromMe = 我方
//   文本     span.TextMessageTexttextInnerContent  时间 div.MessageBoxTimetimeLayout
//   输入框   div.messageEditorinputArea[contenteditable=true]（Slate/EditorKit）
//   发送     粘贴注入 + 回车（实测有效）；兜底点击 inputAction 末尾 svg
// 2026-09-26 v4 变更（全部来自 CDP 实测结论，不是猜的）：
//   ① 点赞/收藏判据改为「className 是否翻转」——计数文本四舍五入到 0.1 万，±1 赞看不出来；
//      旧实现 ok 恒为 true 属于假阳性（日志实证：collect ok=true 4.0万→4.0万 changed=false）。
//   ② 视频页交互区（digg/collect）在 display:none 的「沉浸式隐藏交互区」内，rect 全 0
//      → 坐标型真实点击必然打不中，只有派发事件序列有效；故事件序列加 pointerdown/pointerup。
//   ③ 同动作在飞去重（实测用户连点 4 次 → 并存 4 个轮询定时器，各回报一次）。
//   ④ 新增 ensureImHome()：互动会把全局唯一引擎导航到 /video/*，不归位则聊天/会话列表永久空。
//   ⑤ 评论提交：不硬编码 hash 类名，改为枚举 commentInput-right-ct 内可点 span 逐个试 + 回车兜底，
//      判定用 Draft 的 [data-text] 镜像与 textContent 双通道是否清空（DOM 直改会让 Draft state 脱钩，禁用）。
(function () {
  if (window.ChatBridge && window.ChatBridge.__v4) return;

  function post(obj) {
    try {
      if (window.AndroidBridge && AndroidBridge.onBridgeEvent) {
        AndroidBridge.onBridgeEvent(JSON.stringify(obj));
      }
    } catch (e) {}
  }

  function fireClick(el) {
    // ⚠️ 只用 mouse 三事件（2026-09-25 实测能真实翻转已赞态）。
    // 加 pointerdown/pointerup 后同一按钮反而“状态未变”（实测：v4 首版 12:16 一次 like ok=false），
    // 推因是指针事件与点击事件各触发一次 toggle、净效果抵消。未重现前勿再加回。
    ['mousedown', 'mouseup', 'click'].forEach(function (type) {
      el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
    });
  }

  function isLoginWall() {
    var t = (document.body && document.body.innerText) || '';
    return t.indexOf('扫码登录') >= 0 || t.indexOf('登录后免费畅享') >= 0;
  }

  function wait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  window.ChatBridge = {
    __v4: true,

    // 登录态检查（DOM 登录墙 + profile/self 双重验证）
    checkAuth: function () {
      try {
        if (isLoginWall()) {
          post({ type: 'auth', ok: false, message: '会话未生效：页面出现登录墙，请重新扫码登录' });
          return;
        }
        fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400&cookie_enabled=true&platform=PC', { credentials: 'include' })
          .then(function (r) { return r.json(); })
          .then(function (j) {
            if (j && j.user && j.user.uid) {
              post({ type: 'auth', ok: true, user: j.user.nickname || '', uid: String(j.user.uid) });
            } else {
              post({ type: 'auth', ok: false, message: '会话无效(status_code=' + (j ? j.status_code : '?') + ')，请重新扫码登录' });
            }
          })
          .catch(function (e) { post({ type: 'auth', ok: null, message: '登录探测失败: ' + e }); });
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    },

    // 会话列表（DOM 抓取，新→旧序）
    // ⚠️ 类名是拼接式单词（wrapper/rowArea1/title 互为子串），必须用 [class~=] 整词匹配，
    // 否则行内元素全被当会话行捞出来（真机实测踩过：子串匹配→名字全空→列表为空）
    fetchConversations: function () {
      var self = this;
      // SPA 先把 uid 当昵称渲染、随后才换成真昵称（实测 18:39:04 全是 768941… / 18:39:05 变回 5h1iky）。
      // DOM 里没有第二个地方存真昵称，所以只能在"还有纯数字占位名"时继续在页内等，
      // 而不是把中间态吐给原生——那会让列表闪一下数字。
      var collect = function () {
        var items = [], pending = 0;
        var rows = document.querySelectorAll('[class~="conversationConversationItemwrapper"]');
        for (var i = 0; i < rows.length; i++) {
          var r = rows[i];
          var nameEl = r.querySelector('[class~="conversationConversationItemtitle"]');
          var timeEl = r.querySelector('[class~="ConversationItemTagNextToTitletimeStr"]');
          var descEl = r.querySelector('[class*="ConversationItemDescwrapper"]');
          var name = nameEl ? (nameEl.textContent || '') : '';
          var time = timeEl ? (timeEl.textContent || '') : '';
          var desc = descEl ? (descEl.textContent || '') : '';
          name = name.replace(/\s+/g, ' ').trim();
          if (!name) continue;
          if (/^\d{6,}$/.test(name)) pending++;
          // ⚠️ 实测：描述位会被"在线状态"覆盖（😂 → 昨天在线 / 60分钟内在线）→ 认出后丢弃，别当最近消息显示
          desc = desc.replace(/\s+/g, ' ').trim();
          if (/^(刚刚|[\d一二两三四五六七十半]+分钟内在线|[\d]+\s?(小时|天|周)内在线|昨天在线|在线|离线|对方[：:].*)$/.test(desc)) desc = '';
          items.push({ key: String(i), name: name, lastMsg: desc, time: time.trim() });
        }
        return { items: items, pending: pending, rows: rows.length };
      };
      var tries = 0;
      var step = function () {
        tries++;
        try {
          var r = collect();
          if ((r.rows > 0 && r.pending === 0) || tries > 14) {
            post({ type: 'conversations', items: r.items });
            return;
          }
        } catch (e) {
          post({ type: 'error', message: String(e) });
          return;
        }
        setTimeout(step, 500);
      };
      step();
    },

    // 进入指定会话（按名字匹配会话行点击；name 为空=保持当前）
    openConversation: function (name) {
      try {
        if (!name) { post({ type: 'opened', ok: true, name: '' }); return; }
        var rows = document.querySelectorAll('[class~="conversationConversationItemwrapper"]');
        for (var i = 0; i < rows.length; i++) {
          var t = rows[i].querySelector('[class~="conversationConversationItemtitle"]');
          if (t && t.textContent.replace(/\s+/g, ' ').trim().indexOf(name) >= 0) {
            fireClick(t);
            post({ type: 'opened', ok: true, name: name });
            return;
          }
        }
        post({ type: 'opened', ok: false, name: name });
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    },

    // 返回会话列表（尝试点击 SPA 返回控件；找不到就回退重载）
    backToList: function () {
      var back = document.querySelector('[class*="back" i],[aria-label*="返回"]');
      if (back) { fireClick(back); post({ type: 'back', ok: true }); }
      else { location.reload(); post({ type: 'back', ok: true, reload: true }); }
    },

    // 引擎归位：互动会把这唯一的全局引擎带到 /video/*；不归位则聊天页永远抓不到 DOM
    // （实测：从 /video/x 导航回 /chat 后 convRows=2、无登录墙，SPA 自己恢复列表视图）
    ensureImHome: function () {
      try {
        if (location.pathname.indexOf('/chat') === 0) return true;
        location.href = 'https://www.douyin.com/chat';
        post({ type: 'home', ok: false, nav: true });
        return false;
      } catch (e) { return false; }
    },

    // ---- 推荐流数据源（用户 2026-09-26 定案：视频内容改从 PC 推荐页拿）----
    // 实测发现：精选页卡片元素直接带 data-aweme-id（19 位），比抓 DOM 文本/认 hash 类名稳定得多；
    // 且页面靠它自己跟服务器拿的推荐（同源 webid/设备指纹），就是我们原生 tab/feed 拿不到的那一路推荐。
    JINGXUAN_URL: 'https://www.douyin.com/jingxuan',

    // 拉一页推荐 id（scroll=true 时先滚到底触发加载再抓）
    fetchRecommendFeed: function (scroll) {
      var self = this;
      if (location.pathname.indexOf('/jingxuan') !== 0) {
        try {
          sessionStorage.setItem('__dywatch_action', JSON.stringify({ kind: 'feed', awemeId: 'feed', payload: !!scroll }));
        } catch (e) {}
        location.href = this.JINGXUAN_URL;
        post({ type: 'feed', phase: 'navigating' });
        return;
      }
      var step = function () {
        var els = document.querySelectorAll('[data-aweme-id]');
        var ids = [], seen = {};
        for (var i = 0; i < els.length; i++) {
          var id = els[i].getAttribute('data-aweme-id');
          if (!id || !/^\d{15,}$/.test(id) || seen[id]) continue;
          seen[id] = 1; ids.push(id);
        }
        post({ type: 'feed', ids: ids, docH: document.documentElement.scrollHeight });
      };
      if (!scroll) { step(); return; }
      try { window.scrollTo(0, document.documentElement.scrollHeight); } catch (e) {}
      setTimeout(step, 2500);
    },

    // ---- 视频互动（点赞/收藏）：复用本引擎开视频页，点页面自带按钮（页面 SDK 全包风控）----
    // data-e2e 语义锚点（2026-09-26 实探）：video-player-digg=点赞 feed-comment-icon=评论
    // video-player-collect=收藏 video-player-share=分享
    // ⚠️ 该交互区祖先为 display:none（immersive-player-switch-on-hide-interaction-area），
    //    元素 rect 全 0 → 真实坐标点击不可能命中，只能派发事件序列（实测有效）。
    likeVideo: function (awemeId, want) {
      ChatBridge.__doVideoAction('like', awemeId, want);
    },
    collectVideo: function (awemeId, want) {
      ChatBridge.__doVideoAction('collect', awemeId, want);
    },
    // 拉评论（DOM 抓取：昵称/文本/时间/赞数）
    fetchComments: function (awemeId) {
      ChatBridge.__doVideoAction('comments', awemeId, true);
    },
    // 加载更多评论（已在该视频页时直接滚评论区翻页，不再重新导航）
    loadMoreComments: function (awemeId) {
      if (location.href.indexOf('/video/' + awemeId) >= 0) return ChatBridge.__loadMoreComments();
      ChatBridge.__doVideoAction('commentsMore', awemeId, true);
    },
    // 发评论（激活 Draft.js 框 → 粘贴注入 → 枚举提交面）
    sendComment: function (awemeId, text) {
      ChatBridge.__doVideoAction('sendComment', awemeId, text);
    },

    // 状态指纹：类名 token 集合 + 计数文本。
    // 类名是构建期哈希（实测已赞标 = 多出一段如 Fw5w6T_O，整页重载后服务端仍给同一标记），
    // 故不硬编类名、只比差异；用排序后的 token 集合，免因 React 重渲染重排类名而误判。
    __trait: function (el) {
      if (!el) return null;
      var raw = el.className && el.className.baseVal !== undefined ? el.className.baseVal : (el.className || '');
      var tokens = String(raw).split(/\s+/).filter(function (s) { return s.length > 0; });
      return {
        cls: tokens.sort().join(' '),
        text: (el.textContent || '').replace(/\s+/g, ' ').trim()
      };
    },
    __inFlight: {},

    // 就绪门：页面 DOM 里按钮很早就存在，但 React onClick 要等组件挂载完才绑上去；
    // 绑上前点击=空操作（真机实测：App 路径 3 次点击窗口内未绑，窗口后才生效→误报"无响应"）。
    // 所以直接读节点上的 __reactProps$ 看 onClick 在不在，比猜等待时长可靠。
    __ready: function (el) {
      if (!el) return false;
      var keys = Object.keys(el);
      for (var i = 0; i < keys.length; i++) {
        if (keys[i].indexOf('__reactProps$') === 0) {
          var pr = el[keys[i]];
          return !!(pr && (pr.onClick || pr.onPointerDown || pr.onMouseDown));
        }
      }
      return false;
    },

    // 点击 + 以状态指纹变化判定是否生效（先等就绪门，再点，再宽窗口复查）
    __clickAndJudge: function (kind, e2e, want, tries) {
      var self = this;
      var btn = document.querySelector('[data-e2e="' + e2e + '"]');
      if (!btn) {
        if ((tries || 0) < 20) {
          return wait(400).then(function () { return self.__clickAndJudge(kind, e2e, want, (tries || 0) + 1); });
        }
        return Promise.resolve({ type: 'action', action: kind, ok: false, detail: '未找到按钮 ' + e2e });
      }
      if (!this.__ready(btn)) {
        // 组件未挂载完：不计入点击次数，最多等 12s
        if ((tries || 0) < 200) {
          return wait(400).then(function () { return self.__clickAndJudge(kind, e2e, want, (tries || 0) + 1); });
        }
        return Promise.resolve({ type: 'action', action: kind, ok: false, detail: '按钮事件始终未绑定（组件未就绪）' });
      }
      var before = this.__trait(btn);
      var attempt = tries || 0;
      fireClick(btn);
      return this.__waitChange(e2e, before, 14).then(function (now) {
        var changed = !!now && (now.cls !== before.cls || now.text !== before.text);
        if (changed) {
          return {
            type: 'action', action: kind, ok: true, want: want, changed: true, attempts: attempt + 1,
            count: before.text + '→' + now.text,
            detail: '状态已翻转'
          };
        }
        if (attempt < 3) {
          return wait(1200).then(function () { return self.__clickAndJudge(kind, e2e, want, attempt + 1); });
        }
        // 最后复查一次（宽窗口）：真机出现过"窗口后才发现服务端已记账"的情况
        return self.__waitChange(e2e, before, 14).then(function (late) {
          var lateChanged = !!late && (late.cls !== before.cls || late.text !== before.text);
          return {
            type: 'action', action: kind, ok: lateChanged, want: want, changed: lateChanged, attempts: 4,
            count: before.text + '→' + (late ? late.text : '?'),
            detail: lateChanged ? '状态已翻转（复查发现）' : '多次点击后状态仍未变'
          };
        });
      });
    },

    // 轮询等状态指纹变化（每 400ms 一次，最多 n 次）；变化即早退，避免误重试把刚点的赞翻回去
    __waitChange: function (e2e, before, n) {
      var self = this;
      return new Promise(function (res) {
        var i = 0;
        (function step() {
          var now = self.__trait(document.querySelector('[data-e2e="' + e2e + '"]'));
          var diff = !!now && (now.cls !== before.cls || now.text !== before.text);
          if (diff || i++ >= n) { res(now); return; }
          setTimeout(step, 400);
        })();
      });
    },

    __doVideoAction: function (kind, awemeId, payload) {
      var self = this;
      var e2e = kind === 'like' ? 'video-player-digg' : 'video-player-collect';
      // 不在该视频页 → 跳转；待办动作存 sessionStorage（同域跨页存续），下页注入后自续
      if (location.href.indexOf('/video/' + awemeId) < 0 && location.href.indexOf('modal_id=' + awemeId) < 0) {
        try {
          sessionStorage.setItem('__dywatch_action', JSON.stringify({ kind: kind, awemeId: awemeId, payload: payload }));
        } catch (e) {}
        location.href = 'https://www.douyin.com/video/' + awemeId;
        post({ type: 'action', action: kind, phase: 'navigating' });
        return;
      }
      if (kind === 'comments') return this.__scrapeComments();
      if (kind === 'commentsMore') return this.__loadMoreComments();
      if (kind === 'sendComment') return this.__doSendComment(payload);
      if (kind === 'feed') return this.fetchRecommendFeed(!!payload);
      var key = kind + ':' + awemeId;
      if (this.__inFlight[key]) {
        post({ type: 'action', action: kind, ok: false, skipped: true, detail: '上一次同动作仍在执行中，已忽略' });
        return;
      }
      this.__inFlight[key] = true;
      this.__clickAndJudge(kind, e2e, payload, 0).then(function (r) {
        delete self.__inFlight[key];
        post(r);
      });
    },

    // ---- 评论：增量分页 ----
    // 实测（2026-09-26）：评论挂在 [class*="route-scroll-container"] 里，滚一屏就能从 16 条涨到 76 条。
    // 旧实现只抓「当前 DOM 已有」的一次性快照、且等满 30 秒才 post 一次
    // → 用户永远只见五六条、滑不出更多、还慢。现在改成：一有内容立刻 post，滚动一页追发新的一批。
    __commentScroller: function () {
      return document.querySelector('[class*="route-scroll-container"]') || document.scrollingElement;
    },

    // 去重键：DOM 里没有稳定的评论 id，用 昵称+正文前缀+时间 兜住
    __ck: function (it) { return it.name + '|' + it.text.slice(0, 60) + '|' + it.time; },

    // 抓当前 DOM 里渲染出来的评论（语义锚点 + 列分类）
    __collectComments: function () {
      var items = [];
      var wraps = document.querySelectorAll('[class*="comment-item-info-wrap"]');
      for (var i = 0; i < wraps.length; i++) {
        var wrap = wraps[i];
        var col = wrap.parentElement;
        if (!col) continue;
        var name = (wrap.textContent || '').replace(/\s+/g, ' ').trim().replace(/\.{2,}$/, '');
        var text = '', time = '', likes = '';
        for (var c = 0; c < col.children.length; c++) {
          var el = col.children[c];
          var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
          // 排除昵称列：querySelector 只查后代不查自身，光靠它会把昵称列漏进来，
          // 而昵称+「…」比正文长→被当成正文（真机实测：自己的评论只显了个账号名）
          if (el === wrap || wrap.contains(el) || el.contains(wrap)) continue;
          if (el.querySelector('[class*="comment-item-info-wrap"]')) continue;
          if (el.querySelector('[class*="comment-item-stats-container"]')) {
            var sp = el.querySelector('p span');
            likes = sp ? (sp.textContent || '').trim() : '';
            continue;
          }
          // 时间形如「刚刚」「1分钟前」「2月前·山西」「09-20」；单位必须含「月/年」，漏了会逐条漏配
          var mt = t.match(/^(刚刚|\d+\s?(秒|分钟|小时|天|周|月|年)前|\d{1,2}-\d{1,2})/);
          if (mt) { time = mt[1]; continue; }
          if (t.length > text.length) text = t;
        }
        // 正文兜底：真机见过某些行昵称/时间/赞数都在、正文却空（图片评论，或正文挂在更深层级
        // 而不是 col 的直接子节点）。先往深层再找一次最长文本，仍拿不到就标图片，
        // 两头都空的行直接丢掉——给用户一张只有名字的空白卡片比少一行更糟。
        if (!text) {
          var deepest = '';
          col.querySelectorAll('div,span,p').forEach(function (e) {
            if (e.children.length) return;
            // 排除昵称列与赞/分享/回复那一列的后代（否则会把"2.1万分享回复"当正文）
            if (wrap.contains(e) || e.contains(wrap)) return;
            var statsCol = col.querySelector('[class*="comment-item-stats-container"]');
            if (statsCol && statsCol.contains(e)) return;
            var s = (e.textContent || '').replace(/\s+/g, ' ').trim();
            if (!s) return;
            if (/^(刚刚|\d+\s?(秒|分钟|小时|天|周|月|年)前|\d{1,2}-\d{1,2})/.test(s)) return;
            if (/^\d+(\.\d+)?[万千]?$/.test(s)) return;         // 纯赞数
            if (/(分享|回复)$/.test(s) && s.length < 12) return; // 操作按钮文本
            if (s.length > deepest.length) deepest = s;
          });
          text = deepest;
        }
        if (!text && col.querySelector('img[src*="aweme-image"], img[src*="tos-cn"], img[class*="comment"]')) {
          text = '［图片］';
        }
        if (name || text) items.push({ name: name, text: text, time: time, likes: likes });
      }
      return items;
    },

    // 只把没发过的那部分推给原生；返回新增条数
    __drainComments: function (type) {
      if (!this.__seenCmts) this.__seenCmts = {};
      var all = this.__collectComments(), fresh = [];
      for (var i = 0; i < all.length; i++) {
        var k = this.__ck(all[i]);
        if (this.__seenCmts[k]) continue;
        this.__seenCmts[k] = 1;
        fresh.push(all[i]);
      }
      if (!fresh.length) return 0;
      var sc = this.__commentScroller();
      var atEnd = sc ? (sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 40) : true;
      post({ type: type, items: fresh, total: Object.keys(this.__seenCmts).length, atEnd: !!atEnd });
      return fresh.length;
    },

    // 首次拉取：清零账本、回到顶部，之后每 500ms 试一次，一有评论就发（不再干等 30 秒）
    __scrapeComments: function () {
      var self = this;
      this.__seenCmts = {};
      var sc = this.__commentScroller();
      if (sc) sc.scrollTop = 0;
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        if (self.__drainComments('comments') || tries > 30) clearInterval(timer);
      }, 500);
    },

    // 加载更多：一次滚约 3 屏，每屏把新渲染出来的追发出去。
    // ⚠️ 只有滚动位置真到底才允许报 atEnd —— 早先"一次没抓到就 end:true"会把还有评论的视频误标成已到底。
    __loadMoreComments: function () {
      var self = this;
      var sc = this.__commentScroller();
      if (!sc) { post({ type: 'commentsMore', items: [], end: true, atEnd: true }); return; }
      var atBottom = function () { return sc.scrollTop + sc.clientHeight >= sc.scrollHeight - 40; };
      var round = 0;
      var timer = setInterval(function () {
        round++;
        self.__drainComments('commentsMore');
        if (round % 3 === 1) sc.scrollTop = sc.scrollTop + sc.clientHeight * 0.85;
        if (round >= 9 || (atBottom() && round >= 6)) {
          clearInterval(timer);
          self.__drainComments('commentsMore');
          post({ type: 'commentsMore', items: [], end: true, atEnd: atBottom() });
        }
      }, 700);
    },


    // 编辑器是否有内容（Draft 的 [data-text] 镜像 + textContent 双通道；DOM 直改不可信）
    __editorText: function (ed) {
      if (!ed) return '';
      var mirror = ed.querySelector('[data-text="true"]');
      var raw = (mirror && mirror.textContent ? mirror.textContent : ed.textContent) || '';
      return raw.replace(/[\u200b\u200c\u200d\ufeff]/g, '').trim();
    },

    // 发评论：激活 Draft.js 框 → 粘贴注入 → 枚举提交面（right-ct 内可点 span 逐个试）→ 回车兜底
    // 判定 = 编辑器清空（清空即已被页面消费=已发出）；不再用「未找到即失败」的假阴性口径
    __doSendComment: function (text) {
      var self = this;
      // 评论框要等 SPA 渲染（实测：跳页后 4s 才起页面、评论框还没挂载，旧版直接报"未找到评论框"）
      var boxTries = 0;
      var boxTimer = setInterval(function () {
        boxTries++;
        var b = document.querySelector('[class*="comment-input-inner-container"]');
        if (!b) {
          if (boxTries > 40) {
            clearInterval(boxTimer);
            post({ type: 'action', action: 'sendComment', ok: false, detail: '等 20s 仍无评论框（页面未渲染完）' });
          }
          return;
        }
        clearInterval(boxTimer);
        fireClick(b);
        self.__doSendCommentEdit(text);
      }, 500);
    },

    __doSendCommentEdit: function (text) {
      var self = this;
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        var editor = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
        if (editor || tries > 25) {
          clearInterval(timer);
          if (!editor) { post({ type: 'action', action: 'sendComment', ok: false, detail: '编辑器未出现' }); return; }
          editor.focus();
          try {
            var dt = new DataTransfer();
            dt.setData('text/plain', text);
            editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
          } catch (e) {}
          wait(400).then(function () {
            // 粘贴没进 state 才补 execCommand（它走真实 input 事件链，不会让 state 脱钩）
            if (self.__editorText(editor).indexOf(text) < 0) {
              try { document.execCommand('insertText', false, text); } catch (e) {}
            }
            return wait(400);
          }).then(function () {
            var injected = self.__editorText(editor);
            if (injected.indexOf(text) < 0) {
              post({ type: 'action', action: 'sendComment', ok: false, detail: '文本未进入编辑器（state=' + injected + '）' });
              return;
            }
            // 提交面枚举（真机实测纠版）：right-ct 里 3 个 span = 表情 / @提及 / 发送。
            // 真发送钮在编辑器为空时是 cursor:auto + opacity:1，表情与@ 钮是 cursor:pointer + opacity 0.44；
            // 旧版只收 cursor:pointer → 把 @ 提及钮当候选点了→它往编辑器里插了一个“@”（用户反馈的尾@根因）。
            // 现按 DOM 倒序（发送恒在末尾）优先，且不再以 cursor 为硬性门槛。
            var nodes = document.querySelectorAll('[class*="commentInput-right-ct"] span, [class*="commentInput-right-ct"] button');
            var cands = [];
            for (var i = nodes.length - 1; i >= 0; i--) {
              var cs = getComputedStyle(nodes[i]);
              var op = parseFloat(cs.opacity || '1');
              if (cs.cursor === 'pointer' || op >= 0.9) cands.push(nodes[i]);
            }
            post({ type: 'action', action: 'sendComment', phase: 'submitting', cands: cands.length, injected: injected });
            return self.__trySubmit(editor, cands, 0);
          }).then(function (r) {
            if (r) post(r);
          });
        }
      }, 400);
    },

    // 依次尝试：每个候选钮（pointer+mouse 事件全序列）→ 回车；每次试完看编辑器是否清空
    __trySubmit: function (editor, cands, idx) {
      var self = this;
      if (this.__editorText(editor).length === 0) {
        return Promise.resolve({ type: 'action', action: 'sendComment', ok: true, detail: '已发送（编辑器已清空）' });
      }
      if (idx < cands.length) {
        var beforeText = self.__editorText(editor);
        fireClick(cands[idx]);
        return wait(1500).then(function () {
          var nowText = self.__editorText(editor);
          if (nowText.length === 0) {
            return { type: 'action', action: 'sendComment', ok: true, via: 'click#' + idx, detail: '已发送（点提交钮第' + (cands.length - idx) + '个）' };
          }
          // 文本变长 = 点到了“插入型”钮（表情/@ 提及）→ 把插入的字符删掉再试下一个，
          // 绝不带着它提交（这就是用户看到尾@ 的那一步）
          if (nowText.length > beforeText.length) {
            var extra = nowText.length - beforeText.length;
            editor.focus();
            for (var d = 0; d < extra; d++) {
              try { document.execCommand('delete', false, null); } catch (e) {}
            }
            return wait(400).then(function () {
              return self.__trySubmit(editor, cands, idx + 1);
            });
          }
          return self.__trySubmit(editor, cands, idx + 1);
        });
      }
      // 回车兜底（私信侧实测有效；Draft 可能绑在 keydown 上）
      ['keydown', 'keypress', 'keyup'].forEach(function (type) {
        editor.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      });
      return wait(1500).then(function () {
        var left = self.__editorText(editor);
        return {
          type: 'action', action: 'sendComment', ok: left.length === 0,
          triedClicks: cands.length,
          detail: left.length === 0 ? '已发送（回车）' : ('提交未生效：编辑器仍有内容「' + left.slice(0, 12) + '」，已试 ' + cands.length + ' 个候选钮')
        };
      });
    },

    // 发送文本：粘贴注入 → 回车（2026-09-25 实测：消息真实送达，编辑器自动清空）
    // 防抖：同文 2.5s 内重复触发只发一次（真机踩过一次点击两条的坑）
    sendText: function (text) {
      try {
        var now = Date.now();
        if (window.__ChatBridgeLastSend && window.__ChatBridgeLastSend.text === text
            && now - window.__ChatBridgeLastSend.ts < 2500) {
          post({ type: 'sent', ok: true, note: '防抖跳过（重复触发）' });
          return;
        }
        window.__ChatBridgeLastSend = { text: text, ts: now };
        var editor = document.querySelector('[class~="messageEditorinputArea"][contenteditable="true"]');
        if (!editor) {
          post({ type: 'error', message: '未找到聊天输入框（引擎可能不在私信页，会自动归位重试）' });
          ChatBridge.ensureImHome();
          return;
        }
        editor.focus();
        try {
          var dt = new DataTransfer();
          dt.setData('text/plain', text);
          editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
        } catch (e) {}
        setTimeout(function () {
          if ((editor.textContent || '').indexOf(text) < 0) {
            try { document.execCommand('insertText', false, text); } catch (e) {}
          }
          setTimeout(function () {
            ['keydown', 'keypress', 'keyup'].forEach(function (type) {
              editor.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
            });
            setTimeout(function () {
              var cleared = (editor.textContent || '').replace(/[\u200b\u200c\u200d\ufeff]/g, '').trim().length === 0;
              post({ type: 'sent', ok: cleared, note: cleared ? '已发送（编辑器已清空）' : '编辑器未清空，可能未发出' });
            }, 600);
          }, 200);
        }, 200);
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    },

    // 拉取消息（新→旧序；dir: out=我方 in=对方）
    fetchMessages: function () {
      try {
        var items = [];
        var boxes = document.querySelectorAll('[class*="messageMessageBoxmessageBox"]');
        for (var i = 0; i < boxes.length; i++) {
          var b = boxes[i];
          var mine = !!b.querySelector('[class*="messageMessageBoxcontentBox"][class*="isFromMe"]');
          var textEl = b.querySelector('[class*="TextMessageTexttextInnerContent"]')
            || b.querySelector('[class*="MessageItemTextbubbleTextContent"]');
          var text = textEl ? (textEl.textContent || '').replace(/\s+/g, ' ').trim() : '';
          if (!text) continue;
          var timeEl = b.querySelector('[class*="MessageBoxTimetimeLayout"]');
          var timeText = timeEl ? (timeEl.textContent || '').trim() : '';
          items.push({ dir: mine ? 'out' : 'in', text: text, time: timeText });
        }
        post({ type: 'messages', items: items });
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
    }
  };

  // 文档令牌：只在桥真正启动成功的那一份文档里生成。原生侧用它区分
  // "同一个文档的重复 onPageFinished"和"真的换页/刷新了"，避免重复通知导致同一批数据回多份。
  window.__dywatch_doc = String(Date.now()) + '-' + String(Math.random()).slice(2, 9);

  post({ type: 'ready' });

  // 自续机制：跨页待办动作（sessionStorage 同域存续，桥每次注入自查）
  try {
    var raw = sessionStorage.getItem('__dywatch_action');
    if (raw) {
      sessionStorage.removeItem('__dywatch_action');
      var a = JSON.parse(raw);
      if (a && a.kind && a.awemeId) {
        setTimeout(function () {
          if (a.kind === 'like') ChatBridge.likeVideo(a.awemeId, a.payload);
          else if (a.kind === 'collect') ChatBridge.collectVideo(a.awemeId, a.payload);
          else if (a.kind === 'comments') ChatBridge.fetchComments(a.awemeId);
          else if (a.kind === 'sendComment') ChatBridge.sendComment(a.awemeId, a.payload);
          else if (a.kind === 'feed') ChatBridge.fetchRecommendFeed(!!a.payload);
        }, 800);
      }
    }
  } catch (e) {}
})();
