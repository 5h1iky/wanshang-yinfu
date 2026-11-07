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
import com.dywatch.app.chat.api.ConvResolver;
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

    /**
     * 发送总超时：首次发送可能要等临时 WebView 提取凭证（CredExtractor 上限 20s），
     * 再加 HTTP connect/read（10s/20s），所以给 55s。宁可慢一点，也不要提前解锁
     * 让用户重试造成重复发送。
     */
    private static final long SEND_TIMEOUT_MS = 55_000L;

    private final Activity mActivity;
    private final ChatEngine.Listener mOuter;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();

    private ImApi mApi;
    /** 当前会话（进聊天页时由 openConversation 设；volatile：IO 线程写、主线程读） */
    private volatile String mConvId;
    private volatile long mConvShortId;
    private volatile String mConvTicket;
    /** 打开会话的代次：快速返回/重进时旧请求不能覆盖新会话 */
    private volatile long mConvSeq;
    /** 当前会话最近一次成功拿到的消息（用于历史与去重） */
    private final List<ImModels.Msg> mMsgs = new ArrayList<>();
    /** 昵称缓存：sec_uid → 昵称（会话列表渲染用） */
    private final java.util.Map<String, String> mNick = new java.util.HashMap<>();
    /** 凭证提取中的回调队列（同时只跑一个提取器） */
    private CredExtractor mExtracting;
    private final List<Runnable> mCredWaiters = new ArrayList<>();
    private boolean mDestroyed;

    // ---- 发送状态机（主线程读写；IO 线程只读 volatile 快照） ----
    private volatile boolean mSending;
    private volatile long mSendSeq;
    private SendCallback mSendCb;
    private String mSendText = "";

    private final Runnable mSendTimeout = new Runnable() {
        @Override
        public void run() {
            if (!mSending) return;
            long seq = mSendSeq;
            AppLog.i(TAG, "发送超时（seq=" + seq + "）");
            finishSend(seq, false, "发送超时（网络/凭证无回应，请重试）");
        }
    };

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
                        // key 直接用 conversation_id：跨页面/跨刷新稳定，不再建 name→id 的静态表。
                        String dn = c.displayName();
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

    @Override
    public boolean isConvListAuthoritative() {
        // init 成功即代表服务端权威结果：0 条就是真实 0 会话，不能再套 DOM 的“卡在会话数据”诊断。
        return true;
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
                        if (convId.equals(mConvId)) postError("消息: " + init.error);
                        return;
                    }
                    // ⚠️ 会话可能在这期间被切换：旧请求的结果绝不能投到新会话。
                    if (!convId.equals(mConvId)) return;
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
                    if (convId.equals(mConvId)) postError("消息: " + friendly(t));
                }
            }
        });
    }

    @Override
    public void openConversation(final String name) {
        openConversation(null, name);
    }

    /**
     * 打开会话：稳定 key（conversation_id）精确查找；name 只做唯一精确兜底。
     *
     * 2026-11-07 审计：原实现按 name 双向 startsWith 匹配，前缀会话会串；
     * 静态 sNameToId 也可能被旧账号/旧列表污染。现在不再用任何名称模糊匹配。
     */
    @Override
    public void openConversation(final String key, final String name) {
        final long seq = ++mConvSeq;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ImApi.Init init = mApi.init();
                    if (!init.ok()) {
                        if (seq == mConvSeq) postError("打开会话: " + init.error);
                        return;
                    }
                    if (seq != mConvSeq) return;   // 已切到别的会话，旧请求作废
                    fillNicknames(init.convs);
                    if (seq != mConvSeq) return;

                    ImModels.Conv hit = ConvResolver.resolve(init.convs, key, name);
                    if (hit == null) {
                        String show = name == null || name.trim().isEmpty() ? key : name;
                        if (seq == mConvSeq) postError("找不到会话「" + show + "」");
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
                    if (seq == mConvSeq) postError("打开会话: " + friendly(t));
                }
            }
        });
    }

    @Override
    public boolean sendText(final String text, final SendCallback cb) {
        if (mDestroyed) return false;
        if (text == null || text.trim().isEmpty()) return false;
        final String convId = mConvId;
        final long shortId = mConvShortId;
        final String ticket = mConvTicket;
        if (convId == null || convId.isEmpty()) {
            // 还没进会话：明确告诉 UI 这次没发（不要显示气泡）
            postError("还没进入会话，无法发送");
            return false;
        }
        // 单飞锁：重复点击/上层漏了防重时，绝不再发第二条
        if (mSending) {
            AppLog.i(TAG, "发送被单飞锁拒绝（上一条仍在途）");
            if (cb != null) cb.onSendResult(false, "上一条还在发送中，请稍候", text);
            return false;
        }
        mSending = true;
        final long seq = ++mSendSeq;
        mSendCb = cb;
        mSendText = text;
        mMain.removeCallbacks(mSendTimeout);
        mMain.postDelayed(mSendTimeout, SEND_TIMEOUT_MS);
        AppLog.i(TAG, "发送开始 seq=" + seq + " conv=" + convId);

        // 凭证：先用缓存，过期/缺失则临时提一次（提取要几秒，先让 UI 知道"在发"）
        ImCreds creds = ImCreds.load(mActivity);
        if (creds.fresh()) {
            doSend(seq, text, creds, convId, shortId, ticket);
            return true;
        }
        extractCredsThen(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentSend(seq)) return;   // 等凭证期间已超时/页面销毁
                ImCreds fresh = ImCreds.load(mActivity);
                if (!fresh.complete()) {
                    finishSend(seq, false, "发送凭证获取失败（请打开一次私信页后重试）");
                    return;
                }
                doSend(seq, text, fresh, convId, shortId, ticket);
            }
        });
        return true;
    }

    private boolean isCurrentSend(long seq) {
        return !mDestroyed && mSending && seq == mSendSeq;
    }

    /**
     * 发送唯一终态：只有当前在途请求能结束；旧请求的迟到回调直接丢弃。
     * 主线程调用（Handler / mMain.post / CredExtractor 回调）。
     */
    private void finishSend(long seq, boolean ok, String detail) {
        if (!isCurrentSend(seq)) {
            AppLog.i(TAG, "忽略迟到发送回调 seq=" + seq + "（当前=" + mSendSeq + "）");
            return;
        }
        mSending = false;
        mMain.removeCallbacks(mSendTimeout);
        SendCallback cb = mSendCb;
        String text = mSendText;
        mSendCb = null;
        mSendText = "";
        if (cb != null) cb.onSendResult(ok, detail == null ? "" : detail, text);
    }

    private void doSend(final long seq, final String text, final ImCreds creds,
                        final String convId, final long shortId, final String ticket) {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                // 排队期间可能已超时/切换页面；不要再多发一条已作废的消息。
                if (!isCurrentSend(seq)) return;
                try {
                    ImSend send = new ImSend().setCookie(LoginManager.getCookies(mActivity));
                    final ImSend.Result r = send.sendText(convId, shortId, ticket, creds, text);
                    AppLog.i(TAG, "发送 " + r);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isCurrentSend(seq)) return;   // 迟到结果不改下一次发送状态
                            if (r.ok) {
                                applyRotatedConvTicket(convId, r.newTicket);
                                finishSend(seq, true, "");
                                if (mOuter != null) mOuter.onAuth(true, "");
                                // 立刻刷新一次，让服务端真数据替换本地回声，同时把会话 ticket 刷到最新
                                fetchMessages();
                            } else if (!r.decision.isEmpty()) {
                                finishSend(seq, false, "发送被服务端拦截（" + r.decision + "）");
                            } else {
                                finishSend(seq, false,
                                        "发送失败: " + (r.message.isEmpty() ? ("code " + r.code) : r.message));
                            }
                        }
                    });
                } catch (Throwable t) {
                    final String why = friendly(t);
                    AppLog.i(TAG, "发送异常: " + why);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            finishSend(seq, false, "发送失败: " + why);
                        }
                    });
                }
            }
        });
    }

    /**
     * 采用发送响应里的轮换 ticket（f9）。
     *
     * 只更新本进程内当前会话的 f7 会话 ticket，不写 ImCreds：
     * ImCreds.ticket 是 ticket-guard HMAC 的账号级材料，f9 语义未在 proto 中确认，
     * 写错会让所有后续发送签名失败（甚至 KICK）。会话 ticket 每轮 init 都会刷新，
     * 这里只在形态可信时短暂采用。
     */
    private void applyRotatedConvTicket(String convId, String candidate) {
        if (candidate == null || candidate.isEmpty()) return;
        if (convId == null || !convId.equals(mConvId)) return;
        String next = ImSend.nextConvTicket(mConvTicket, candidate);
        if (next.equals(mConvTicket)) {
            AppLog.i(TAG, "发送响应的 f9 不像会话 ticket，忽略");
            return;
        }
        mConvTicket = next;
        AppLog.i(TAG, "会话 ticket 已按发送响应轮换（len=" + next.length() + "）");
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
        mSending = false;
        mSendSeq++;
        mSendCb = null;
        mSendText = "";
        mMain.removeCallbacks(mSendTimeout);
        mMain.removeCallbacksAndMessages(null);
        if (mExtracting != null) {
            try {
                mExtracting.destroy();
            } catch (Throwable ignored) {
            }
            mExtracting = null;
        }
        mCredWaiters.clear();
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
                    if (older.isEmpty() || !convId.equals(mConvId)) return;
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
                        for (Runnable r : todo) {
                            try {
                                r.run();
                            } catch (Throwable t) {
                                AppLog.i(TAG, "凭证等待回调异常: " + friendly(t));
                            }
                        }
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
