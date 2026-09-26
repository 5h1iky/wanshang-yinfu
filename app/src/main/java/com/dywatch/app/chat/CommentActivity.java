package com.dywatch.app.chat;

// 视频评论页（用户方案：互动全走 WebView 引擎，全局单例复用）。
// 收 = DOM 抓取评论列表（昵称/文本/时间/赞数）；发 = 激活 Draft.js 框 → 粘贴注入 → 回车。
// 验证友好：评论内容肉眼可见（用户点名优先做评论的原因）。

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.util.AppLog;

import java.util.List;

public class CommentActivity extends AppCompatActivity implements ChatEngine.Listener {

    public static final String EXTRA_AWEME_ID = "aweme_id";

    private LinearLayout mList;
    private TextView mHint;
    private EditText mInput;
    private ChatEngine mEngine;
    private String mAwemeId;
    private int mEmptyRetries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comment);

        mList = findViewById(R.id.ll_comments);
        mHint = findViewById(R.id.tv_comment_hint);
        mInput = findViewById(R.id.et_comment);
        mAwemeId = getIntent().getStringExtra(EXTRA_AWEME_ID);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        if (mAwemeId == null || mAwemeId.isEmpty()) {
            mHint.setText("示例视频没有评论（去刷真实视频）");
            return;
        }

        mEngine = ChatEngine.getInstance(this, this);
        mEngine.setActionListener(new ChatEngine.ActionListener() {
            @Override
            public void onActionResult(String action, boolean ok, String detail) {
                mHint.setText(ok ? "已发送，刷新中…" : ("发送失败: " + detail));
                if (ok && mEngine != null) {
                    mList.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mEngine != null && !isFinishing()) mEngine.fetchComments(mAwemeId);
                        }
                    }, 1500);
                }
            }
        });

        findViewById(R.id.btn_send_comment).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });

        // 键盘"发送/完成"键直接提交（手表键盘点按不便，IME 动作键是主通道）
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    send();
                    return true;
                }
                return false;
            }
        });

        AppLog.i("chat", "评论页打开 aweme=" + mAwemeId);
    }

    private void send() {
        String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        mInput.setText("");
        mHint.setText("发送中…");
        mEngine.sendComment(mAwemeId, text);
    }

    private void render(List<Comment> list) {
        mList.removeAllViews();
        if (list.isEmpty()) {
            mHint.setText("正在等待评论数据…（自动重试）");
            // 评论区懒加载：页面刚就绪时常为空 → 自动重试
            if (mEmptyRetries < 6) {
                mEmptyRetries++;
                mList.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEngine != null && !isFinishing()) mEngine.fetchComments(mAwemeId);
                    }
                }, 4000);
            } else {
                mHint.setText("暂无评论");
            }
            return;
        }
        mEmptyRetries = 0;
        mHint.setText("共 " + list.size() + " 条评论");
        for (Comment c : list) {
            TextView tv = new TextView(this);
            String head = c.name + (c.time.isEmpty() ? "" : (" · " + c.time))
                    + (c.likes.isEmpty() ? "" : ("  ❤" + c.likes));
            tv.setText(head + "\n" + c.text);
            tv.setTextSize(13);
            tv.setTextColor(Color.WHITE);
            tv.setPadding(16, 14, 16, 14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 8);
            tv.setLayoutParams(lp);
            tv.setBackgroundColor(0xFF1A222C);
            mList.addView(tv);
        }
    }

    // ---- ChatEngine.Listener ----

    @Override
    public void onEngineReady() {
        // getInstance 同步回调时 mEngine 未赋值 → post 队尾
        mHint.post(new Runnable() {
            @Override
            public void run() {
                mHint.setText("通道就绪，拉取评论…");
                if (mEngine != null) mEngine.fetchComments(mAwemeId);
            }
        });
    }

    @Override
    public void onComments(List<Comment> list) {
        render(list);
    }

    @Override
    public void onMessages(List<ChatMessage> list) {
    }

    @Override
    public void onConversations(List<Conversation> list) {
    }

    @Override
    public void onAuth(boolean ok, String message) {
        if (!ok) {
            mHint.setText("⚠ " + message);
        }
    }

    @Override
    public void onEngineError(String err) {
        mHint.setText("通道异常: " + err);
        AppLog.i("chat", "评论页异常: " + err);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mEngine != null) {
            mEngine.setActionListener(null);
            mEngine.setListenerDetached(this);
        }
    }
}
