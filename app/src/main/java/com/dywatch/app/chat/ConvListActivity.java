package com.dywatch.app.chat;

// M3 会话列表页（用户设计要求：会话列表 → 选人 → 进会话，不能直接跳输入框）。
// 数据来自 ChatEngine（WebView 协议引擎单例）DOM 抓取；点击会话行进入 ChatActivity。

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.util.AppLog;

import java.util.List;

public class ConvListActivity extends UiActivity implements ChatEngine.Listener {

    private androidx.recyclerview.widget.RecyclerView mConvs;
    private com.dywatch.app.ui.RowAdapter mAdapter;
    private TextView mHint;
    private ChatPort mEngine;
    private int mEmptyRetries;
    /** 登录态是否已确认（onAuth ok=true 后置起） */
    private boolean mAuthed;
    /** 私信页是否已加载完成（onEngineReady 后置起） */
    private boolean mPageReady;
    /** 本页打开时刻：首屏预算（LegacyKernel.FIRST_LOAD_BUDGET_MS）从这一刻算起 */
    private long mOpenedAt;
    /** 空列表自动重试的上限（之后不再加新请求，但"加载中"的提示与转圈继续） */
    private static final int MAX_EMPTY_RETRIES = 8;
    /** 进度刷新间隔（见 mTick 的注释） */
    private static final long TICK_MS = 5000L;
    /** 是否已经有会话内容（有内容就不再刷"正在拉取"） */
    private boolean mHasContent;
    /** 是否已经拿到**可信**的列表结果（非空，或原生接口确认 0 条） */
    private boolean mListSettled;
    /** 原生接口已确认"真实 0 会话"（不是 DOM 还没渲染出来） */
    private boolean mConfirmedEmpty;
    /** 终态提示是否已给过（避免每 5 秒重复写一遍同一句） */
    private boolean mTerminalShown;
    /** 上一次心跳时是否断网：用于"网络恢复 → 重启首屏预算"（否则文案承诺的"自动继续"是空话） */
    private boolean mWasOffline;

