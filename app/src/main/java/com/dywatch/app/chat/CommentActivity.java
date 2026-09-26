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

import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.util.AppLog;

import java.util.List;

public class CommentActivity extends UiActivity implements ChatEngine.Listener {

    public static final String EXTRA_AWEME_ID = "aweme_id";

    private LinearLayout mList;
    private android.widget.ScrollView mScroll;
    private TextView mHint;
    private EditText mInput;
    private android.widget.Button mMoreBtn;
    private ChatEngine mEngine;
    private String mAwemeId;
    private int mEmptyRetries;
    /** 已收到的评论累积表（桥每次只回"新增的一批"，追加式渲染才拼得完整） */
    private final List<Comment> mAll = new java.util.ArrayList<>();
    private boolean mLoadingMore;
    private boolean mAtEnd;
    /** 评论发送在途标记（防连点重复发出） */
    private boolean mSending;
    /** 手表内存有限，评论攒到 300 条就停，别无限往下拉 */
    private static final int MAX_COMMENTS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_comment);

        mList = findViewById(R.id.ll_comments);
        mScroll = findViewById(R.id.sv_comments);
        mHint = findViewById(R.id.tv_comment_hint);
        mInput = findViewById(R.id.et_comment);
        // 滚到接近底部就自动续拉，不用用户去点按钮
        mScroll.setOnScrollChangeListener(new android.widget.ScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(android.view.View v, int x, int y, int ox, int oy) {
                if (com.dywatch.app.ui.Settings.commentAutoLoad(CommentActivity.this)) maybeAutoLoadMore();
            }
        });
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
                mSending = false;
                mHint.setVisibility(View.VISIBLE);
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

        mMoreBtn = findViewById(R.id.btn_comments_more);
        mMoreBtn.setVisibility(View.GONE);
        mMoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadMore();
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
        // 闸门：发出去到桥回报结果之间要几秒，期间再点会真发出第二条（评论是写操作，重发不可撤回）
        if (mSending) {
            mHint.setVisibility(View.VISIBLE);
            mHint.setText("上一条还在发送中…");
            return;
        }
        mSending = true;
        mInput.setText("");
        mHint.setVisibility(View.VISIBLE);
        mHint.setText("发送中…");
        mEngine.sendComment(mAwemeId, text);
        mList.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mSending && !isFinishing()) {
                    mSending = false;   // 桥没回音也不能把发送按钮永久锁死
                    mHint.setText("发送无回应，可重试");
                }
            }
        }, 20000);
    }

    /** 全量重绘累积表：分页只动数据源，渲染口径保持单一（两处画列表必然行为漂移） */
    private void render() {
        // 重绘会把 ScrollView 弹回顶部——自动续拉时每来一批就弹一次，用户看着就是"一直在往下读却突然回头"。
        // 先记下位置，重建后再恢复。
        final int keepY = mScroll == null ? 0 : mScroll.getScrollY();
        mList.removeAllViews();
        for (Comment c : mAll) {
            mList.addView(row(c));
        }
        if (keepY > 0 && mScroll != null) {
            mScroll.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) mScroll.scrollTo(0, keepY);
                }
            });
        }
        if (mAll.isEmpty()) {
            ((TextView) findViewById(R.id.tv_page_name)).setText("评论");
            mHint.setVisibility(View.VISIBLE);
            mHint.setText("正在等待评论数据…");
            // 评论区要等 SPA 渲染，桥那边一有内容就会立刻回；这里只做兜底重试
            if (!mAtEnd && mEmptyRetries < 4) {
                mEmptyRetries++;
                mList.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mEngine != null && !isFinishing()) mEngine.fetchComments(mAwemeId);
                    }
                }, 3000);
            } else {
                mHint.setText("暂无评论");
            }
        } else {
            mEmptyRetries = 0;
            // 页名兼当计数条：有内容就不占独立一行提示，把手表那点高度全留给评论
            TextView name = findViewById(R.id.tv_page_name);
            name.setText("评论 " + mAll.size());
            mHint.setVisibility(View.GONE);
            mList.addView(footer());
        }
        boolean auto = com.dywatch.app.ui.Settings.commentAutoLoad(this);
        mMoreBtn.setVisibility((auto || mAll.isEmpty() || mAtEnd) ? View.GONE : View.VISIBLE);
        mMoreBtn.setText(mLoadingMore ? "加载中…" : "加载更多评论");
        mMoreBtn.setEnabled(!mLoadingMore);
        if (auto) maybeAutoLoadMore();
    }

    private TextView footer() {
        TextView tv = new TextView(this);
        tv.setText(mAtEnd ? "已经到底了" : (mLoadingMore ? "正在加载更多…" : "继续下滑加载更多"));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.t_caption));
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_muted));
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        return tv;
    }

    /**
     * 无缝续拉：内容填不满屏幕时永远触发不了滚动事件，所以每次渲染完都要主动判一次，
     * 否则"自动加载"会卡在首屏那几条上。
     */
    private void maybeAutoLoadMore() {
        if (mLoadingMore || mAtEnd || mEngine == null) return;
        if (mAll.size() >= MAX_COMMENTS) { mAtEnd = true; return; }
        mScroll.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || mLoadingMore || mAtEnd) return;
                View child = mScroll.getChildAt(0);
                if (child == null) return;
                boolean nearBottom = mScroll.getScrollY() + mScroll.getHeight()
                        >= child.getHeight() - dp(64);
                if (nearBottom) loadMore();
            }
        });
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /**
     * 一条评论：昵称 / 时间+赞数 / 正文 三级分层，画法与会话列表共用 Rows。
     * 实测偶发昵称抓空（桥只取 info-wrap 的 textContent），空着会让整行看起来像坏了，给个兜底。
     */
    private View row(Comment c) {
        String meta = c.time + (c.likes.isEmpty() ? "" : ("  ·  " + c.likes + " 赞"));
        return com.dywatch.app.ui.Rows.card(this,
                c.name.isEmpty() ? "匿名" : c.name, meta, c.text);
    }

    /** 翻页：让引擎把评论区滚一页，新渲染出来的那批经 onCommentsMore 追加回来 */
    private void loadMore() {
        if (mLoadingMore || mAtEnd || mEngine == null) return;
        mLoadingMore = true;
        render();
        mEngine.loadMoreComments(mAwemeId);
        // 兜底：桥那边若没回音（页面异常/到底），30s 后放开按钮，别把用户卡死
        mList.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mLoadingMore && !isFinishing()) {
                    mLoadingMore = false;
                    render();
                }
            }
        }, 30000);
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
        mAll.clear();
        mAll.addAll(list);
        mAtEnd = false;
        mLoadingMore = false;
        render();
    }

    @Override
    public void onCommentsMore(List<Comment> list, boolean atEnd) {
        mLoadingMore = false;
        mAll.addAll(list);
        // 只认引擎报来的"滚动位置真到底"；这次没抓到新内容不等于没有更多了
        mAtEnd = atEnd;
        render();
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
    protected void onResume() {
        super.onResume();
        ChatEngine.attachTo(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ChatEngine.detachFrom(this);
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
