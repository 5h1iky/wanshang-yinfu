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

/** 收藏夹目录及作品列表。目录为空时仍可查看全部收藏。 */
public class CollectionActivity extends UiActivity {
    public static final String EXTRA_COLLECTION_ID = "collection_id";
    public static final String EXTRA_NAME = "name";
    private final List<DouyinApi.CollectionInfo> mFolders = new ArrayList<>();
    private final List<FeedVideo> mVideos = new ArrayList<>();
    private MediaRowAdapter mAdapter;
    private RecyclerView mList;
    private TextView mHint;
    private DouyinApi mApi;
    private String mCollectionId = "", mName = "收藏夹";
    private boolean mAllCollections;
    private long mCursor;
    private boolean mHasMore = true, mLoading;
    private int mGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection);
        mCollectionId = getIntent().getStringExtra(EXTRA_COLLECTION_ID);
        mAllCollections = mCollectionId != null && mCollectionId.isEmpty()
                && getIntent().hasExtra(EXTRA_COLLECTION_ID);
        if (mCollectionId == null) mCollectionId = "";
        if (mAllCollections) mName = "全部收藏";
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name != null && !name.isEmpty()) mName = name;
        setPageTitle(mName);
        mList = findViewById(R.id.rv_collection);
        mHint = findViewById(R.id.tv_collection_hint);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MediaRowAdapter(payload -> {
            if (payload instanceof DouyinApi.CollectionInfo) {
                DouyinApi.CollectionInfo folder = (DouyinApi.CollectionInfo) payload;
                Intent it = new Intent(this, CollectionActivity.class);
                it.putExtra(EXTRA_COLLECTION_ID, folder.id);
                it.putExtra(EXTRA_NAME, folder.name);
                startActivity(it);
            } else if (payload instanceof Integer) {
                openVideo((Integer) payload);
            }
        });
        mList.setAdapter(mAdapter);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null || !mHasMore) return;
                int threshold = isFolderMode() ? mFolders.size() + 1 : mVideos.size();
                if (lm.findLastVisibleItemPosition() >= threshold - 3) load(false);
            }
        });
        if (!LoginManager.hasSession(this)) {
            mHasMore = false;
            hint("登录后可查看收藏");
        } else load(true);
    }

    private boolean isFolderMode() { return mCollectionId.isEmpty() && !mAllCollections; }

    private void load(boolean first) {
        if (mLoading || !mHasMore || isFinishing() || isDestroyed()) return;
        mLoading = true;
        final int generation = mGeneration;
        final long cursor = mCursor;
        hint(first ? "正在拉取收藏…" : "正在加载更多…");
        if (first) Loading.show(this, true);
        new Thread(() -> {
            try {
                DouyinApi api = ensureApi();
                if (isFolderMode()) {
                    final com.dywatch.app.net.PageResult<DouyinApi.CollectionInfo> page =
                            api.fetchCollections(cursor);
                    final List<DouyinApi.CollectionInfo> items = page.items();
                    runOnUiThread(() -> {
                        if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                        mLoading = false;
                        // 服务端 cursor/has_more 只属于本次请求，迟到响应已被 generation 丢弃。
                        mCursor = page.cursor();
                        mHasMore = page.hasMore();
                        for (DouyinApi.CollectionInfo info : items) {
                            if (info.name.isEmpty()) info.name = "未命名收藏夹";
                            boolean duplicate = false;
                            for (DouyinApi.CollectionInfo old : mFolders) {
                                if (old.id.equals(info.id)) { duplicate = true; break; }
                            }
                            if (!duplicate) mFolders.add(info);
                        }
                        showFolders();
                    });
                } else {
                    final com.dywatch.app.net.PageResult<FeedVideo> page = mAllCollections
                            ? api.fetchAllCollectionVideos(cursor)
                            : api.fetchCollectionVideos(mCollectionId, cursor);
                    final List<FeedVideo> items = page.items();
                    runOnUiThread(() -> {
                        if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                        mLoading = false;
                        mCursor = page.cursor();
                        mHasMore = page.hasMore();
                        appendVideos(items);
                    });
                }
            } catch (Exception e) {
                AppLog.i("collection", "读取失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    Loading.show(this, false);
                    if (isFolderMode()) {
                        mHasMore = false;
                        showFolders();
                        hint("收藏夹目录暂不可用；仍可进入“全部收藏”");
                    } else {
                        hint(DouyinApi.friendlyMessage(e));
                    }
                });
            }
        }, "collection-load").start();
    }

    private void showFolders() {
        List<MediaRowAdapter.Item> rows = new ArrayList<>();
        rows.add(new MediaRowAdapter.Item("全部收藏", "按收藏时间查看", "查看作品列表",
                "", new DouyinApi.CollectionInfo(), MediaRowAdapter.KIND_FOLDER));
        for (DouyinApi.CollectionInfo folder : mFolders) {
            String meta = folder.count + " 个作品" + (folder.isPrivate ? " · 私密" : "");
            rows.add(new MediaRowAdapter.Item(folder.name, meta, "", "", folder,
                    MediaRowAdapter.KIND_FOLDER));
        }
        mAdapter.submitList(rows);
        Loading.show(this, false);
        if (mFolders.isEmpty()) hint("暂无自建收藏夹，可在“全部收藏”查看");
        else if (!mHasMore) clearHint();
        else clearHint();
    }

    private void appendVideos(List<FeedVideo> page) {
        List<MediaRowAdapter.Item> rows = new ArrayList<>();
        for (FeedVideo v : page) {
            boolean duplicate = false;
            for (FeedVideo old : mVideos) if (old.awemeId.equals(v.awemeId)) { duplicate = true; break; }
            if (duplicate) continue;
            int index = mVideos.size();
            mVideos.add(v);
            rows.add(VideoRows.of(v, index));
        }
        mAdapter.append(rows);
        Loading.show(this, false);
        if (mVideos.isEmpty()) hint("这个收藏夹还没有作品");
        else if (!mHasMore) hint("已经到底了");
        else clearHint();
        setPageTitle(mName + " " + mVideos.size());
    }

    private void openVideo(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        Intent it = new Intent(this, FeedActivity.class);
        it.putExtra(FeedActivity.EXTRA_VIDEO_LIST, new ArrayList<>(mVideos));
        it.putExtra(FeedActivity.EXTRA_START_INDEX, index);
        it.putExtra(FeedActivity.EXTRA_VIDEO_SOURCE, mAllCollections
                ? VideoSource.allCollections(mCursor, mHasMore)
                : VideoSource.collection(mCollectionId, mCursor, mHasMore));
        startActivity(it);
    }

    private synchronized DouyinApi ensureApi() throws Exception {
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
    private void clearHint() { mHint.setVisibility(View.GONE); }
    @Override protected void onDestroy() { mGeneration++; super.onDestroy(); }
}