    /**
     * 进度心跳（2026-10-25 真机发现）。
     *
     * 现场：断开网络进聊天页，界面停在「正在拉取会话…（…已重试 10 次）**已等 45s**」再也不动。
     * 原因：提示只在引擎回调里刷新，而"没有回调"恰恰就是卡住的形态——
     * 于是"已等 Ns"定格、180s 的终态也永远不会出现（用户看到的是一个冻结的界面）。
     *
     * 修法：每 5 秒自己刷一次。预算内继续报进度（秒数在动 = 程序还活着），
     * 预算耗尽且仍无内容 → 给**一次**终态提示（只说慢/卡在哪一步，不下"无法加载"的结论）。
     */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (mHint == null) return;
            // 网络恢复（上一次心跳还断着）→ 重启首屏预算、清掉终态，让"连上会自动继续"成为真话
            boolean up = networkUp();
            if (!up) {
                mWasOffline = true;
            } else if (mWasOffline) {
                mWasOffline = false;
                mTerminalShown = false;
                mOpenedAt = System.currentTimeMillis();
                AppLog.i("chat", "网络恢复 → 重启首屏预算 + 重载私信页");
                // ⚠️ 只重启预算不够：断网时 WebView 停在 Chromium 报错页，它不会自己重试
                if (mEngine != null) mEngine.reloadIm();
            }
            long waited = System.currentTimeMillis() - mOpenedAt;
            if (!mListSettled && !mTerminalShown) {
                boolean patched = mEngine != null && mEngine.isPatchApplied();
                LegacyKernel.Hint h = LegacyKernel.decideHint(
                        kernel(), mAuthed, mPageReady, mEmptyRetries, patched, waited, up, mConfirmedEmpty);
                if (h.loading) {
                    loadingHint(h.text);
                } else {
                    mTerminalShown = true;
                    terminalHint(h.text);
                    AppLog.i("chat", "首屏预算耗尽（已等 " + (waited / 1000) + "s，无内容）→ 终态: " + h.text);
                }
            }
            mHint.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_convlist);
        setPageTitle("会话");
        mOpenedAt = System.currentTimeMillis();

        mConvs = findViewById(R.id.rv_convs);
        mConvs.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        // 行点击 → 进会话（会话对象作为载荷带回）
        mAdapter = new com.dywatch.app.ui.RowAdapter(
                new com.dywatch.app.ui.RowAdapter.OnRowClick() {
                    @Override
                    public void onClick(Object payload) {
                        if (!(payload instanceof Conversation)) return;
                        Conversation c = (Conversation) payload;
                        Intent it = new Intent(ConvListActivity.this, ChatActivity.class);
                        // 稳定 key 优先（原生通道=conversation_id）；name 只用于显示/老通道兼容。
                        it.putExtra(ChatActivity.EXTRA_CONV_KEY, c.key);
                        it.putExtra(ChatActivity.EXTRA_CONV_NAME, c.name);
                        startActivity(it);
                    }
                });
        mConvs.setAdapter(mAdapter);
        mHint = findViewById(R.id.tv_convlist_hint);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        if (com.dywatch.app.login.LoginManager.hasSession(this)) {
            mEngine = ChatPorts.create(this, this);
            hint("正在加载会话…");
            setLoading(true);
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再拉）
            if (mEngine != null) mEngine.ensureImHome();
            // 进度心跳：只在"有登录态、在等数据"时才有意义
            mHint.postDelayed(mTick, TICK_MS);
        } else {
            mEngine = null;
            hint("请先登录（主屏→登录→扫码）");
        }

        AppLog.i("chat", "会话列表页打开");
    }

    /**
     * 当前有没有可用网络（2026-10-25）。
     *
     * 用来避免一个真机上抓到的误判：断网时引擎确认不了登录态，终态会说"未登录，请回主屏重新登录"，
     * 把用户骗去重新扫码（还没网，必然失败）。网络不通时先报网络。
     * 判不出来时**按有网处理**（宁可多等，也不要误报"没网"）。
     */
    private boolean networkUp() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 本机网页内核（用于判定私信是否可能因内核过旧而不可用） */    private LegacyKernel.Kernel kernel() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return null;
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            if (pi == null) return null;
            return LegacyKernel.from(pi.versionName, pi.packageName);
        } catch (Throwable t) {
            return null;   // 取不到就当未知，不因此崩溃
        }
    }

    /**
     * 加载小圆圈：转 = "正在取数据且还没结果"；一旦给出错误/终态提示就停，
     * 免得界面永远在转（用户看不出到底还在等还是已经失败）。
     */
    private void setLoading(boolean on) {
        com.dywatch.app.ui.Loading.show(this, on);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mEngine != null) {
            mEngine.attach(this);
            // ⚠️ 从聊天页返回时，内核线的静态监听者还挂着 ChatActivity；
            //    必须重新绑定回本页，否则新会话列表只会分发给已经销毁的聊天页。
            //    WebView 线的 rebind() 是空实现，行为保持不变。
            mEngine.rebind();
            // 从会话页返回时刷新列表
            mEngine.fetchConversations();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mEngine != null) mEngine.detach(this);
    }

    /**
     * 四态反馈统一出口：有话要说就显示，没话就收起来。
     * 手表那点高度要留给会话本身——旧版提示行永远占位（且一直写"正在加载会话…"），
     * 内容来了也不让位，是纯浪费。
     */
    private void hint(String text) {
        if (mHint == null) return;
        mHint.setText(text);
        mHint.setVisibility(android.view.View.VISIBLE);
    }

    /**
     * 加载态提示：文案 + **转圈**（2026-10-01）。
     * 用户反馈"只有正在拉取那一段有圆圈"——原因就是老代码在错误/终态提示里都会
     * setLoading(false)，而唯一的加载提示没管转圈状态。现在加载态统一走这里。
     */
    private void loadingHint(String text) {
        hint(text);
        setLoading(true);
    }

    /** 终态提示：说明卡在哪一步，并停下转圈（别再让用户空等） */
    private void terminalHint(String text) {
        hint(text);
        setLoading(false);
    }

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(android.view.View.GONE);
    }

    private void render(List<Conversation> list) {
        if (list.isEmpty()) {
            // 原生 API 的 init 成功返回 0 条 = 服务端确认“真实 0 会话”。
            // 这时必须清掉旧行（否则旧会话还能点、可能点进已删除/已切换账号的会话），
            // 并且**不能**再套 DOM 的“卡在会话数据”诊断。
            if (mEngine != null && mEngine.isConvListAuthoritative()) {
                mEmptyRetries = 0;
                mHasContent = false;
                mListSettled = true;
                mConfirmedEmpty = true;
                mTerminalShown = true;
                setLoading(false);
                setPageTitle("会话 0");
                mAdapter.clear();
                hint(LegacyKernel.emptyHint().text);
                AppLog.i("chat", "会话列表：原生接口确认 0 个会话 → 空态");
                return;
            }

            mHasContent = false;
            mListSettled = false;
            mConfirmedEmpty = false;
            if (mTerminalShown) {
                // 终态已经给过（心跳那一路），引擎再来空结果也不用重复刷屏
                return;
            }
            // ⚠️ 2026-10-01 重做（用户反馈："三个提示里两个在否定加载成功"）：
            //    老逻辑只要重试 2 次（≈5 秒）就敢下"内核过旧 / 脚本解析失败"的结论，
            //    而真机冷启动要 35~40 秒才拿到数据 → 提示先否定、几秒后又加载成功，自打脸。
            //    现在分两段：**首屏预算内只报进度（且一直转圈）**，预算耗尽才给"卡在哪一步"的终态。
            long waited = System.currentTimeMillis() - mOpenedAt;
            LegacyKernel.Kernel k = kernel();
            boolean patched = mEngine != null && mEngine.isPatchApplied();
            LegacyKernel.Hint h = LegacyKernel.decideHint(
                    k, mAuthed, mPageReady, mEmptyRetries, patched, waited, networkUp(), false);
            if (h.loading) {
                loadingHint(h.text);
            } else {
                mTerminalShown = true;
                terminalHint(h.text);
                if (LegacyKernel.isKernelDeadEnd(k, mEmptyRetries, patched)) {
                    // 补丁未生效 + 内核确实过旧：只说"慢"，不再说"无法加载"，也不再排重试
                    AppLog.i("chat", "私信很慢（内核偏旧，补丁未生效，已等 " + (waited / 1000) + "s）: " + h.text);
                    return;
                }
            }
            // 预算内继续自动重试；到底了（MAX_EMPTY_RETRIES）就不再加新请求，
            // 但页面仍由 bridge 自己的轮询兜着（真机上数据就是这么在 30~40s 到的）。
            mEmptyRetries++;
            if (mEmptyRetries <= MAX_EMPTY_RETRIES) {
                mConvs.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEngine != null && !isFinishing()) mEngine.fetchConversations();
                    }
                }, 2500);
            }
            return;
        }
        mEmptyRetries = 0;
        mHasContent = true;
        mListSettled = true;
        mConfirmedEmpty = false;
        mTerminalShown = false;
        setLoading(false);
        // 计数折进页名，省掉常驻提示行——手表那点高度要留给会话本身
        setPageTitle("会话 " + list.size());
        clearHint();
        // 昵称 / 时间 / 最近消息 三级分层（旧版三段同字号同颜色塞一个 TextView，读不出层级）
        java.util.List<com.dywatch.app.ui.RowAdapter.Item> items = new java.util.ArrayList<>();
        for (Conversation c : list) {
            items.add(new com.dywatch.app.ui.RowAdapter.Item(c.name, c.timeText, c.lastMsg, c));
        }
        mAdapter.submitList(items);
    }

    // ---- ChatEngine.Listener ----

    @Override
    public void onEngineReady() {
        mPageReady = true;
        // ⚠️ getInstance 同步回调时 mEngine 尚未赋值 → post 到队尾
        mHint.post(new Runnable() {
            @Override
            public void run() {
                loadingHint("通道就绪，拉取会话…");   // 加载态：继续转圈（用户反馈的那段）
                if (mEngine != null) mEngine.fetchConversations();
            }
        });
    }

    /**
     * 引擎还在热身（2026-10-01）：这是**等待**不是错误 —— 显示进度 + 转圈，
     * 绝不出现"异常/失败"字样（用户反馈：刚进页面就报"通道异常: 通道未就绪"，几秒后就好了）。
     */
    @Override
    public void onEngineWaiting(String why) {
        loadingHint(LegacyKernel.progressHint(false, mEmptyRetries + 1,
                System.currentTimeMillis() - mOpenedAt));
    }

    @Override
    public void onMessages(List<ChatMessage> list) {
        // 列表页不消费消息
    }

    @Override
    public void onConversations(List<Conversation> list) {
        AppLog.i("chat", "会话列表回调 n=" + list.size());
        render(list);
    }

    @Override
    public void onComments(List<Comment> list) {
        // 会话列表页不消费评论
    }

    @Override
    public void onCommentsMore(List<Comment> list, boolean atEnd) {
        // 会话列表页不消费评论
    }

    @Override
    public void onAuth(boolean ok, String message) {
        mAuthed = ok;   // 供"卡在哪一步"的分级提示使用
        if (!ok) {
            // 登录态失败是**用户要动手**的事（去扫码登录），立刻给终态，不套首屏预算
            terminalHint("⚠ " + message);
            if (mEngine != null) mEngine.fetchConversations();
        }
    }

    /**
     * 真·错误（引擎自己报的；"未就绪"那种正常等待已经改走 onEngineWaiting）。
     *
     * ⚠️ 但**首屏预算内仍不当失败显示**：老代码在这里直接写"通道异常: …"并停转圈，
     *    而这类错误常常只是"页面还没就绪"的连带反应（真机实测：报完几秒数据就来了）。
     *    预算内一律显示加载进度 + 继续转圈，同时把真错误**照原样打进日志**（排查不受影响）；
     *    预算耗尽后才是真的终态。
     */
    @Override
    public void onEngineError(String err) {
        AppLog.i("chat", "会话列表异常: " + err);
        long waited = System.currentTimeMillis() - mOpenedAt;
        if (LegacyKernel.stillLoading(waited)) {
            loadingHint(LegacyKernel.progressHint(mPageReady, mEmptyRetries + 1, waited));
        } else {
            terminalHint("通道异常: " + err);
        }
        // 页面可能未就绪，稍后自动重试
        mConvs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mEngine != null && !isFinishing()) mEngine.fetchConversations();
            }
        }, 3000);
    }

    @Override
    protected void onDestroy() {
        // 停掉进度心跳，别让定时器抱着已销毁的页面（会泄漏整页）
        if (mHint != null) mHint.removeCallbacks(mTick);
        super.onDestroy();
        // 引擎是单例，不随页面销毁；仅解除监听
        if (mEngine != null) mEngine.setListenerDetached(this);
    }
}
