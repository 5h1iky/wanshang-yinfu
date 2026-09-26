// 聊天 JS 桥 v3（架构 A）：抖音私信页 DOM 校准版。
// 2026-09-25 CDP 真机标定（选择器均有实证）：
//   会话行   div.conversationConversationItemwrapper（名字 .conversationConversationItemtitle
//            时间 .ConversationItemTagNextToTitletimeStr 描述 .ConversationItemDescwrapper）
//   消息列表 div.messageMessageListlist / 气泡 div.messageMessageBoxmessageBox
//   方向     气泡内 div.messageMessageBoxcontentBox.isFromMe = 我方
//   文本     span.TextMessageTexttextInnerContent  时间 div.MessageBoxTimetimeLayout
//   输入框   div.messageEditorinputArea[contenteditable=true]（Slate/EditorKit）
//   发送     粘贴注入 + 回车（实测有效）；兜底点击 inputAction 末尾 svg
(function () {
  if (window.ChatBridge && window.ChatBridge.__v3) return;

  function post(obj) {
    try {
      if (window.AndroidBridge && AndroidBridge.onBridgeEvent) {
        AndroidBridge.onBridgeEvent(JSON.stringify(obj));
      }
    } catch (e) {}
  }

  function fireClick(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (type) {
      el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
    });
  }

  function isLoginWall() {
    var t = (document.body && document.body.innerText) || '';
    return t.indexOf('扫码登录') >= 0 || t.indexOf('登录后免费畅享') >= 0;
  }

  window.ChatBridge = {
    __v3: true,

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
      try {
        var items = [];
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
          items.push({ key: String(i), name: name, lastMsg: desc.replace(/\s+/g, ' ').trim(), time: time.trim() });
        }
        post({ type: 'conversations', items: items });
      } catch (e) {
        post({ type: 'error', message: String(e) });
      }
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

    // ---- 视频互动（点赞/收藏）：复用本引擎开视频页，点页面自带按钮（页面 SDK 全包风控）----
    // data-e2e 语义锚点（2026-09-26 实探）：video-player-digg=点赞 feed-comment-icon=评论
    // video-player-collect=收藏 video-player-share=分享
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
    // 发评论（激活 Draft.js 框 → 粘贴注入 → 回车）
    sendComment: function (awemeId, text) {
      ChatBridge.__doVideoAction('sendComment', awemeId, text);
    },
    __doVideoAction: function (kind, awemeId, payload) {
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
      if (kind === 'comments') return ChatBridge.__scrapeComments();
      if (kind === 'sendComment') return ChatBridge.__doSendComment(payload);
      // 点赞/收藏：等按钮出现（SPA 异步渲染，最多 8s）
      var tries = 0;
      var timer = setInterval(function () {
        var btn = document.querySelector('[data-e2e="' + e2e + '"]');
        tries++;
        if (btn || tries > 20) {
          clearInterval(timer);
          if (!btn) { post({ type: 'action', action: kind, ok: false, detail: '未找到按钮' }); return; }
          var before = (btn.textContent || '').replace(/\s+/g, ' ').trim();
          fireClick(btn);
          setTimeout(function () {
            var after = (btn.textContent || '').replace(/\s+/g, ' ').trim();
            post({ type: 'action', action: kind, ok: true, before: before, after: after, changed: before !== after });
          }, 1000);
        }
      }, 400);
    },

    // 评论抓取：语义锚点（comment-item-info-wrap / comment-item-stats-container）+ 列分类
    // ⚠️ 评论区懒加载（实测 10~20 秒才渲染）→ 轮询窗口放宽到 ~30 秒
    __scrapeComments: function () {
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        var wraps = document.querySelectorAll('[class*="comment-item-info-wrap"]');
        if (wraps.length || tries > 40) {
          clearInterval(timer);
          var items = [];
          for (var i = 0; i < wraps.length; i++) {
            var col = wraps[i].parentElement;
            if (!col) continue;
            var name = (wraps[i].textContent || '').replace(/\s+/g, ' ').trim().replace(/\.{2,}$/, '');
            var text = '', time = '', likes = '';
            for (var c = 0; c < col.children.length; c++) {
              var el = col.children[c];
              var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
              if (el.querySelector('[class*="comment-item-info-wrap"]')) continue;
              if (el.querySelector('[class*="comment-item-stats-container"]')) {
                var sp = el.querySelector('p span');
                likes = sp ? (sp.textContent || '').trim() : '';
                continue;
              }
              if (/\d+(天|小时|分钟|秒)前|^刚刚|^\d{2}-\d{2}/.test(t)) { time = t; continue; }
              if (t.length > text.length) text = t;
            }
            if (name || text) items.push({ name: name, text: text, time: time, likes: likes });
          }
          post({ type: 'comments', items: items });
        }
      }, 750);
    },

    // 发评论：激活输入区 → Draft.js 粘贴注入 → 点发送图标（回车兜底）→ 校验编辑器清空
    __doSendComment: function (text) {
      var box = document.querySelector('[class*="comment-input-inner-container"]');
      if (!box) { post({ type: 'action', action: 'sendComment', ok: false, detail: '未找到评论框' }); return; }
      fireClick(box);
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        var editor = document.querySelector('.public-DraftEditor-content[contenteditable="true"]');
        if (editor || tries > 12) {
          clearInterval(timer);
          if (!editor) { post({ type: 'action', action: 'sendComment', ok: false, detail: '编辑器未出现' }); return; }
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
              function cleared() {
                return (editor.textContent || '').replace(/[\u200b\u200c\u200d\ufeff]/g, '').trim().length === 0;
              }
              // 主通道：点发送图标（Draft.js 评论框不吃回车）
              var icon = document.querySelector('[class*="commentInput-right-ct"] > div > span:last-child');
              if (icon) fireClick(icon);
              setTimeout(function () {
                if (cleared()) {
                  post({ type: 'action', action: 'sendComment', ok: true, detail: '已发送' });
                  return;
                }
                // 兜底：回车提交
                ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                  editor.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                });
                setTimeout(function () {
                  post({
                    type: 'action', action: 'sendComment', ok: cleared(),
                    detail: cleared() ? '已发送(回车)' : '编辑器未清空，可能未发出'
                  });
                }, 1500);
              }, 1200);
            }, 250);
          }, 250);
        }
      }, 400);
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
          post({ type: 'error', message: '未找到聊天输入框' });
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
        }, 800);
      }
    }
  } catch (e) {}
})();
