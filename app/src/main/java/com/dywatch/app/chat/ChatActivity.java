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

    private LinearLayout mMessages;
    private EditText mInput;
    private TextView mHint;
    private ChatEngine mEngine;
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

        mMessages = findViewById(R.id.ll_messages);
        mInput = findViewById(R.id.et_input);
        mHint = findViewById(R.id.tv_chat_hint);
        mConvName = getIntent().getStringExtra(EXTRA_CONV_NAME);

        // 登录门：私信必须先登录（未登录时页面上没有聊天输入框，JS 桥必然找不到）
        if (com.dywatch.app.login.LoginManager.hasSession(this)) {
            mEngine = ChatEngine.getInstance(this, this);
            mHint.setText(mConvName == null ? "聊天通道启动中…" : ("打开会话: " + mConvName + "…"));
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再开会话）
            mEngine.ensureImHome();
        } else {
            mEngine = null;
            mHint.setText("请先登录再聊天（主屏→登录→扫码）");
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
        int[] quickIds = {R.id.qr1, R.id.qr2, R.id.qr3, R.id.qr4};
        final String[] quickTexts = {"好", "在忙", "稍等", "😂"};
        for (int i = 0; i < quickIds.length; i++) {
            final String t = quickTexts[i];
            findViewById(quickIds[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    safeSend(t);
                    addBubble(new ChatMessage(ChatMessage.OUT, t, System.currentTimeMillis()));
                }
            });
        }

        AppLog.i("chat", "聊天页打开");
    }

    private void sendCurrent() {
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        mInput.setText("");
        safeSend(text);
        addBubble(new ChatMessage(ChatMessage.OUT, text, System.currentTimeMillis()));
    }

    /** 安全发送：引擎未就绪时给提示而不是崩溃 */
    private void safeSend(String text) {
        if (mEngine == null) {
            mHint.setText("未登录，不能发送（主屏→登录→扫码）");
            return;
        }
        mEngine.sendText(text);
    }

    private void addBubble(ChatMessage msg) {
        boolean out = msg.direction == ChatMessage.OUT;
        int dp_ = com.dywatch.app.ui.Rows.dp(this, 1);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // 我方靠右、对方靠左，靠外侧留白做出对话轴向
        lp.setMargins(out ? dp_ * 80 : dp_ * 8, dp_ * 8, out ? dp_ * 8 : dp_ * 80, dp_ * 8);
        lp.gravity = out ? android.view.Gravity.END : android.view.Gravity.START;
        col.setLayoutParams(lp);

        // ⚠️ 必须显式给文字颜色：主题是 Material3.DayNight，浅色模式下默认字色是深色，
        // 配我们这套深色气泡会几乎看不见——以前只是恰好没在浅色模式下试过。
        TextView meta = new TextView(this);
        meta.setText((out ? "我" : "对方")
                + (msg.timeText == null || msg.timeText.isEmpty() ? "" : (" · " + msg.timeText)));
        meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.t_caption));
        meta.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_muted));
        meta.setGravity(out ? android.view.Gravity.END : android.view.Gravity.START);
        col.addView(meta);

        TextView tv = new TextView(this);
        tv.setText(msg.text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.t_body));
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        tv.setPadding(dp_ * 12, dp_ * 9, dp_ * 12, dp_ * 9);
        tv.setBackgroundResource(out ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in);
        col.addView(tv);

        mMessages.addView(col);
    }

    // ---- ChatEngine.Listener ----

    @Override
    public void onEngineReady() {
        // ⚠️ getInstance 会同步回调此方法（此时 mEngine 字段尚未赋值）→ 必须 post 到队尾
        mHint.post(new Runnable() {
            @Override
            public void run() {
                mHint.setText("通道就绪");
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
        mMessages.removeAllViews();
        // 桥返回新→旧，页面按旧→新显示
        for (int i = list.size() - 1; i >= 0; i--) addBubble(list.get(i));
    }

    @Override
    public void onConversations(List<com.dywatch.app.chat.model.Conversation> list) {
        mHint.setText("会话 " + list.size() + " 个");
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
        mHint.setText(ok ? message : ("⚠ " + message));
        AppLog.i("chat", "登录态: ok=" + ok + " " + message);
    }

    @Override
    public void onEngineError(String err) {
        mHint.setText("通道异常: " + err);
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
