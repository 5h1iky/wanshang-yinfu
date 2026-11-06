package com.dywatch.app.chat;

// 原生 API 聊天通道（0.9.1 起）。
//
// 这是「聊天全链路 API 化」的落点：会话列表 / 消息历史 / 发送 全部走 imapi 原生 HTTP，
// 不再需要那个常驻的隐藏 WebView 去抓 DOM。协议基线见 `调研\19`、`调研\20`。
//
// 与另外两条通道的关系（{@link ChatPorts} 里按开关选）：
//   · ApiChatPort   ← 本类：纯 HTTP，快、省内存、不受页面改版影响
//   · WebViewChatPort ← 现有 DOM 抓取通道，保留为默认/回落
//   · KernelChatPort  ← GeckoView 通道（kernel 变体）
//
// ⚠️ 设计上**只在必要时用 WebView**：
//   读（列表/消息）纯 cookie 就够 → 一次请求几百毫秒；
//   发（需要 ticket-guard 凭证）→ 首次/过期时用一个临时 WebView 提取一次凭证（约 10s），
//   提完立刻销毁；凭证缓存 30 分钟。
//
// ⚠️ 与 UI 的契约（{@link ChatPort} + {@link ChatEngine.Listener}）完全不变，
//    所以两个 Activity 一行都不用改。

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.dywatch.app.R;
import com.dywatch.app.chat.api.CredExtractor;
import com.dywatch.app.chat.api.ImApi;
import com.dywatch.app.chat.api.ImCreds;
import com.dywatch.app.chat.api.ImModels;
import com.dywatch.app.chat.api.ImSend;
import com.dywatch.app.chat.api.ImUserInfo;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.util.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiChatPort implements ChatPort {

    private static final String TAG = "apichat";

    /** 读接口有限频：轮询间隔不能被 UI 的 5s 拉得太密（ImApi 内部还有 3s 硬节流） */
    private static final long CONV_CACHE_MS = 4000L;

    private final Activity mActivity;
    private final ChatEngine.Listener mOuter;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private ImApi mApi;
    /** 当前会话（进聊天页时由 openConversation 设） */
    private String mConvId;
    private long mConvShortId;
    private String mConvTicket;
    /** 当前会话最近一次成功拿到的消息（用于历史与去重） */
    private final List<ImModels.Msg> mMsgs = new ArrayList<>();
    /** 昵称缓存：sec_uid → 昵称（会话列表渲染用） */
    private final java.util.Map<String, String> mNick = new java.util.HashMap<>();
    /**
     * 会话名 → 会话 id（**静态**，跨页面共享）。
     *
     * 为什么必须静态：会话列表页和聊天页各自 new 一个 ApiChatPort，
     * 列表页拿到的昵称在聊天页那个实例里看不到；而 UI 传给 openConversation 的是**名字**。
     * 没有这张表，聊天页就只能拿 uid 去匹配名字 → "找不到会话"（2026-11-07 真机踩到）。
     */
    private static final java.util.Map<String, String> sNameToId = new java.util.HashMap<>();
    /** 凭证提取中的回调队列（同时只跑一个提取器） */
    private CredExtractor mExtracting;
    private final List<Runnable> mCredWaiters = new ArrayList<>();
    private boolean mDestroyed;

    ApiChatPort(Activity activity, ChatEngine.Listener listener) {
        mActivity = activity;
        mOuter = listener;
        mApi = new ImApi()
                .setCookie(LoginManager.getCookies(activity))
                .setScreen(viewportW(), viewportH());
    }

    private int viewportW() {
        try {
            return mActivity.getResources().getConfiguration().screenWidthDp;
        } catch (Throwable t) {
            return 393;
        }
    }

    private int viewportH() {
        try {
            return mActivity.getResources().getConfiguration().screenHeightDp;
        } catch (Throwable t) {
            return 873;
        }
    }

    // ---------- ChatPort ----------

    @Override
    public void ensureImHome() {
        // 原生通道没有"页面"，归位是空动作。就绪回调仍要发一次，
        // 否则 UI 的 onEngineReady 分支（打开会话 / 开始轮询）永远不会触发。
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (mDestroyed) return;
                if (mOuter != null) mOuter.onEngineReady();
            }
        });
    }

    @Override
    public void rebind() {
        // 没有页面监听者需要重绑；但 cookie 可能被刷新过 → 同步一次
        mApi.setCookie(LoginManager.getCookies(mActivity));
    }

    @Override
    public void attach(Activity activity) {
        // 原生通道不需要挂宿主视图
    }

    @Override
    public void detach(Activity activity) {
        // 同上
    }

    @Override
    public void fetchConversations() {
        final long started = System.currentTimeMillis();
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ImApi.Init init = mApi.init();
                    if (!init.ok()) {
                        postError("会话列表: " + init.error);
                        return;
                    }
                    fillNicknames(init.convs);

                    final List<Conversation> out = new ArrayList<>();
                    for (ImModels.Conv c : convs(init)) {
                        // 会话名 → id 的映射也在这里建（聊天页要用它反查）
                        String dn = c.displayName();
                        if (!dn.isEmpty()) sNameToId.put(dn, c.id);
                        out.add(new Conversation(
                                c.id,
                                dn,
                                summaryOf(c.lastText()),
                                timeTextOf(c.lastTimeMs())));
                    }
                    AppLog.i(TAG, "会话 " + out.size() + " 个（" + (System.currentTimeMillis() - started) + "ms）");
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mDestroyed && mOuter != null) mOuter.onConversations(out);
                        }
                    });
                    // 顺手校验登录态：能拉到列表就是有效会话（比再打一个接口可靠）
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mDestroyed && mOuter != null) mOuter.onAuth(true, "");
                        }
                    });
                } catch (Throwable t) {
                    postError("会话列表: " + friendly(t));
                }
            }
        });
    }

    private static List<ImModels.Conv> convs(ImApi.Init init) {
        return init.convs;
    }

    /**
     * 补昵称（init 只给 uid / sec_uid，没有昵称）。
     *
     * 三条路径都要用同一份逻辑：列表页、聊天页（打开会话）、上翻历史。
     * 抽出来是因为 2026-11-07 真机这里踩了两次坑：
     *   ① 第一次 data 解析没兼容数组形状 → 昵称全丢，列表显示 uid；
     *   ② 聊天页是新实例，列表页查到的昵称它看不到 → 按名字反查会话失败。
     */
    private void fillNicknames(List<ImModels.Conv> list) {
        List<String> secs = new ArrayList<>();
        for (ImModels.Conv c : list) {
            if (!c.peerSecUid.isEmpty()) {
                String hit = mNick.get(c.peerSecUid);
                if (hit != null) c.nickname = hit;
                else secs.add(c.peerSecUid);
            }
        }
        if (secs.isEmpty()) return;
        try {
            String cookie = LoginManager.getCookies(mActivity);
            java.util.Map<String, ImUserInfo.Profile> p =
                    new ImUserInfo().fetch(cookie, ImUserInfo.uifidFromCookie(cookie), secs);
            for (ImModels.Conv c : list) {
                ImUserInfo.Profile prof = p.get(c.peerSecUid);
                if (prof != null && prof.nickname != null && !prof.nickname.isEmpty()) {
                    c.nickname = prof.nickname;
                    mNick.put(c.peerSecUid, prof.nickname);
                }
            }
        } catch (Throwable t) {
            // 昵称缺失不该阻断列表（退化成显示 uid）
            AppLog.i(TAG, "昵称补全失败（不阻断）: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void fetchMessages() {
        final String convId = mConvId;
        if (convId == null || convId.isEmpty()) {
            // 还没进会话：给一个空结果，让 UI 别一直转圈
            return;
        }
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ImApi.Init init = mApi.init();
                    if (!init.ok()) {
                        postError("消息: " + init.error);
                        return;
                    }
                    ImModels.Conv c = init.find(convId);
                    if (c == null) {
                        // 会话不在本次列表里（可能被删/退群）：给空而不是报错
                        postMessages(new ArrayList<ChatMessage>());
                        return;
                    }
                    // ticket 会轮换：每次都用最新的（发送要用）
                    mConvShortId = c.shortId;
                    mConvTicket = c.ticket;

                    mMsgs.clear();
                    mMsgs.addAll(c.recent);
                    postMessages(toUi(c.recent, init.selfUid));
                } catch (Throwable t) {
                    postError("消息: " + friendly(t));
                }
            }
        });
    }

    @Override
    public void openConversation(final String name) {
        // UI 传的是会话名（与 WebView 通道一致的入参）。这里按名字回查会话 id。
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // ⚠️ 先用列表页留下的映射直查（省一次请求，也避免"昵称还没补全就按名字匹配"）
                    String known = name == null ? null : sNameToId.get(name);
                    ImApi.Init init = mApi.init();
                    if (!init.ok()) {
                        postError("打开会话: " + init.error);
                        return;
                    }
                    fillNicknames(init.convs);

                    ImModels.Conv hit = null;
                    if (known != null) hit = init.find(known);
                    if (hit == null) {
                        for (ImModels.Conv c : init.convs) {
                            String dn = c.displayName();
                            if (dn != null && dn.equals(name)) {
                                hit = c;
                                break;
                            }
                            // 名字可能被 UI 截断/带后缀，再做一次前缀匹配
                            if (hit == null && dn != null && !dn.isEmpty()
                                    && (dn.startsWith(name) || name.startsWith(dn))) {
                                hit = c;
                            }
                        }
                    }
                    if (hit == null) {
                        postError("找不到会话「" + name + "」");
                        return;
                    }
                    mConvId = hit.id;
                    mConvShortId = hit.shortId;
                    mConvTicket = hit.ticket;
                    mMsgs.clear();
                    mMsgs.addAll(hit.recent);
                    AppLog.i(TAG, "进入会话 short=" + hit.shortId + " recent=" + hit.recent.size()
                            + " self=" + init.selfUid);
                    postMessages(toUi(hit.recent, init.selfUid));
                } catch (Throwable t) {
                    postError("打开会话: " + friendly(t));
                }
            }
        });
    }

    @Override
    public boolean sendText(final String text, final SendCallback cb) {
        if (text == null || text.trim().isEmpty()) return false;
        if (mConvId == null || mConvId.isEmpty()) {
            // 还没进会话：明确告诉 UI 这次没发（不要显示气泡）
            postError("还没进入会话，无法发送");
            return false;
        }
        // 凭证：先用缓存，过期/缺失则临时提一次（提取要几秒，先让 UI 知道"在发"）
        ImCreds creds = ImCreds.load(mActivity);
        if (creds.fresh()) {
            doSend(text, creds, cb);
            return true;
        }
        extractCredsThen(new Runnable() {
            @Override
            public void run() {
                ImCreds fresh = ImCreds.load(mActivity);
                if (!fresh.complete()) {
                    if (cb != null) cb.onSendResult(false, "发送凭证获取失败（请打开一次私信页后重试）", text);
                    return;
                }
                doSend(text, fresh, cb);
            }
        });
        return true;
    }

    private void doSend(final String text, final ImCreds creds, final SendCallback cb) {
        final String convId = mConvId;
        final long shortId = mConvShortId;
        final String ticket = mConvTicket;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ImSend send = new ImSend().setCookie(LoginManager.getCookies(mActivity));
                    final ImSend.Result r = send.sendText(convId, shortId, ticket, creds, text);
                    AppLog.i(TAG, "发送 " + r);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mDestroyed) return;
                            // ⚠️ 2026-11-07 审计 H4：把真实结果回给 UI（原来只写日志，
                            //    UI 无论成败都显示"我"的气泡，用户会以为已经发出去了）。
                            if (cb != null) {
                                if (r.ok) {
                                    cb.onSendResult(true, "", text);
                                } else if (!r.decision.isEmpty()) {
                                    // 有风控判定：账号级信号，必须让用户看到
                                    cb.onSendResult(false, "发送被服务端拦截（" + r.decision + "）", text);
                                } else {
                                    cb.onSendResult(false,
                                            "发送失败: " + (r.message.isEmpty() ? ("code " + r.code) : r.message), text);
                                }
                            }
                            if (r.ok) {
                                if (mOuter != null) mOuter.onAuth(true, "");
                                // 立刻刷新一次，让服务端真数据替换本地回声
                                fetchMessages();
                            }
                        }
                    });
                } catch (Throwable t) {
                    final String why = friendly(t);
                    AppLog.i(TAG, "发送异常: " + why);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!mDestroyed && cb != null) cb.onSendResult(false, "发送失败: " + why, text);
                        }
                    });
                }
            }
        });
    }

    @Override
    public boolean isPatchApplied() {
        // 原生通道不需要 JS 语法补丁 —— 恒 true，让提示状态机直接跳过"内核过旧"分支
        return true;
    }

    @Override
    public void reloadIm() {
        // 网络恢复：重新拉一次（原生通道没有可重载的页面）
        fetchConversations();
    }

    @Override
    public void setListenerDetached(ChatEngine.Listener listener) {
        mDestroyed = true;
        mMain.removeCallbacksAndMessages(null);
        if (mExtracting != null) {
            try {
                mExtracting.destroy();
            } catch (Throwable ignored) {
            }
            mExtracting = null;
        }
        mIo.shutdownNow();
    }

    /** 历史消息上翻（供未来的"加载更多"用；当前 UI 还没接） */
    void loadMoreHistory() {
        if (mConvId == null || mMsgs.isEmpty()) return;
        final long before = mMsgs.get(0).indexInConversation;
        final String convId = mConvId;
        final long shortId = mConvShortId;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<ImModels.Msg> older = mApi.history(convId, shortId, before);
                    if (older.isEmpty()) return;
                    mMsgs.addAll(0, older);
                    postMessages(toUi(mMsgs, 0));
                } catch (Throwable t) {
                    AppLog.i(TAG, "历史加载失败: " + friendly(t));
                }
            }
        });
    }

    // ---------- 凭证提取 ----------

    /**
     * 需要一个"新鲜凭证"时调它。同一个实例内并发请求会排队，只跑一次提取。
     */
    private void extractCredsThen(final Runnable then) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mCredWaiters.add(then);
                if (mExtracting != null) return;   // 已在提取，等它完成
                AppLog.i(TAG, "开始提取发送凭证（临时 WebView）");
                final CredExtractor ex = new CredExtractor(mActivity);
                mExtracting = ex;
                ex.extract(new CredExtractor.Callback() {
                    @Override
                    public void onResult(boolean ok, ImCreds creds, String why) {
                        mExtracting = null;
                        if (!ok) AppLog.i(TAG, "凭证提取失败: " + why);
                        List<Runnable> todo = new ArrayList<>(mCredWaiters);
                        mCredWaiters.clear();
                        for (Runnable r : todo) r.run();
                    }
                });
            }
        });
    }

    // ---------- 结果投递 ----------

    private void postMessages(final List<ChatMessage> list) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (!mDestroyed && mOuter != null) mOuter.onMessages(list);
            }
        });
    }

    private void postError(final String msg) {
        AppLog.i(TAG, msg);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (!mDestroyed && mOuter != null) mOuter.onEngineError(msg);
            }
        });
    }

    /**
     * 模型 → UI 模型。UI 侧要求 **新→旧**（ChatActivity.onMessages 会反转）。
     *
     * 方向优先用解析阶段算好的 {@code m.direction}（来自协议：sender == selfUid）；
     * selfUid 缺省时才用这里传进来的兜底值。
     */
    private List<ChatMessage> toUi(List<ImModels.Msg> msgs, long selfUid) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ImModels.Msg m = msgs.get(i);
            int dir = m.direction;
            if (selfUid != 0 && m.senderUid != 0) {
                dir = m.senderUid == selfUid ? ChatMessage.OUT : ChatMessage.IN;
            }
            ChatMessage cm = new ChatMessage(dir, m.text, m.timeMs);
            cm.timeText = timeTextOf(m.timeMs);
            out.add(cm);
        }
        return out;
    }

    private String summaryOf(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    /**
     * 相对时间文案（与 WebView 通道拿到的 DOM 文案口径相近）：
     * 今天 → HH:mm；昨天 → "昨天"；更早 → MM-dd。
     * ⚠️ 用本地时区，不引 java.time（minSdk 21 没有）。
     */
    private String timeTextOf(long ms) {
        if (ms <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar t = java.util.Calendar.getInstance();
        t.setTimeInMillis(ms);
        boolean sameDay = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return String.format(java.util.Locale.US, "%02d:%02d",
                    t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE));
        }
        now.add(java.util.Calendar.DAY_OF_YEAR, -1);
        boolean yesterday = now.get(java.util.Calendar.YEAR) == t.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == t.get(java.util.Calendar.DAY_OF_YEAR);
        if (yesterday) return "昨天";
        return String.format(java.util.Locale.US, "%02d-%02d",
                t.get(java.util.Calendar.MONTH) + 1, t.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
