package com.dywatch.app.ui;

// 作者主页：作品列表 + 用户信息。
//
// 纯只读：/aweme/v1/web/aweme/post/（作品，实测 5 条）+ /aweme/v1/web/user/profile/other/（信息）。
// 都走原生 API 直连——风控严的是写入，读取宽松（本项目 2026-09-26 实测结论）。
// 播放复用 FeedActivity 的"预置列表"模式，不必再写一套播放页。

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.sign.Signer;
import com.dywatch.app.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UserActivity extends UiActivity {

    public static final String EXTRA_SEC_UID = "sec_uid";
    public static final String EXTRA_NAME = "name";

    private androidx.recyclerview.widget.RecyclerView mList;
    private RowAdapter mAdapter;
    private TextView mHint, mName, mStats;
    private DouyinApi mApi;
    private String mSecUid;
    /** 翻页状态（滚到底自动续拉） */
    private boolean mPostsHasMore;
    private boolean mLoadingPosts;
    private long mPostsCursor;
    /** 当前作品列表（点条目进播放页要用） */
    private final List<FeedVideo> mVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        mSecUid = getIntent().getStringExtra(EXTRA_SEC_UID);
        if (mSecUid == null) mSecUid = "";
        setPageTitle("主页");

        mList = findViewById(R.id.rv_user);
        mList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mAdapter = new RowAdapter(new RowAdapter.OnRowClick() {
            @Override
            public void onClick(Object payload) {
                if (payload instanceof Integer) playAt((Integer) payload);
            }
        });
        mList.setAdapter(mAdapter);
        wireAutoLoadMore();

        mHint = findViewById(R.id.tv_user_hint);
        mName = findViewById(R.id.tv_user_name);
        mStats = findViewById(R.id.tv_user_stats);

        // 先用上一页带来的昵称占位，避免头部空着（信息接口要等一下）
        String name = getIntent().getStringExtra(EXTRA_NAME);
        mName.setText(name == null || name.isEmpty() ? "作者" : name);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        if (mSecUid.isEmpty()) {
            hint("拿不到作者标识，无法打开主页");
            return;
        }
        load();
    }

    private void load() {
        hint("正在拉取作品…");
        com.dywatch.app.ui.Loading.show(this, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DouyinApi api = ensureApi();
                    // 两个请求都做，但任何一个失败都不该让整页空白
                    final List<FeedVideo> posts = api.fetchUserPosts(mSecUid, 0);
                    DouyinApi.UserInfo info = null;
                    try {
                        info = api.fetchUserInfo(mSecUid);
                    } catch (Exception e) {
                        AppLog.i("user", "作者信息失败（不影响作品列表）: " + e);
                    }
                    final DouyinApi.UserInfo finfo = info;
                    final boolean more = api.lastPostsHasMore;
                    final long next = api.lastPostsCursor;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            if (finfo != null) {
                                mName.setText(finfo.nickname.isEmpty() ? "作者" : finfo.nickname);
                                StringBuilder sb = new StringBuilder();
                                sb.append("粉丝 ").append(FeedVideo.formatCount(finfo.followerCount));
                                sb.append(" · 作品 ").append(finfo.awemeCount);
                                sb.append(" · 获赞 ").append(FeedVideo.formatCount(finfo.totalFavorited));
                                mStats.setText(sb.toString());
                                setPageTitle(finfo.nickname.isEmpty() ? "主页" : finfo.nickname);
                            }
                            show(posts);
                            // 首页就有更多 → 先把游标记下，滚到底由自动续拉接手
                            mPostsCursor = next;
                            mPostsHasMore = more && next >= 0;
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("user", "作品列表失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            hint("拉取失败: " + e.getMessage());
                            com.dywatch.app.ui.Loading.show(UserActivity.this, false);
                        }
                    });
                }
            }
        }, "user-load").start();
    }

    /**
     * 自动续拉（滚到底触发）：作者主页作品常超过一屏，只拉首页的话
     * 用户会以为"这人就没几个视频"。
     */
    private void loadMorePosts() {
        if (mLoadingPosts || !mPostsHasMore || mSecUid.isEmpty()) return;
        mLoadingPosts = true;
        hint("正在加载更多作品…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final DouyinApi api = ensureApi();
                    final List<FeedVideo> posts = api.fetchUserPosts(mSecUid, mPostsCursor);
                    final boolean more = api.lastPostsHasMore;
                    final long next = api.lastPostsCursor;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoadingPosts = false;
                            mPostsCursor = next;
                            mPostsHasMore = more && next >= 0;
                            // 追加渲染（不清空，保住已看位置）
                            for (FeedVideo v : posts) {
                                if (!mVideos.contains(v)) mVideos.add(v);
                            }
                            List<RowAdapter.Item> items = new ArrayList<>();
                            for (int i = 0; i < mVideos.size(); i++) {
                                FeedVideo v = mVideos.get(i);
                                String meta = v.diggCount > 0 ? (FeedVideo.formatCount(v.diggCount) + " 赞") : "";
                                items.add(new RowAdapter.Item(
                                        v.title.isEmpty() ? "(无标题)" : v.title, meta, "", i));
                            }
                            mAdapter.submitList(items);
                            // 成功也落日志：分不清"到底了"还是"续拉没触发"时这就是唯一线索
                            AppLog.i("user", "作品续拉 +" + posts.size() + " 条，累计 "
                                    + mVideos.size() + "，还有更多=" + mPostsHasMore);
                            if (mPostsHasMore) clearHint();
                            else hint("已经到底了");
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("user", "作品续拉失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoadingPosts = false;
                            hint("加载更多失败: " + e.getMessage());
                        }
                    });
                }
            }
        }, "user-more").start();
    }

    /** 滚动近底 → 自动续拉（与评论页同一套口径） */
    private void wireAutoLoadMore() {
        mList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv,
                                   int dx, int dy) {
                if (mLoadingPosts || !mPostsHasMore) return;
                androidx.recyclerview.widget.LinearLayoutManager lm =
                        (androidx.recyclerview.widget.LinearLayoutManager) mList.getLayoutManager();
                if (lm == null || mList.getAdapter() == null) return;
                int last = lm.findLastVisibleItemPosition();
                int count = mList.getAdapter().getItemCount();
                if (count > 0 && last >= count - 3) loadMorePosts();
            }
        });
    }

    private void show(List<FeedVideo> list) {
        mVideos.clear();
        mVideos.addAll(list);
        List<RowAdapter.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            FeedVideo v = list.get(i);
            String meta = v.diggCount > 0 ? (FeedVideo.formatCount(v.diggCount) + " 赞") : "";
            items.add(new RowAdapter.Item(
                    v.title.isEmpty() ? "(无标题)" : v.title, meta, "", i));
        }
        mAdapter.submitList(items);
        com.dywatch.app.ui.Loading.show(this, false);
        if (list.isEmpty()) {
            hint("这个作者没有作品（或接口没返回）");
        } else {
            clearHint();
        }
    }

    /** 点条目 → 用预置列表模式播放该主页的全部作品 */
    private void playAt(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        android.content.Intent it = new android.content.Intent(this,
                com.dywatch.app.feed.FeedActivity.class);
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_VIDEO_LIST,
                new ArrayList<>(mVideos));
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_START_INDEX, index);
        startActivity(it);
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        mApi = new DouyinApi(new Signer(Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")))));
        mApi.setSessionCookie(LoginManager.getCookies(this));
        return mApi;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void hint(String text) {
        if (mHint == null) return;
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        if (mHint != null) mHint.setVisibility(View.GONE);
    }
}
