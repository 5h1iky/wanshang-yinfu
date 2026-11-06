package com.dywatch.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedActivity;
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

/** 互动通知：仅打开页面时拉取，不标记已读，不与私信混排。 */
public class NoticeActivity extends UiActivity {
    private final List<DouyinApi.NoticeItem> mNotices = new ArrayList<>();
    private MediaRowAdapter mAdapter;
    private TextView mHint;
    private RecyclerView mList;
    private Button[] mTabs;
    private DouyinApi mApi;
    private int mGroup = 700, mGeneration;
    private boolean mLoading;
    private long mCursor;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice);
        setPageTitle("互动通知");
        mHint = findViewById(R.id.tv_notice_hint);
        mList = findViewById(R.id.rv_notice);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MediaRowAdapter(payload -> {
            if (payload instanceof DouyinApi.NoticeItem) open((DouyinApi.NoticeItem) payload);
        });
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (mCursor > 0 && !mLoading && lm != null
                        && lm.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 3) {
                    load();
                }
            }
        });
        mTabs = new Button[]{findViewById(R.id.btn_notice_all), findViewById(R.id.btn_notice_comment),
                findViewById(R.id.btn_notice_like), findViewById(R.id.btn_notice_at), findViewById(R.id.btn_notice_fans)};
        int[] groups = {700, 2, 3, 601, 401};
        for (int i = 0; i < mTabs.length; i++) {
            final int group = groups[i], index = i;
            mTabs[i].setOnClickListener(v -> switchGroup(group, index));
        }
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        for (Button tab : mTabs) tab.setAlpha(0.68f);
        mTabs[0].setAlpha(1f);
        if (LoginManager.hasSession(this)) load();
        else hint("登录后可查看互动通知");
    }

    private void switchGroup(int group, int index) {
        if (mGroup == group && !mNotices.isEmpty()) return;
        mGroup = group;
        for (int i = 0; i < mTabs.length; i++) {
            // ⚠️ 2026-11-07 审计 M1：Context.getColor 是 API 23 → minSdk 21 上切分类会崩，改 ContextCompat
            mTabs[i].setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    i == index ? R.color.text_primary : R.color.text_secondary));
            mTabs[i].setAlpha(i == index ? 1f : 0.68f);
        }
        mGeneration++;
        mLoading = false;
        mNotices.clear();
        mAdapter.clear();
        mCursor = 0;
        Loading.show(this, false);
        if (LoginManager.hasSession(this)) load();
    }

    private void load() {
        if (mLoading) return;
        mLoading = true;
        final int generation = mGeneration, group = mGroup;
        final long cursor = Math.max(0, mCursor);
        hint("仅本次打开时读取 · 不自动标记已读");
        Loading.show(this, true);
        new Thread(() -> {
            try {
                DouyinApi api = ensureApi();
                List<DouyinApi.NoticeItem> list = api.fetchNotices(group, cursor);
                boolean more = api.lastNoticeHasMore;
                long next = api.lastNoticeCursor;
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    mCursor = next;
                    for (DouyinApi.NoticeItem item : list) {
                        boolean duplicate = false;
                        for (DouyinApi.NoticeItem old : mNotices) {
                            if (!item.id.isEmpty() && old.id.equals(item.id)) { duplicate = true; break; }
                        }
                        if (!duplicate) mNotices.add(item);
                    }
                    render();
                    if (!more) mCursor = -1;
                });
            } catch (Exception e) {
                AppLog.i("notice", "读取失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    Loading.show(this, false);
                    hint(DouyinApi.friendlyMessage(e));
                });
            }
        }, "notice-load").start();
    }

    private void render() {
        List<MediaRowAdapter.Item> rows = new ArrayList<>();
        for (DouyinApi.NoticeItem item : mNotices) {
            String title = item.nickname.isEmpty() ? "抖音用户" : item.nickname;
            String action = item.group == 2 ? "评论了你："
                    : item.group == 3 ? "赞了你："
                    : item.group == 601 ? "提到了你："
                    : item.group == 401 ? "" : "";
            String summary = action + item.text
                    + (item.awemeId.isEmpty() && item.secUid.isEmpty() ? "" : " ›");
            rows.add(new MediaRowAdapter.Item(title, item.time, summary, item.imageUrl, item,
                    MediaRowAdapter.KIND_USER));
        }
        mAdapter.submitList(rows);
        Loading.show(this, false);
        if (mNotices.isEmpty()) hint("暂无此类互动 · 未改动未读状态");
        else if (mCursor <= 0) hint("已显示全部 · 未改动未读状态");
        else hint("仅本次打开时读取 · 不自动标记已读");
    }

    private void open(DouyinApi.NoticeItem item) {
        if (item == null) return;
        if (item.group == 401 && !item.secUid.isEmpty()) {
            Intent it = new Intent(this, UserActivity.class);
            it.putExtra(UserActivity.EXTRA_SEC_UID, item.secUid);
            it.putExtra(UserActivity.EXTRA_NAME, item.nickname);
            startActivity(it);
        } else if (!item.awemeId.isEmpty()) {
            // 通知只有定位 id：用 detail 换完整视频，再进入同一个全屏视频流。
            Loading.show(this, true);
            new Thread(() -> {
                try {
                    FeedVideo video = ensureApi().fetchDetail(item.awemeId);
                    runOnUiThread(() -> {
                        Loading.show(this, false);
                        if (isFinishing() || isDestroyed()) return;
                        Intent it = new Intent(this, FeedActivity.class);
                        it.putExtra(FeedActivity.EXTRA_VIDEO_LIST, new ArrayList<>(java.util.Collections.singletonList(video)));
                        it.putExtra(FeedActivity.EXTRA_START_INDEX, 0);
                        startActivity(it);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Loading.show(this, false);
                        hint(DouyinApi.friendlyMessage(e));
                    });
                }
            }, "notice-video").start();
        } else if (!item.secUid.isEmpty()) {
            Intent it = new Intent(this, UserActivity.class);
            it.putExtra(UserActivity.EXTRA_SEC_UID, item.secUid);
            it.putExtra(UserActivity.EXTRA_NAME, item.nickname);
            startActivity(it);
        }
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi == null) {
            mApi = new DouyinApi(new Signer(Arrays.asList(readAsset("sign/utils.js"),
                    readAsset("sign/sm3.js"), readAsset("sign/vm_decode.js"))));
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
    private void hint(String text) { mHint.setText(text); mHint.setVisibility(View.VISIBLE); }
    @Override protected void onDestroy() { mGeneration++; super.onDestroy(); }
}
