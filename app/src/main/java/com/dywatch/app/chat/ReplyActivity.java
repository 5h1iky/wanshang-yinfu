package com.dywatch.app.chat;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.ui.DyDialog;
import com.dywatch.app.ui.Fonts;
import com.dywatch.app.ui.Loading;
import com.dywatch.app.ui.RowAdapter;
import com.dywatch.app.ui.Settings;
import com.dywatch.app.ui.UiActivity;
import com.dywatch.app.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单条评论的回复页。列表走只读 API，发送只走引擎的公开评论回复动作。 */
public class ReplyActivity extends UiActivity {

    public static final String EXTRA_AWEME_ID = "aweme_id";
    public static final String EXTRA_ROOT_COMMENT = "root_comment";

    private static final int PAGE_SIZE = 20;
    private static final int MAX_REPLIES = 300;
    private static final long SEND_TIMEOUT_MS = 20000L;

    private RecyclerView mList;
    private RowAdapter mAdapter;
    private TextView mHint;
    private TextView mFooter;
    private TextView mRootName;
    private TextView mRootMeta;
    private TextView mRootText;
    private TextView mRootAction;
    private TextView mTargetLabel;
    private LinearLayout mTargetBar;
    private EditText mInput;
    private ImageButton mSend;
    private Button mMore;

    private String mAwemeId;
    private Comment mRoot;
    /** null = 回复根评论；非 null = 回复列表中的某条回复。 */
    private Comment mTarget;
    private final List<Comment> mAll = new ArrayList<>();
    private final Set<String> mKnownReplyIds = new HashSet<>();

    private DouyinApi mApi;
    private ChatEngine mEngine;
    private long mCursor;
    private boolean mHasMore = true;
    private boolean mLoading;
    private boolean mLoadFailed;
    private boolean mRefreshPending;
    private boolean mDestroyed;
    private int mPageGeneration;
    private final Runnable mRefreshLoad = new Runnable() {
        @Override
        public void run() {
            if (!alive() || !mRefreshPending) return;
            if (mLoading) {
                // 不取消旧请求，也不与旧请求并发；旧结果落屏后再刷新。
                mList.postDelayed(this, 500L);
                return;
            }
            mRefreshPending = false;
            mAll.clear();
            mKnownReplyIds.clear();
            mAdapter.submitList(new ArrayList<RowAdapter.Item>());
            fetchReplies(0, false);
        }
    };

