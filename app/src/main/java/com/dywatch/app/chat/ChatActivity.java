package com.dywatch.app.chat;

// M3 聊天页（自写极简三件套，ChatKit 评估结论：手机尺寸假设过重、圆表小屏需重写大半布局 → 按预案自写）。
// 协议层走架构 A：ChatEngine（隐藏 WebView + JS 桥）承载页面 SDK，本页只做手表友好 UI。

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.util.AppLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends UiActivity implements ChatEngine.Listener {

    /** 进入指定会话（ConvListActivity 传入会话名） */
    public static final String EXTRA_CONV_NAME = "conv_name";

    private androidx.recyclerview.widget.RecyclerView mMessages;
    private com.dywatch.app.ui.MessageAdapter mAdapter;
    private EditText mInput;
    private TextView mHint;
    private ChatEngine mEngine;
    /** 上次渲染的消息条数：只有真的变多了才自动滚到底，否则每 5s 轮询都会把用户拽回底部 */
    private int mLastCount = -1;
    private String mConvName;
    private final SimpleDateFormat mFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final android.os.Handler mPoll = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            if (mEngine != null) mEngine.fetchMessages();
            mPoll.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        setPageTitle("聊天");

        mMessages = findViewById(R.id.rv_messages);
        mMessages.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new com.dywatch.app.ui.MessageAdapter();
        mMessages.setAdapter(mAdapter);
        mInput = findViewById(R.id.et_input);
        mHint = findViewById(R.id.tv_chat_hint);
        mConvName = getIntent().getStringExtra(EXTRA_CONV_NAME);

        // 登录门：私信必须先登录（未登录时页面上没有聊天输入框，JS 桥必然找不到）
        if (com.dywatch.app.login.LoginManager.hasSession(this)) {
            mEngine = ChatEngine.getInstance(this, this);
            hint(mConvName == null ? "聊天通道启动中…" : ("打开会话: " + mConvName + "…"));
            com.dywatch.app.ui.Loading.show(this, true);
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再开会话）
            mEngine.ensureImHome();
        } else {
            mEngine = null;
            hint("请先登录再聊天（主屏→登录→扫码）");
        }

        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCurrent();
            }
        });

        // 手表硬件适配 §10：可见返回键（不依赖系统手势/按键）
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 手表快捷回复（输入层自研部分：先给常用短语，语音后补）
        // ⚠️ 传页面根（this）而不是 bar 自己：wire() 里要按 id 找 bar 来控制整条显隐，
        //    传 bar 时靠"findViewById 命中自身"这条边角行为才能work，太脆。
        com.dywatch.app.ui.QuickReply.wire(this,
                new String[]{"好", "在忙", "稍等", "😂"}, new com.dywatch.app.ui.QuickReply.Pick() {
                    @Override
                    public void onPick(String t) {
                        safeSend(t);
                        appendLocal(new ChatMessage(ChatMessage.OUT, t, System.currentTimeMillis()));
                    }
                });

        AppLog.i("chat", "聊天页打开");
    }

    private void sendCurrent() {
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        mInput.setText("");
        safeSend(text);
        appendLocal(new ChatMessage(ChatMessage.OUT, text, System.currentTimeMillis()));
    }

    /**
     * 本地回声：刚发出的消息先显示出来（等桥 5s 轮询回来才有真数据，那之前不能空白）。
     * 走追加而不是塞进 mAll——下一次 onMessages 会以服务端数据整批替换，
     * 这里加的东西自然会被覆盖，不会重复。
     */
    private void appendLocal(ChatMessage msg) {
        java.util.List<ChatMessage> one = new java.util.ArrayList<>();
        one.add(msg);
        mAdapter.appendLocal(one);
        scrollToBottomIfGrew(mAdapter.size());
    }

    /** 安全发送：引擎未就绪时给提示而不是崩溃 */
    private void safeSend(String text) {
        if (mEngine == null) {
            hint("未登录，不能发送（主屏→登录→扫码）");
            return;
        }
        mEngine.sendText(text);
    }

    /** 发送失败/未登录等提示：只在有事时占一行，通道正常就让位给消息 */
    private void hint(String text) {
        if (mHint == null) return;
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(View.GONE);
    }

    /**
     * 滚到底部：只在消息条数真的变多时滚（新消息/首次进入）。
     * 每 5s 的轮询重绘若无条件滚，用户往上翻历史会被反复拽回底部。
     */
    private void scrollToBottomIfGrew(int newCount) {
        if (mMessages == null) return;
        boolean grew = newCount > mLastCount;
        mLastCount = newCount;
        if (!grew) return;
        mMessages.post(new Runnable() {
            @Override
            public void run() {
                int n = mAdapter.getItemCount();
                if (n > 0 && !isFinishing()) mMessages.scrollToPosition(n - 1);
            }
        });
    }

    // ---- ChatEngine.Listener ----

    @Override
    public void onEngineReady() {
        // ⚠️ getInstance 会同步回调此方法（此时 mEngine 字段尚未赋值）→ 必须 post 到队尾
        mHint.post(new Runnable() {
            @Override
            public void run() {
                if (mEngine != null && mConvName != null && !mConvName.isEmpty()) {
                    mEngine.openConversation(mConvName);
                }
                startPolling();
            }
        });
    }

    private void startPolling() {
        mPoll.removeCallbacks(mPollTask);
        mPoll.postDelayed(mPollTask, 1500);
    }

    @Override
    public void onMessages(List<ChatMessage> list) {
        // 桥返回新→旧，页面按旧→新显示
        java.util.List<ChatMessage> ordered = new java.util.ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) ordered.add(list.get(i));
        mAdapter.submitList(ordered);
        clearHint();
        com.dywatch.app.ui.Loading.show(this, false);   // 拿到消息了 → 停转圈
        scrollToBottomIfGrew(ordered.size());
    }

    @Override
    public void onConversations(List<com.dywatch.app.chat.model.Conversation> list) {
        // 聊天页顺带收到会话数：不占提示行（通道正常时不该有常驻提示）
        AppLog.i("chat", "会话 " + list.size() + " 个");
    }

    @Override
    public void onComments(List<com.dywatch.app.chat.model.Comment> list) {
        // 聊天页不消费评论
    }

    @Override
    public void onCommentsMore(List<com.dywatch.app.chat.model.Comment> list, boolean atEnd) {
        // 聊天页不消费评论
    }

    @Override
    public void onAuth(boolean ok, String message) {
        AppLog.i("chat", "登录态: ok=" + ok + " " + message);
        // 登录正常不占提示行；只有异常才提示（旧版一律写一行，白吃手表高度）
        if (!ok) {
            hint("⚠ " + message);
            com.dywatch.app.ui.Loading.show(this, false);
        }
    }

    @Override
    public void onEngineError(String err) {
        hint("通道异常: " + err);
        com.dywatch.app.ui.Loading.show(this, false);
        AppLog.i("chat", "通道异常: " + err);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChatEngine.attachTo(this);
        if (mEngine != null) startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatEngine.detachFrom(this);
        mPoll.removeCallbacks(mPollTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPoll.removeCallbacks(mPollTask);
        // 引擎是单例（WebView 复用），仅解除监听
        if (mEngine != null) mEngine.setListenerDetached(this);
    }
}
