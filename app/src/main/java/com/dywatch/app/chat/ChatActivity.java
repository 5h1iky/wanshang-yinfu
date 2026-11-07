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

    /** 进入指定会话（ConvListActivity 传入稳定 key / 显示名） */
    public static final String EXTRA_CONV_KEY = "conv_key";
    public static final String EXTRA_CONV_NAME = "conv_name";

    /** 发送在途保护（防连点/迟到回调污染下一条） */
    private static final long SEND_TIMEOUT_MS = 60_000L;

    private androidx.recyclerview.widget.RecyclerView mMessages;
    private com.dywatch.app.ui.MessageAdapter mAdapter;
    private EditText mInput;
    private TextView mHint;
    private View mSendButton;
    private ChatPort mEngine;
    /** 上次渲染的消息条数：只有真的变多了才自动滚到底，否则每 5s 轮询都会把用户拽回底部 */
    private int mLastCount = -1;
    /** 稳定会话 key（原生通道=conversation_id）；可能为 null（老 WebView 通道只有 name） */
    private String mConvKey;
    private String mConvName;
    /** 本次发送的草稿：成功才丢，失败/超时回填（审计 M6 同款思路） */
    private String mDraft;
    /** 本次发送的本地回声：失败时要撤回，不能留“已发送”假象 */
    private ChatMessage mLocalEcho;
    /** 发送状态机（纯逻辑，见 ChatSendGuard） */
    private final ChatSendGuard mSendGuard = new ChatSendGuard();
    /** 首屏预算（LegacyKernel.FIRST_LOAD_BUDGET_MS）从这一刻算起 */
    private long mOpenedAt;
    private final SimpleDateFormat mFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final android.os.Handler mPoll = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler mUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mSendTimeout = new Runnable() {
        @Override
        public void run() {
            if (!mSendGuard.isSending()) return;
            long token = mSendGuard.currentToken();
            finishSend(token, false, "发送超时，请重试", mDraft == null ? "" : mDraft);
        }
    };
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
        mOpenedAt = System.currentTimeMillis();   // 首屏预算起点（提示状态机用）

        mMessages = findViewById(R.id.rv_messages);
        mMessages.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new com.dywatch.app.ui.MessageAdapter();
        mMessages.setAdapter(mAdapter);
        mInput = findViewById(R.id.et_input);
        mHint = findViewById(R.id.tv_chat_hint);
        mSendButton = findViewById(R.id.btn_send);
        // 方案 C：输入框 = 正文面，吃「字体大小」（发送键/快捷回复条等 chrome 不跟）
        com.dywatch.app.ui.Fonts.scale(mInput, R.dimen.t_body);
        mConvKey = getIntent().getStringExtra(EXTRA_CONV_KEY);
        mConvName = getIntent().getStringExtra(EXTRA_CONV_NAME);

        // 登录门：私信必须先登录（未登录时页面上没有聊天输入框，JS 桥必然找不到）
        if (com.dywatch.app.login.LoginManager.hasSession(this)) {
            mEngine = ChatPorts.create(this, this);
            hint(mConvName == null ? "聊天通道启动中…" : ("打开会话: " + mConvName + "…"));
            com.dywatch.app.ui.Loading.show(this, true);
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再开会话）
            if (mEngine != null) mEngine.ensureImHome();
        } else {
            mEngine = null;
            hint("请先登录再聊天（主屏→登录→扫码）");
        }

        mSendButton.setOnClickListener(new View.OnClickListener() {
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
        // ⚠️ 文案不再写死（软件内问题 ③b）：从 Settings 读用户自己编辑的那 4 个槽位，
        //    默认值就是原来写死这套（好/在忙/稍等/😂）；留空的槽位 wire() 会自动隐藏按钮。
        com.dywatch.app.ui.QuickReply.wire(this,
                com.dywatch.app.ui.Settings.quickReplyTexts(this),
                new com.dywatch.app.ui.QuickReply.Pick() {
                    @Override
                    public void onPick(String t) {
                        // 快捷回复也走同一条发送状态机：在途防重、失败撤回气泡、恢复输入。
                        // 不覆盖用户已经打好的草稿（clearInput=false）。
                        submitSend(t, false);
                    }
                });

        AppLog.i("chat", "聊天页打开");
    }

    private void sendCurrent() {
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (mSendGuard.isSending()) {
            hint("上一条还在发送中…");
            return;
        }
        submitSend(text, true);
    }

    /**
     * 发送状态机（2026-11-07 审计 H4 + 私信链路修复）。
     *
     * 顺序很关键：
     *   ① 先拿 in-flight 锁（同一时刻只允许一条）；
     *   ② 记住草稿、清空输入框、插入本地回声；
     *   ③ 调用通道；返回 false（根本没发）或回调失败 → 撤回回声 + 回填草稿；
     *   ④ 超时也走同一个终态，绝不让按钮永久锁死。
     *
     * @param clearInput true=发送键（清空输入框）；false=快捷回复（保留用户原草稿）
     * @return true = 通道已接手；false = 根本没发出去
     */
    private boolean submitSend(final String text, boolean clearInput) {
        if (TextUtils.isEmpty(text)) return false;
        if (mSendGuard.isSending()) {
            hint("上一条还在发送中…");
            return false;
        }
        if (mEngine == null) {
            hint("未登录，不能发送（主屏→登录→扫码）");
            return false;
        }

        final long token = mSendGuard.begin();
        if (token == 0L) {
            hint("上一条还在发送中…");
            return false;
        }
        mDraft = text;
        if (clearInput && mInput != null) mInput.setText("");

        final ChatMessage echo = new ChatMessage(ChatMessage.OUT, text, System.currentTimeMillis());
        mLocalEcho = echo;
        appendLocal(echo);
        hint("发送中…");
        setSendingUi(true);

        boolean accepted;
        try {
            accepted = mEngine.sendText(text, new ChatPort.SendCallback() {
                @Override
                public void onSendResult(boolean ok, String detail, String text2) {
                    if (isFinishing() || isDestroyed()) return;
                    finishSend(token, ok, detail, text2 == null ? text : text2);
                }
            });
        } catch (Throwable t) {
            accepted = false;
            AppLog.i("chat", "发送调用异常: " + t.getClass().getSimpleName());
        }
        AppLog.i("chat", "发送提交 textLen=" + text.length() + " accepted=" + accepted
                + " channel=" + (mEngine == null ? "null" : mEngine.getClass().getSimpleName()));

        if (!accepted) {
            finishSend(token, false, "通道还没就绪，消息未发出", text);
            return false;
        }
        // 只有仍在途才挂超时；同步回调已经结束的情况不要留下多余定时器。
        if (mSendGuard.isCurrent(token)) mUi.postDelayed(mSendTimeout, SEND_TIMEOUT_MS);
        return true;
    }

    /**
     * 发送唯一终态。token 不匹配 = 迟到/重复回调，直接丢弃，绝不改下一条发送的状态。
     */
    private void finishSend(long token, boolean ok, String detail, String text) {
        if (!mSendGuard.finish(token)) return;
        mUi.removeCallbacks(mSendTimeout);
        setSendingUi(false);

        if (ok) {
            // 成功：不额外动作（下一次拉取会用服务端数据整批替换本地回声）
            mDraft = null;
            mLocalEcho = null;
            clearHint();
            return;
        }

        // 失败：撤回假气泡 + 回填原文（用户不用重打一遍）
        if (mLocalEcho != null) {
            mAdapter.remove(mLocalEcho);
            mLocalEcho = null;
        }
        String draft = mDraft == null ? text : mDraft;
        mDraft = null;
        restoreDraft(draft);
        hint("⚠ " + (detail == null || detail.isEmpty() ? "发送失败" : detail));
    }

    /** 失败/超时把草稿放回输入框；用户已经重新打字就不覆盖他的新内容。 */
    private void restoreDraft(String draft) {
        if (draft == null || draft.isEmpty() || mInput == null) return;
        if (mInput.getText() != null && mInput.getText().length() > 0) return;
        mInput.setText(draft);
        mInput.setSelection(mInput.getText().length());
    }

    private void setSendingUi(boolean sending) {
        if (mSendButton != null) mSendButton.setEnabled(!sending);
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
                if (mEngine != null) {
                    boolean hasKey = mConvKey != null && !mConvKey.isEmpty();
                    boolean hasName = mConvName != null && !mConvName.isEmpty();
                    if (hasKey || hasName) {
                        mEngine.openConversation(mConvKey, mConvName);
                    }
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
        // 脱敏（审计 H3）：成功那一路的 message 是"已登录 <昵称>"，昵称属于账号身份，不落盘；
        // 失败那一路的 message 是诊断信息（状态码/异常），过一遍 safeText 去掉可能夹带的内容。
        AppLog.i("chat", "登录态: ok=" + ok + (ok ? "（已带昵称）" : " " + BridgeLog.safeText(message)));
        // 登录正常不占提示行；只有异常才提示（旧版一律写一行，白吃手表高度）
        if (!ok) {
            hint("⚠ " + message);
            com.dywatch.app.ui.Loading.show(this, false);
        }
    }

    /**
     * 引擎还在热身（2026-10-01）：等待 ≠ 失败。
     * 会话列表页同样改过这一点——用户反馈"刚进页面就报通道异常，几秒后自己又好了"。
     */
    @Override
    public void onEngineWaiting(String why) {
        if (System.currentTimeMillis() - mOpenedAt < LegacyKernel.FIRST_LOAD_BUDGET_MS) {
            hint(LegacyKernel.progressHint(false, 1));
            com.dywatch.app.ui.Loading.show(this, true);
        }
    }

    /**
     * 真·错误：**首屏预算内不当失败显示**（真机实测这类错误常常只是"页面还没就绪"的连带反应），
     * 预算内显示加载进度并继续转圈；真错误照原样进日志。预算耗尽才是终态。
     */
    @Override
    public void onEngineError(String err) {
        AppLog.i("chat", "通道异常: " + err);
        if (System.currentTimeMillis() - mOpenedAt < LegacyKernel.FIRST_LOAD_BUDGET_MS) {
            hint(LegacyKernel.progressHint(false, 1));
            com.dywatch.app.ui.Loading.show(this, true);
            return;
        }
        hint("通道异常: " + err);
        com.dywatch.app.ui.Loading.show(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mEngine != null) {
            mEngine.attach(this);
            mEngine.rebind();
            startPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mEngine != null) mEngine.detach(this);
        mPoll.removeCallbacks(mPollTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPoll.removeCallbacks(mPollTask);
        mUi.removeCallbacks(mSendTimeout);
        mSendGuard.cancel();
        mDraft = null;
        mLocalEcho = null;
        // 引擎是单例（WebView 复用），仅解除监听
        if (mEngine != null) mEngine.setListenerDetached(this);
    }
}
