package com.dywatch.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedActivity;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.feed.VideoSource;
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

/**
 * 首页“刷视频”选片页。
 *
 * 参考系（哔哩终端）的首页不是一进来就自动播：先展示封面和标题，用户点具体
 * 一条才进全屏视频流。这里沿用同一套 MediaRowAdapter/VideoRows 卡片，进入
 * FeedActivity 时把当前推荐流游标一起带过去，保证上下滑仍按推荐流续拉。
 */
public class BrowseActivity extends UiActivity {
    private final List<FeedVideo> mVideos = new ArrayList<>();
    private MediaRowAdapter mAdapter;
    private RecyclerView mList;
    private TextView mHint;
    private DouyinApi mApi;
    private boolean mLoading, mHasMore = true;
    private int mGeneration;
    /** 推荐接口一页可能只有 6 条，首屏填不满时自动补一次（最多一次，避免无限翻页）。 */
    private int mAutoFillPages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);
        setPageTitle("刷视频");

        mList = findViewById(R.id.rv_browse);
        mHint = findViewById(R.id.tv_browse_hint);
        mList.setLayoutManager(new LinearLayoutManager(this));
        // 低端机/手表：卡片高度固定，关掉按内容反复测量；缓存一屏多的 item。
        mList.setHasFixedSize(true);
        mList.setItemViewCacheSize(8);
        mList.setItemAnimator(null); // 低端机/手表：列表只做追加，不需要动画开销
        mAdapter = new MediaRowAdapter(payload -> {
            if (payload instanceof Integer) openAt((Integer) payload);
        });
        mList.setAdapter(mAdapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_browse_recommend).setSelected(true);
        findViewById(R.id.btn_browse_recommend).setOnClickListener(v -> {
            // 当前就是推荐页；保留选中态，不做跳转。
        });
        findViewById(R.id.btn_browse_search).setOnClickListener(v -> {
            Intent it = new Intent(this, SearchActivity.class);
            it.putExtra(SearchActivity.EXTRA_INITIAL_TAB, SearchActivity.VIDEO);
            startActivity(it);
        });
        findViewById(R.id.btn_browse_live).setOnClickListener(v -> {
            Intent it = new Intent(this, SearchActivity.class);
            it.putExtra(SearchActivity.EXTRA_INITIAL_TAB, SearchActivity.LIVE);
            startActivity(it);
        });

        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                // 只有用户向下滑才续拉；首屏布局完成时也会回调一次，别一进来就自动翻页。
                if (dy <= 0 || !mHasMore || mLoading) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 3) {
                    load(false);
                }
            }
        });

        load(true);
    }

    private void load(final boolean first) {
        if (mLoading || !mHasMore || isFinishing() || isDestroyed()) return;
        mLoading = true;
        final int generation = mGeneration;
        hint(first ? "正在拉取推荐…" : "正在加载更多…");
        if (first) Loading.show(this, true);
        new Thread(() -> {
            try {
                final DouyinApi api = ensureApi();
                final List<FeedVideo> page = api.fetchFeed(10);
                // 与 FeedActivity 共用同一个持久游标：选片页拉了几页，进全屏后从下一页继续。
                getSharedPreferences("feed_state", MODE_PRIVATE).edit()
                        .putInt("refresh_index", api.getRefreshIndex()).apply();
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    int before = mVideos.size();
                    List<MediaRowAdapter.Item> rows = new ArrayList<>();
                    for (FeedVideo v : page) {
                        if (contains(v.awemeId)) continue;
                        int index = mVideos.size();
                        mVideos.add(v);
                        rows.add(VideoRows.of(v, index));
                    }
                    mAdapter.append(rows);
                    mHasMore = !page.isEmpty();
                    Loading.show(BrowseActivity.this, false);
                    if (mVideos.isEmpty()) hint("暂时没有拉到推荐视频，稍后再试");
                    else if (!mHasMore) hint("已经到底了");
                    else clearHint();
                    AppLog.i("browse", "本页 " + page.size() + " 条，新增 " + (mVideos.size() - before)
                            + "，共 " + mVideos.size() + " 条");
                    // 首页推荐一页可能只有 6 条；内容不够一屏时补一页，别让用户看到半屏空白。
                    if (first && mVideos.size() < 8 && mHasMore && mAutoFillPages < 1) {
                        mAutoFillPages++;
                        mList.postDelayed(() -> load(false), 300);
                    }
                });
            } catch (Exception e) {
                AppLog.i("browse", "推荐读取失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    Loading.show(BrowseActivity.this, false);
                    hint(DouyinApi.friendlyMessage(e));
                });
            }
        }, "browse-load").start();
    }

    private boolean contains(String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return false;
        for (FeedVideo video : mVideos) {
            if (awemeId.equals(video.awemeId)) return true;
        }
        return false;
    }

    private void openAt(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        Intent it = new Intent(this, FeedActivity.class);
        it.putExtra(FeedActivity.EXTRA_VIDEO_LIST, new ArrayList<>(mVideos));
        it.putExtra(FeedActivity.EXTRA_START_INDEX, index);
        it.putExtra(FeedActivity.EXTRA_VIDEO_SOURCE,
                VideoSource.recommend(mApi == null ? 0 : mApi.getRefreshIndex(), mHasMore));
        startActivity(it);
    }

    private DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        mApi = new DouyinApi(new Signer(Arrays.asList(
                readAsset("sign/utils.js"),
                readAsset("sign/sm3.js"),
                readAsset("sign/vm_decode.js"))));
        mApi.setSessionCookie(LoginManager.getCookies(this));
        mApi.setRefreshIndex(getSharedPreferences("feed_state", MODE_PRIVATE)
                .getInt("refresh_index", 0));
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

    private void clearHint() {
        mHint.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        mGeneration++;
        super.onDestroy();
    }
}
