package com.dywatch.app.chat;

// M3 会话列表页（用户设计要求：会话列表 → 选人 → 进会话，不能直接跳输入框）。
// 数据来自 ChatEngine（WebView 协议引擎单例）DOM 抓取；点击会话行进入 ChatActivity。

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.util.AppLog;

import java.util.List;

public class ConvListActivity extends AppCompatActivity implements ChatEngine.Listener {

    private LinearLayout mConvs;
    private TextView mHint;
    private ChatEngine mEngine;
    private int mEmptyRetries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_convlist);

        mConvs = findViewById(R.id.ll_convs);
        mHint = findViewById(R.id.tv_convlist_hint);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        if (com.dywatch.app.login.LoginManager.hasSession(this)) {
            mEngine = ChatEngine.getInstance(this, this);
            mHint.setText("正在加载会话…");
            // 引擎可能被上一次互动留在视频页 → 先归位（导航完成后经 onEngineReady 再拉）
            mEngine.ensureImHome();
        } else {
            mEngine = null;
            mHint.setText("请先登录（主屏→登录→扫码）");
        }

        AppLog.i("chat", "会话列表页打开");
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

    private void render(List<Conversation> list) {
        mConvs.removeAllViews();
        if (list.isEmpty()) {
            mHint.setText("正在等待会话数据…（自动重试）");
            // IM 数据异步渲染，页面刚就绪时常为空 → 自动重试
            if (mEmptyRetries < 8) {
                mEmptyRetries++;
                mConvs.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEngine != null && !isFinishing()) mEngine.fetchConversations();
                    }
                }, 2500);
            } else {
                mHint.setText("没有会话（可下拉重进或稍后再试）");
            }
            return;
        }
        mEmptyRetries = 0;
        mHint.setText("共 " + list.size() + " 个会话");
        for (final Conversation c : list) {
            TextView row = new TextView(this);
            String time = c.timeText.isEmpty() ? "" : ("　·　" + c.timeText);
            String last = c.lastMsg.isEmpty() ? "" : ("\n" + c.lastMsg);
            row.setText(c.name + time + last);
            row.setTextSize(15);
            row.setTextColor(Color.WHITE);
            row.setPadding(24, 20, 24, 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 12);
            row.setLayoutParams(lp);
            row.setBackgroundResource(R.drawable.bg_pill);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent it = new Intent(ConvListActivity.this, ChatActivity.class);
                    it.putExtra(ChatActivity.EXTRA_CONV_NAME, c.name);
                    startActivity(it);
                }
            });
            mConvs.addView(row);
        }
    }

    // ---- ChatEngine.Listener ----

    @Override
    public void onEngineReady() {
        // ⚠️ getInstance 同步回调时 mEngine 尚未赋值 → post 到队尾
        mHint.post(new Runnable() {
            @Override
            public void run() {
                mHint.setText("通道就绪，拉取会话…");
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
    public void onAuth(boolean ok, String message) {
        if (!ok) {
            mHint.setText("⚠ " + message);
            if (mEngine != null) mEngine.fetchConversations();
        }
    }

    @Override
    public void onEngineError(String err) {
        mHint.setText("通道异常: " + err);
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
