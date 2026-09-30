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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_convlist);
        setPageTitle("会话");

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

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(android.view.View.GONE);
    }

    private void render(List<Conversation> list) {
        if (list.isEmpty()) {
            // 老内核（方案 D）：命中就给终态提示，不再无谓转圈。
            // 以前一律写"正在等待会话数据…（自动重试）"，用户以为是自己网络不好，
            // 实际上是本机内核太老（真机坐实：Chromium 83 解析不了抖音私信模块）。
            LegacyKernel.Kernel k = kernel();
            boolean patched = mEngine != null && mEngine.isPatchApplied();
            // ⚠️ 补丁已生效时，内核不再是原因 → 别再显示"内核过旧"（那会误导）
            if (!patched && LegacyKernel.isKernelDeadEnd(k, mEmptyRetries)) {
                hint(LegacyKernel.kernelHint(k));
                setLoading(false);
                AppLog.i("chat", "私信不可用（内核过旧）: " + LegacyKernel.kernelHint(k));
                return;
            }
            setLoading(true);   // 还在自动重试 = 仍在加载，圆圈继续转
            // IM 数据异步渲染，页面刚就绪时常为空 → 自动重试；提示要说明"卡在哪一步"
            if (mEmptyRetries < 8) {
                hint(LegacyKernel.stuckHint(k, mAuthed, mPageReady, mEmptyRetries, patched));
                mEmptyRetries++;
                mConvs.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEngine != null && !isFinishing()) mEngine.fetchConversations();
                    }
                }, 2500);
            } else {
                // 重试到底仍空：给出"卡在哪"的终态说明，而不是含糊的"没有会话"
                hint(LegacyKernel.stuckHint(k, mAuthed, mPageReady, mEmptyRetries, patched));
                setLoading(false);
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
                hint("通道就绪，拉取会话…");
                if (mEngine != null) mEngine.fetchConversations();
            }
        });
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
            hint("⚠ " + message);
            setLoading(false);   // 已给出明确失败提示 → 停止转圈
            if (mEngine != null) mEngine.fetchConversations();
        }
    }

    @Override
    public void onEngineError(String err) {
        hint("通道异常: " + err);
        setLoading(false);       // 同上：有明确提示就不再转
        AppLog.i("chat", "会话列表异常: " + err);
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
