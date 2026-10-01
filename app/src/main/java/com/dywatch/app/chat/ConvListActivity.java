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
    private ChatEngine mEngine;
    private int mEmptyRetries;
    /** 登录态是否已确认（onAuth ok=true 后置起） */
    private boolean mAuthed;
    /** 私信页是否已加载完成（onEngineReady 后置起） */
    private boolean mPageReady;
    /** 本页打开时刻：首屏预算（LegacyKernel.FIRST_LOAD_BUDGET_MS）从这一刻算起 */
    private long mOpenedAt;
    /** 空列表自动重试的上限（之后不再加新请求，但"加载中"的提示与转圈继续） */
    private static final int MAX_EMPTY_RETRIES = 8;

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
            mEngine = ChatEngine.getInstance(this, this);
            hint("正在加载会话…");
            setLoading(true);
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再拉）
            mEngine.ensureImHome();
        } else {
            mEngine = null;
            hint("请先登录（主屏→登录→扫码）");
        }

        AppLog.i("chat", "会话列表页打开");
    }

    /** 本机网页内核（用于判定私信是否可能因内核过旧而不可用） */
    private LegacyKernel.Kernel kernel() {
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
        ChatEngine.attachTo(this);
        if (mEngine != null) {
            // 从会话页返回时刷新列表
            mEngine.fetchConversations();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatEngine.detachFrom(this);
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
            // ⚠️ 2026-10-01 重做（用户反馈："三个提示里两个在否定加载成功"）：
            //    老逻辑只要重试 2 次（≈5 秒）就敢下"内核过旧 / 脚本解析失败"的结论，
            //    而真机冷启动要 35~40 秒才拿到数据 → 提示先否定、几秒后又加载成功，自打脸。
            //    现在分两段：**首屏预算内只报进度（且一直转圈）**，预算耗尽才给"卡在哪一步"的终态。
            long waited = System.currentTimeMillis() - mOpenedAt;
            int attempts = mEmptyRetries + 1;          // 本次是第几次尝试（渲染发生在自增之前）
            LegacyKernel.Kernel k = kernel();
            boolean patched = mEngine != null && mEngine.isPatchApplied();
            if (LegacyKernel.stillLoading(waited)) {
                loadingHint(LegacyKernel.progressHint(mPageReady, attempts));
            } else if (!patched && LegacyKernel.isKernelDeadEnd(k, mEmptyRetries)) {
                terminalHint(LegacyKernel.kernelHint(k));
                AppLog.i("chat", "私信不可用（内核过旧，已等 " + (waited / 1000) + "s）: "
                        + LegacyKernel.kernelHint(k));
                return;
            } else {
                terminalHint(LegacyKernel.stuckHint(k, mAuthed, mPageReady, attempts, patched));
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
        loadingHint(LegacyKernel.progressHint(false, mEmptyRetries + 1));
    }

    @Override
    public void onMessages(List<ChatMessage> list) {
        // 列表页不消费消息
    }

    @Override
    public void onConversations(List<Conversation> list) {
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
            loadingHint(LegacyKernel.progressHint(mPageReady, mEmptyRetries + 1));
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
        super.onDestroy();
        // 引擎是单例，不随页面销毁；仅解除监听
        if (mEngine != null) mEngine.setListenerDetached(this);
    }
}