    private boolean mSending;
    private String mSendReqId;
    private ChatEngine.ActionListener mSendListener;
    private String mPendingText;
    private String mPendingReplyToReplyId;
    private final Runnable mSendTimeout = new Runnable() {
        @Override
        public void run() {
            if (!mSending || !alive()) return;
            // 只撤销结果回调并解除防连点锁，不自动重发；迟到结果不会再次清空输入。
            cancelSendCallback();
            mSending = false;
            clearPendingSend();
            updateSendState();
            hint("发送无回应，请确认回复列表后重试");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reply);
        setPageTitle("回复");

        mList = findViewById(R.id.rv_replies);
        mHint = findViewById(R.id.tv_reply_hint);
        mFooter = findViewById(R.id.tv_reply_footer);
        mRootName = findViewById(R.id.tv_root_name);
        mRootMeta = findViewById(R.id.tv_root_meta);
        mRootText = findViewById(R.id.tv_root_text);
        mRootAction = findViewById(R.id.tv_root_action);
        mTargetLabel = findViewById(R.id.tv_reply_target);
        mTargetBar = findViewById(R.id.reply_target_bar);
        mInput = findViewById(R.id.et_reply);
        mSend = findViewById(R.id.btn_send_reply);
        mMore = findViewById(R.id.btn_replies_more);

        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new RowAdapter(new RowAdapter.OnRowClick() {
            @Override
            public void onClick(Object payload) {
                if (!(payload instanceof Comment) || mSending) return;
                Comment next = (Comment) payload;
                // 再点当前对象即切回根评论，方便手表上纠错。
                setTarget(mTarget != null && sameReply(mTarget, next) ? null : next);
            }
        });
        mList.setAdapter(mAdapter);
        Fonts.scale(mInput, R.dimen.t_body);
        Fonts.scale(mRootName, R.dimen.t_body);
        Fonts.scale(mRootText, R.dimen.t_body);

        mAwemeId = getIntent().getStringExtra(EXTRA_AWEME_ID);
        mRoot = getRootComment();
        if (TextUtils.isEmpty(mAwemeId) || mRoot == null || TextUtils.isEmpty(mRoot.cid)) {
            mRootName.setText("无法打开回复");
            mRootText.setText("原评论信息不完整，请返回后重试");
            mRootAction.setVisibility(View.GONE);
            mInput.setEnabled(false);
            mSend.setEnabled(false);
            mMore.setVisibility(View.GONE);
            findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            hint("原评论信息不完整");
            return;
        }

        bindRoot();
        setTarget(null);
        if (mRoot.previewReplies != null && !mRoot.previewReplies.isEmpty()) {
            appendUnique(new ArrayList<>(mRoot.previewReplies));
            updateListState();
        }
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.root_comment).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mSending) setTarget(null);
            }
        });
        findViewById(R.id.btn_cancel_reply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mSending) setTarget(null);
            }
        });
        mSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSend();
            }
        });
        mMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadMore();
            }
        });
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    requestSend();
                    return true;
                }
                return false;
            }
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (Settings.commentAutoLoad(ReplyActivity.this)) maybeAutoLoadMore();
            }
        });

        fetchReplies(0, false);
        AppLog.i("chat", "回复页打开，原评论标识已就绪");
    }

    private Comment getRootComment() {
        try {
            Object value = getIntent().getSerializableExtra(EXTRA_ROOT_COMMENT);
            return value instanceof Comment ? (Comment) value : null;
        } catch (RuntimeException e) {
            AppLog.i("chat", "回复页原评论反序列化失败");
            return null;
        }
    }

    private void bindRoot() {
        mRootName.setText(displayName(mRoot));
        mRootMeta.setText(meta(mRoot));
        mRootText.setText(mRoot.text);
        setPageTitle("回复 " + (mRoot.replyCount > 0 ? mRoot.replyCount : ""));
    }

    private void setTarget(Comment target) {
        mTarget = target;
        mTargetBar.setVisibility(target == null ? View.GONE : View.VISIBLE);
        mTargetLabel.setText(target == null ? "" : "回复 " + displayName(target) + " ");
        mRootAction.setVisibility(target == null ? View.GONE : View.VISIBLE);
        mInput.setHint(target == null ? "回复原评论…" : "回复 " + displayName(target) + "…");
    }

    private void fetchReplies(final long cursor, final boolean append) {
        if (!alive() || mLoading || (append && !mHasMore)) return;
        mLoading = true;
        mLoadFailed = false;
        final int generation = mPageGeneration;
        if (append) {
            updateListState();
        } else {
            hint("正在拉取回复…");
            Loading.show(this, true);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DouyinApi api = ensureApi();
                    final List<Comment> page = api.fetchCommentReplies(mAwemeId, mRoot.cid,
                            cursor, PAGE_SIZE);
                    final boolean more = api.lastCommentHasMore;
                    final long next = api.lastCommentCursor;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive() || generation != mPageGeneration) return;
                            mLoading = false;
                            Loading.show(ReplyActivity.this, false);
                            mHasMore = more;
                            mCursor = next > 0 ? next : cursor + page.size();
                            appendUnique(page == null ? new ArrayList<Comment>() : page);
                            AppLog.i("chat", "回复直连 " + (page == null ? 0 : page.size())
                                    + " 条 cursor=" + cursor + " next=" + mCursor
                                    + " more=" + more + " total=" + mAll.size());
                            updateListState();
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("chat", "回复读取失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive() || generation != mPageGeneration) return;
                            mLoading = false;
                            Loading.show(ReplyActivity.this, false);
                            // 一级评论自带首条回复预览；分页接口被上游限制时先保留预览，
                            // 明说“更多暂不可用”，不伪造条目也不把已有内容清空。
                            if (!mAll.isEmpty()) {
                                mHasMore = false;
                                updateListState();
                                hint("已显示现有回复；更多回复暂不可用");
                            } else {
                                mHasMore = false;
                                mLoadFailed = true;
                                updateListState();
                                hint("回复加载失败：" + DouyinApi.friendlyMessage(e));
                            }
                        }
                    });
                }
            }
        }, "reply-api").start();
    }

    private void appendUnique(List<Comment> page) {
        List<RowAdapter.Item> items = new ArrayList<>();
        for (Comment c : page) {
            if (c == null || TextUtils.isEmpty(c.cid) || !mKnownReplyIds.add(c.cid)) continue;
            mAll.add(c);
            items.add(new RowAdapter.Item(replyTitle(c), meta(c), c.text, c));
            if (mAll.size() >= MAX_REPLIES) {
                mHasMore = false;
                break;
            }
        }
        mAdapter.append(items);
    }

    private String replyTitle(Comment c) {
        String title = displayName(c);
        if (!TextUtils.isEmpty(c.replyToName)) title += " 回复 " + c.replyToName;
        return title;
    }

    private static String displayName(Comment c) {
        return TextUtils.isEmpty(c.name) ? "匿名" : c.name;
    }

    private static String meta(Comment c) {
        boolean hasLikes = !TextUtils.isEmpty(c.likes) && !"0".equals(c.likes.trim());
        String time = c.time == null ? "" : c.time;
        return time + (hasLikes ? "  ·  " + c.likes + " 赞" : "");
    }

    private static boolean sameReply(Comment a, Comment b) {
        return a == b || (a != null && b != null && !TextUtils.isEmpty(a.cid)
                && a.cid.equals(b.cid));
    }

    private void loadMore() {
        if (!alive() || mLoading || (!mHasMore && !mLoadFailed)) return;
        if (mLoadFailed) {
            mLoadFailed = false;
            fetchReplies(0, false);
            return;
        }
        if (mAll.size() >= MAX_REPLIES) {
            mHasMore = false;
            updateListState();
            return;
        }
        fetchReplies(mCursor, true);
    }

    private void maybeAutoLoadMore() {
        if (!alive() || mLoading || !mHasMore) return;
        final LinearLayoutManager lm = (LinearLayoutManager) mList.getLayoutManager();
        if (lm == null) return;
        mList.post(new Runnable() {
            @Override
            public void run() {
                if (!alive() || mLoading || !mHasMore) return;
                int count = mAdapter.getItemCount();
                int last = lm.findLastVisibleItemPosition();
                if (count == 0 || last >= count - 3) loadMore();
            }
        });
    }

    private void updateListState() {
        if (mAll.isEmpty()) {
            setPageTitle("回复 " + (mRoot != null && mRoot.replyCount > 0 ? mRoot.replyCount : ""));
            if (!mLoading) hint(mHasMore ? "暂无回复" : "暂无回复，发条回复吧");
            mFooter.setVisibility(View.GONE);
        } else {
            setPageTitle("回复 " + mAll.size());
            if (!mLoading) clearHint();
            mFooter.setText(mLoading ? "正在加载更多…"
                    : (mHasMore ? "继续下滑加载更多" : "已经到底了"));
            mFooter.setVisibility(View.VISIBLE);
        }
        boolean auto = Settings.commentAutoLoad(this);
        // 失败必须留人工重试入口，不能靠滚动监听猜测是否该重新请求。
        mMore.setVisibility((mLoadFailed || (!auto && !mAll.isEmpty() && mHasMore))
                ? View.VISIBLE : View.GONE);
        mMore.setEnabled(!mLoading);
        mMore.setText(mLoading ? "加载中…" : (mLoadFailed ? "重试加载回复" : "加载更多回复"));
        if (auto && !mLoadFailed) maybeAutoLoadMore();
    }

    /** 公开发送入口：先确认，只有确认回调才会登记引擎写动作。 */
    private void requestSend() {
        if (!alive() || mSending) {
            if (mSending) hint("上一条还在发送中…");
            return;
        }
        final String text = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        // 在弹窗前固定目标与参数，用户不能借着确认框改变这次公开发送的收件对象。
        final Comment target = mTarget;
        final String replyToReplyId = target == null ? "" : target.cid;
        if (target != null && TextUtils.isEmpty(replyToReplyId)) {
            hint("回复对象信息不完整，请重新选择");
            return;
        }
        final String targetName = target == null ? displayName(mRoot) : displayName(target);
        mSending = true;   // 弹窗展示期间也挡住连点与 IME 重复提交
        updateSendState();
        DyDialog.Opt opt = new DyDialog.Opt()
                .title("确认公开发送")
                .body("回复 " + targetName + "\n\n" + text)
                .positive("发送", new Runnable() {
                    @Override
                    public void run() {
                        beginSend(text, replyToReplyId, target == null ? mRoot : target);
                    }
                })
                .negative("取消", null)
                .onDismiss(new Runnable() {
                    @Override
                    public void run() {
                        // 若 positive 已调用 beginSend，mSending 仍为 true，不能误解锁。
                        if (alive() && mSendListener == null) {
                            mSending = false;
                            updateSendState();
                        }
                    }
                });
        if (DyDialog.show(this, opt) == null) {
            mSending = false;
            updateSendState();
        }
    }

    private void beginSend(String text, String replyToReplyId, final Comment target) {
        if (!alive() || !mSending || mSendListener != null) return;
        mPendingText = text;
        mPendingReplyToReplyId = replyToReplyId;
        try {
            // 取引擎时不再传 Listener，避免顶掉其它页面的全局监听。
            mEngine = ChatEngine.getInstance(getApplicationContext(), null);
            mSendListener = new ChatEngine.ActionListener() {
                @Override
                public void onActionResult(String action, boolean ok, String detail) {
                    // 超时/销毁后，或重复到达的旧结果一律忽略。
                    if (mSendListener != this) return;
                    mList.removeCallbacks(mSendTimeout);
                    mSendReqId = null;
                    mSendListener = null;
                    mSending = false;
                    clearPendingSend();
                    if (!alive()) return;
                    updateSendState();
                    if (!ok) {
                        hint("发送失败：" + friendlyActionMessage(action, detail));
                        return;
                    }
                    mInput.setText("");
                    setTarget(null);
                    hint("已发送，刷新回复…");
                    refreshAfterSend();
                }
            };
            // 回调先就绪再分配请求号：即便引擎同步回调，销毁/超时注销也有 id 可用。
            String targetName = displayName(target);
            String targetText = target.text;
            String prefix = targetText == null ? "" :
                    targetText.substring(0, Math.min(12, targetText.length()));
            mSendReqId = mEngine.sendCommentReply(mAwemeId, text, mRoot.cid,
                    replyToReplyId, targetName, prefix, mSendListener);
            mList.postDelayed(mSendTimeout, SEND_TIMEOUT_MS);
        } catch (Exception e) {
            cancelSendCallback();
            mSendListener = null;
            mSending = false;
            clearPendingSend();
            if (!alive()) return;
            updateSendState();
            hint("发送失败：" + DouyinApi.friendlyMessage(e));
        }
    }

    private static String friendlyActionMessage(String action, String detail) {
        if (TextUtils.isEmpty(detail)) return "操作未完成，请稍后重试";
        return DouyinApi.friendlyMessage(new IOException(action + ": " + detail));
    }

    private void refreshAfterSend() {
        if (!alive()) return;
        mRefreshPending = true;
        mCursor = 0;
        mHasMore = true;
        // 给服务端列表一点时间；旧请求在途时等待其完成，绝不丢掉刷新。
        mList.removeCallbacks(mRefreshLoad);
        mList.postDelayed(mRefreshLoad, 1200L);
    }

    private void updateSendState() {
        mSend.setEnabled(!mSending);
        mInput.setEnabled(!mSending);
        mMore.setEnabled(!mLoading);
        mRootAction.setEnabled(!mSending);
    }

    private boolean alive() {
        return !mDestroyed && !isFinishing() && !isDestroyed();
    }

    private void hint(String text) {
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        mHint.setVisibility(View.GONE);
    }

    private void clearPendingSend() {
        mPendingText = null;
        mPendingReplyToReplyId = null;
    }

    private void cancelSendCallback() {
        if (mEngine != null && mSendReqId != null) mEngine.cancelAction(mSendReqId);
        mSendReqId = null;
        mSendListener = null;
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        mApi = new DouyinApi(new com.dywatch.app.sign.Signer(Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")))));
        mApi.setSessionCookie(com.dywatch.app.login.LoginManager.getCookies(this));
        return mApi;
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                if (n > 0) out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mPageGeneration++;
        mList.removeCallbacks(mSendTimeout);
        mList.removeCallbacks(mRefreshLoad);
        cancelSendCallback();
        clearPendingSend();
        // 本页从未注册全局 Listener，无需解绑；只注销本次发送的一次性回调。
        super.onDestroy();
    }
}
