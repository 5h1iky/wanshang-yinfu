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

import com.dywatch.app.ui.RowAdapter;
import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.util.AppLog;

import java.util.List;

public class CommentActivity extends UiActivity implements ChatEngine.Listener {

    public static final String EXTRA_AWEME_ID = "aweme_id";

    private androidx.recyclerview.widget.RecyclerView mList;
    private com.dywatch.app.ui.RowAdapter mAdapter;
    private TextView mHint;
    private TextView mFooter;
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

        mList = findViewById(R.id.rv_comments);
        mList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new com.dywatch.app.ui.RowAdapter(null);
        mList.setAdapter(mAdapter);
        mHint = findViewById(R.id.tv_comment_hint);
        mFooter = findViewById(R.id.tv_comment_footer);
        mInput = findViewById(R.id.et_comment);
        // 方案 C：输入框 = 正文面，吃「字体大小」
        com.dywatch.app.ui.Fonts.scale(mInput, R.dimen.t_body);
        // 滚到接近底部就自动续拉，不用用户去点按钮
        mList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv,
                                   int dx, int dy) {
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
            hint("示例视频没有评论（去刷真实视频）");
            return;
        }

        mEngine = ChatEngine.getInstance(this, this);
        // 直连是主路（只读接口风控宽松、结构稳定）；引擎 DOM 抓取只在直连失败时兜底
        fetchCommentsFromApi(0, false);
        mEngine.setActionListener(new ChatEngine.ActionListener() {
            @Override
            public void onActionResult(String action, boolean ok, String detail) {
                mSending = false;
                mHint.setVisibility(View.VISIBLE);
                mHint.setText(ok ? "已发送，刷新中…" : ("发送失败: " + detail));
                if (ok) {
                    mList.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            // 发完刷新：按当前数据源走（直连就直连，回退了就走引擎）
                            if (mApiMode) {
                                mApiCursor = 0;
                                mApiLoading = false;
                                fetchCommentsFromApi(0, false);
                            } else if (mEngine != null) {
                                mEngine.fetchComments(mAwemeId);
                            }
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

        com.dywatch.app.ui.QuickReply.wire(this,
                new String[]{"好看！", "求BGM", "赞了", "哈哈哈"},
                new com.dywatch.app.ui.QuickReply.Pick() {
                    @Override
                    public void onPick(String t) {
                        // 只填入、不直发：评论是公开发到别人视频下的，误触代价和聊天不对等
                        mInput.setText(t);
                        mInput.setSelection(t.length());
                        mInput.requestFocus();
                        // 用 Toast 而不是状态行：render() 每来一批就会把提示行收掉，
                        // 写在那儿会被下一次自动加载冲掉（真机实测到就是这个现象）。
                        android.widget.Toast.makeText(CommentActivity.this,
                                "已填入，按发送发布", android.widget.Toast.LENGTH_SHORT).show();
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

    /**
     * 渲染：只更新页名/按钮/提示这些"外壳"，列表内容交给 RowAdapter 按语义通知。
     *
     * 旧版这里是 removeAllViews + 逐条 addView 的全量重建（300 条就要 new 300 个
     * LinearLayout + 900 个 TextView），还要靠 keepY hack 把滚动位置救回来。
     * 现在评论分页是纯追加，走 notifyItemRangeInserted —— 位置天然不丢，
     * 也不再每来一批就把整屏视图重搭一遍。
     */
    private void render() {
        if (mAll.isEmpty()) {
            setPageTitle("评论");
            hint("正在等待评论数据…");
            // 空态兜底重试：直连模式下重发直连，回退模式下才找引擎
            // （直连是同步返回的，空只可能是真没评论或刚进来还没到，重试一次够）
            if (!mAtEnd && mEmptyRetries < 4) {
                mEmptyRetries++;
                com.dywatch.app.ui.Loading.show(this, true);   // 还在重试 = 仍在加载，圆圈继续转
                mList.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (mApiMode) {
                            mApiLoading = false;
                            fetchCommentsFromApi(0, false);
                        } else if (mEngine != null) {
                            mEngine.fetchComments(mAwemeId);
                        }
                    }
                }, 3000);
            } else {
                hint("暂无评论");
                com.dywatch.app.ui.Loading.show(this, false);
            }
        } else {
            mEmptyRetries = 0;
            // 页名兼当计数条：有内容就不占独立一行提示，把手表那点高度全留给评论
            setPageTitle("评论 " + mAll.size());
            clearHint();
            com.dywatch.app.ui.Loading.show(this, false);
        }
        setFooter(mAtEnd ? "已经到底了" : (mLoadingMore ? "正在加载更多…" : "继续下滑加载更多"));
        boolean auto = com.dywatch.app.ui.Settings.commentAutoLoad(this);
        mMoreBtn.setVisibility((auto || mAll.isEmpty() || mAtEnd) ? View.GONE : View.VISIBLE);
        mMoreBtn.setText(mLoadingMore ? "加载中…" : "加载更多评论");
        mMoreBtn.setEnabled(!mLoadingMore);
        if (auto) maybeAutoLoadMore();
    }

    /**
     * 列表尾部提示（加载中 / 到底了）。
     * 从"往 LinearLayout 末尾塞一个 TextView"改成独立的一行：ScrollView 时代那样做
     * 会被下一次 removeAllViews 冲掉，RV 里则由 footer 自己持有。
     */
    private void setFooter(String text) {
        if (mFooter == null) return;
        mFooter.setText(text);
        mFooter.setVisibility(mAll.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void hint(String text) {
        if (mHint == null) return;
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(View.GONE);
    }

    /** Comment -> 行数据（昵称兜底"匿名"照旧：桥偶尔抓空昵称，空着整行像坏了） */
    private RowAdapter.Item toItem(Comment c) {
        // 0 赞不显示：满屏"· 0 赞"是噪音，手表上每个字符都要有信息量
        boolean hasLikes = !c.likes.isEmpty() && !"0".equals(c.likes.trim());
        String meta = c.time + (hasLikes ? ("  ·  " + c.likes + " 赞") : "");
        return new RowAdapter.Item(c.name.isEmpty() ? "匿名" : c.name, meta, c.text, c);
    }

    /**
     * 无缝续拉：内容填不满屏幕时永远触发不了滚动事件，所以每次渲染完都要主动判一次，
     * 否则"自动加载"会卡在首屏那几条上。
     *
     * RV 版判底：问 LayoutManager 最后一个可见项是不是接近末尾——
     * 比 ScrollView 那套 getScrollY()+getHeight() 更准（回收后子视图高度不代表内容高度）。
     */
    private void maybeAutoLoadMore() {
        // ⚠️ 这里的守卫不能依赖 mEngine：直连模式下评论根本不经引擎，
        //    写成 mEngine == null 就直接返回，自动续拉永远不触发（真机踩到过）。
        if (mLoadingMore || mApiLoading || mAtEnd) return;
        if (mAll.size() >= MAX_COMMENTS) { mAtEnd = true; return; }
        final androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) mList.getLayoutManager();
        if (lm == null) return;
        mList.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || mLoadingMore || mApiLoading || mAtEnd) return;
                int last = lm.findLastVisibleItemPosition();
                int count = mAdapter.getItemCount();
                // 末尾 3 条内就算"近底"（手表一屏四五条，阈值给大点才续得上）；
                // 空列表也放行，否则首屏永远触发不到
                if (count == 0 || last >= count - 3) loadMore();
            }
        });
    }

    /**
     * 拉评论：优先走原生直连（只读接口，风控宽松、结构稳定、带真分页）。
     * 直连失败才回退 WebView 引擎的 DOM 抓取——那条路脆（类名是构建期 hash 拼接），
     * 但它是"没得选时的兜底"，不是主路。
     */
    private void fetchCommentsFromApi(final long cursor, final boolean append) {
        if (mApiLoading) return;
        mApiLoading = true;
        hint(append ? "正在加载更多…" : "正在拉取评论…");
        // 只在首屏（列表还空着）时转圈：翻页时列表已有内容，居中大转圈反而挡视线，
        // 那一步的进度由 footer 的"正在加载更多…"表达。
        if (!append) com.dywatch.app.ui.Loading.show(this, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final com.dywatch.app.net.DouyinApi api = ensureApi();
                    final List<Comment> list = api.fetchComments(mAwemeId, cursor, 20);
                    final boolean more = api.lastCommentHasMore;
                    final long next = api.lastCommentCursor;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mApiLoading = false;
                            mApiCursor = next > 0 ? next : mApiCursor + list.size();
                            com.dywatch.app.util.AppLog.i("chat", "评论直连 " + list.size()
                                    + " 条（cursor=" + cursor + " 下一页=" + mApiCursor
                                    + " 还有=" + more + " 累计=" + (mAll.size() + list.size()) + "）");
                            if (append) {
                                onCommentsMore(list, !more);
                            } else {
                                onComments(list);
                                mAtEnd = !more;
                                render();
                            }
                            mApiMode = true;   // 直连成功过，后续翻页也走直连
                        }
                    });
                } catch (final Exception e) {
                    com.dywatch.app.util.AppLog.i("chat", "评论直连失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mApiLoading = false;
                            mApiMode = false;
                            // 兜底：走引擎 DOM 抓取
                            if (append) {
                                if (mEngine != null) mEngine.loadMoreComments(mAwemeId);
                            } else if (mEngine != null) {
                                mEngine.fetchComments(mAwemeId);
                            } else {
                                hint("评论加载失败: " + e.getMessage());
                                com.dywatch.app.ui.Loading.show(CommentActivity.this, false);
                            }
                        }
                    });
                }
            }
        }, "comment-api").start();
    }

    /** 直连模式：true=走 API，false=回退引擎 DOM 抓取 */
    private boolean mApiMode = true;
    private boolean mApiLoading;
    private long mApiCursor;
    private com.dywatch.app.net.DouyinApi mApi;

    private com.dywatch.app.net.DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        mApi = new com.dywatch.app.net.DouyinApi(new com.dywatch.app.sign.Signer(
                java.util.Arrays.asList(
                        readAll(getAssets().open("sign/utils.js")),
                        readAll(getAssets().open("sign/sm3.js")),
                        readAll(getAssets().open("sign/vm_decode.js")))));
        mApi.setSessionCookie(com.dywatch.app.login.LoginManager.getCookies(this));
        return mApi;
    }

    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 翻页：让引擎把评论区滚一页，新渲染出来的那批经 onCommentsMore 追加回来 */
    private void loadMore() {
        // 直连模式用 cursor 翻页，不用去页面上"滚一屏骗出新评论"
        if (mApiMode) {
            if (mApiLoading || mAtEnd) return;
            fetchCommentsFromApi(mApiCursor, true);
            return;
        }
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
        // 首批/刷新：整批替换
        mAll.clear();
        mAll.addAll(list);
        mAtEnd = false;
        mLoadingMore = false;
        mAdapter.clear();
        java.util.List<RowAdapter.Item> items = new java.util.ArrayList<>();
        for (Comment c : mAll) items.add(toItem(c));
        mAdapter.append(items);
        render();
    }

    @Override
    public void onCommentsMore(List<Comment> list, boolean atEnd) {
        mLoadingMore = false;
        if (!list.isEmpty()) {
            mAll.addAll(list);
            // 追加而非全量替换：位置天然不丢，也不用把整屏视图重建一遍
            java.util.List<RowAdapter.Item> items = new java.util.ArrayList<>();
            for (Comment c : list) items.add(toItem(c));
            mAdapter.append(items);
        }
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
            // ⚠️ 2026-09-27 修：原来只 mHint.setText() 没置 VISIBLE —— 提示行默认是 GONE 的，
            //    于是"登录失效"这种情况下用户**什么都看不到**（页面上没有任何反馈）。
            hint("⚠ " + message);
            com.dywatch.app.ui.Loading.show(this, false);
        }
    }

    @Override
    public void onEngineError(String err) {
        hint("通道异常: " + err);   // 同上：必须走 hint() 才会真的显示出来
        com.dywatch.app.ui.Loading.show(this, false);
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
