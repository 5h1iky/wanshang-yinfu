package com.dywatch.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.sign.Signer;
import com.dywatch.app.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 关注列表：只读、按时间游标分页，点击进入现有作者主页。 */
public class FollowingActivity extends UiActivity {
    private final List<DouyinApi.UserSummary> mUsers = new ArrayList<>();
    private MediaRowAdapter mAdapter;
    private RecyclerView mList;
    private TextView mHint;
    private DouyinApi mApi;
    private long mCursor;
    private boolean mHasMore = true;
    private boolean mLoading;
    private int mGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_following);
        setPageTitle("关注");
        mList = findViewById(R.id.rv_following);
        mHint = findViewById(R.id.tv_following_hint);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MediaRowAdapter(new MediaRowAdapter.OnRowClick() {
            @Override public void onClick(Object payload) {
                if (!(payload instanceof DouyinApi.UserSummary)) return;
                DouyinApi.UserSummary user = (DouyinApi.UserSummary) payload;
                Intent it = new Intent(FollowingActivity.this, UserActivity.class);
                it.putExtra(UserActivity.EXTRA_SEC_UID, user.secUid);
                it.putExtra(UserActivity.EXTRA_NAME, user.nickname);
                startActivity(it);
            }
        });
        mList.setAdapter(mAdapter);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 3) {
                    load(false);
                }
            }
        });
        if (!LoginManager.hasSession(this)) {
            mHasMore = false;
            hint("登录后可查看关注列表");
        } else {
            load(true);
        }
    }

    private void load(final boolean first) {
        if (mLoading || !mHasMore || isFinishing() || isDestroyed()) return;
        mLoading = true;
        final int generation = mGeneration;
        final long cursor = mCursor;
        hint(first ? "正在拉取关注…" : "正在加载更多…");
        if (first) Loading.show(this, true);
        new Thread(() -> {
            try {
                DouyinApi api = ensureApi();
                List<DouyinApi.UserSummary> page = api.fetchFollowing(cursor);
                boolean more = api.lastFollowingHasMore;
                long next = api.lastFollowingCursor;
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    mHasMore = more && next > 0;
                    mCursor = next;
                    if (page.isEmpty()) mHasMore = false;
                    int before = mUsers.size();
                    for (DouyinApi.UserSummary user : page) {
                        boolean duplicate = false;
                        for (DouyinApi.UserSummary old : mUsers) {
                            if (old.secUid.equals(user.secUid)) { duplicate = true; break; }
                        }
                        if (!duplicate) mUsers.add(user);
                    }
                    render(first, mUsers.size() - before);
                    AppLog.i("following", "本页 " + page.size() + " 人，累计 " + mUsers.size()
                            + "，hasMore=" + mHasMore);
                });
            } catch (Exception e) {
                AppLog.i("following", "读取失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    Loading.show(this, false);
                    hint(DouyinApi.friendlyMessage(e));
                });
            }
        }, "following-load").start();
    }

    private void render(boolean first, int added) {
        List<MediaRowAdapter.Item> rows = new ArrayList<>();
        for (DouyinApi.UserSummary user : mUsers) {
            String meta = user.followerCount > 0
                    ? FeedVideo.formatCount(user.followerCount) + " 粉丝" : "已关注";
            rows.add(new MediaRowAdapter.Item(user.nickname, meta, user.signature,
                    user.avatarUrl, user, MediaRowAdapter.KIND_USER));
        }
        mAdapter.submitList(rows);
        Loading.show(this, false);
        if (mUsers.isEmpty()) hint("还没有关注的人");
        else if (!mHasMore) hint("已显示全部关注");
        else if (!first && added == 0) hint("继续下滑加载更多");
        else clearHint();
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi == null) {
            mApi = new DouyinApi(new Signer(Arrays.asList(
                    readAsset("sign/utils.js"), readAsset("sign/sm3.js"),
                    readAsset("sign/vm_decode.js"))));
            mApi.setSessionCookie(LoginManager.getCookies(this));
        }
        return mApi;
    }

    private String readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void hint(String text) {
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
    }

    private void clearHint() { mHint.setVisibility(View.GONE); }

    @Override protected void onDestroy() {
        mGeneration++;
        super.onDestroy();
    }
}
