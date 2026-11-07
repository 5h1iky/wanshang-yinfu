package com.dywatch.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
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

/** 搜索：视频、用户、直播；短视频结果始终以全屏视频流播放。 */
public class SearchActivity extends UiActivity {
    public static final String EXTRA_INITIAL_TAB = "initial_tab";
    public static final int VIDEO = 0, USER = 1, LIVE = 2;
    private final List<FeedVideo> mVideos = new ArrayList<>();
    private final List<DouyinApi.UserSummary> mUsers = new ArrayList<>();
    private final List<DouyinApi.LiveRoom> mLives = new ArrayList<>();
    private MediaRowAdapter mAdapter;
    private RecyclerView mList;
    private EditText mInput;
    private TextView mHint, mClearHistory;
    private Button[] mTabs;
    private DouyinApi mApi;
    private int mTab = VIDEO, mOffset, mGeneration;
    private boolean mHasMore, mLoading;
    private String mKeyword = "", mSearchId = "";
    private LinearLayoutManager mLayout;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        setPageTitle("搜索");
        mInput = findViewById(R.id.et_search);
        mHint = findViewById(R.id.tv_search_hint);
        mClearHistory = findViewById(R.id.btn_search_clear);
        mClearHistory.setOnClickListener(v -> {
            SearchHistoryStore.clear(this);
            renderHistory();
        });
        mList = findViewById(R.id.rv_search);
        mLayout = new LinearLayoutManager(this);
        mList.setLayoutManager(mLayout);
        mAdapter = new MediaRowAdapter(payload -> {
            if (payload instanceof String) {
                mInput.setText((String) payload);
                mInput.setSelection(((String) payload).length());
                search();
            } else onRow(payload);
        });
        mList.setAdapter(mAdapter);
        mTabs = new Button[]{findViewById(R.id.btn_search_video),
                findViewById(R.id.btn_search_user), findViewById(R.id.btn_search_live)};
        Fonts.scale(mClearHistory, R.dimen.t_caption);
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            mTabs[i].setOnClickListener(v -> switchTab(tab));
        }
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_search_go).setOnClickListener(v -> search());
        mInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                search();
                return true;
            }
            return false;
        });
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (mHasMore && mLayout.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 3) {
                    load(false);
                }
            }
        });
        Fonts.scale(mInput, R.dimen.t_body);
        switchTab(getIntent().getIntExtra(EXTRA_INITIAL_TAB, VIDEO));
    }

    private void switchTab(int tab) {
        if (tab < VIDEO || tab > LIVE) tab = VIDEO;
        mTab = tab;
        for (int i = 0; i < mTabs.length; i++) {
            // ⚠️ 2026-11-07 审计 M1：Context.getColor 是 API 23，minSdk 21 → 用 ContextCompat。
            //    原来在 Android 5.x 上进搜索页直接 NoSuchMethodError（README 承诺 5.0+）。
            mTabs[i].setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    i == tab ? R.color.text_primary : R.color.text_secondary));
            mTabs[i].setAlpha(i == tab ? 1f : 0.68f);
        }
        mGeneration++;
        mLoading = false;
        mHasMore = false;
        mKeyword = "";
        mSearchId = "";
        mOffset = 0;
        mVideos.clear();
        mUsers.clear();
        mLives.clear();
        mAdapter.clear();
        Loading.show(this, false);
        mInput.setHint(tab == VIDEO ? "搜索视频" : tab == USER ? "搜索用户" : "搜索直播");
        renderHistory();
    }

    private void search() {
        String keyword = mInput.getText() == null ? "" : mInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            hint("先输入搜索关键词");
            return;
        }
        mInput.clearFocus();
        mClearHistory.setVisibility(View.GONE);
        mKeyword = keyword;
        SearchHistoryStore.add(this, keyword);
        mOffset = 0;
        mSearchId = "";
        mVideos.clear();
        mUsers.clear();
        mLives.clear();
        mAdapter.clear();
        mHasMore = true;
        mGeneration++;
        // ⚠️ 2026-11-07 审计 M2：**必须**在这里复位 mLoading。
        //    原实现只 ++generation 不复位，而旧请求回来时"代次不符"会在 mLoading=false 之前就 return，
        //    于是锁永远不解 → 上一条请求在途时改关键词再搜，新搜索根本不发，页面空白转圈到切 tab 为止。
        //    （旧请求的回调也会自己清锁，两条路径都清是幂等的，不会误放新请求的锁：
        //      新请求开始时 mLoading 立刻被 load() 置回 true。）
        mLoading = false;
        load(true);
    }

    private void load(boolean first) {
        if (mLoading || !mHasMore || mKeyword.isEmpty() || isFinishing() || isDestroyed()) return;
        mLoading = true;
        final int generation = mGeneration;
        final int offset = mOffset;
        final String keyword = mKeyword, searchId = mSearchId;
        final int tab = mTab;
        hint(first ? "正在搜索…" : "正在加载更多…");
        if (first) Loading.show(this, true);
        new Thread(() -> {
            try {
                DouyinApi api = ensureApi();
                // 每个请求拿自己的不可变 PageResult，绝不回读 mApi 上的共享游标字段。
                final com.dywatch.app.net.PageResult<?> page;
                final List<FeedVideo> videos;
                final List<DouyinApi.UserSummary> users;
                final List<DouyinApi.LiveRoom> lives;
                if (tab == VIDEO) {
                    com.dywatch.app.net.PageResult<FeedVideo> p =
                            api.searchVideos(keyword, offset, searchId);
                    page = p;
                    videos = p.items();
                    users = null;
                    lives = null;
                } else if (tab == USER) {
                    com.dywatch.app.net.PageResult<DouyinApi.UserSummary> p =
                            api.searchUsers(keyword, offset);
                    page = p;
                    videos = null;
                    users = p.items();
                    lives = null;
                } else {
                    com.dywatch.app.net.PageResult<DouyinApi.LiveRoom> p =
                            api.searchLive(keyword, offset);
                    page = p;
                    videos = null;
                    users = null;
                    lives = p.items();
                }
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    mHasMore = page.hasMore();
                    long next = page.cursor();
                    mOffset = next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
                    mSearchId = page.searchId();
                    if (mTab == VIDEO && videos != null) {
                        for (FeedVideo v : videos) {
                            if (containsVideo(v.awemeId)) continue;
                            int index = mVideos.size();
                            mVideos.add(v);
                            mAdapter.append(java.util.Collections.singletonList(VideoRows.of(v, index)));
                        }
                    } else if (mTab == USER && users != null) {
                        for (DouyinApi.UserSummary u : users) {
                            if (containsUser(u.secUid)) continue;
                            mUsers.add(u);
                            String meta = u.followerCount > 0 ? FeedVideo.formatCount(u.followerCount) + " 粉丝" : "";
                            mAdapter.append(java.util.Collections.singletonList(new MediaRowAdapter.Item(
                                    u.nickname, meta, u.signature, u.avatarUrl, u, MediaRowAdapter.KIND_USER)));
                        }
                    } else if (mTab == LIVE && lives != null) {
                        for (DouyinApi.LiveRoom room : lives) {
                            mLives.add(room);
                            mAdapter.append(java.util.Collections.singletonList(new MediaRowAdapter.Item(
                                    room.title, room.ownerName, room.viewerText + " · 直播中",
                                    room.coverUrl, room, MediaRowAdapter.KIND_VIDEO)));
                        }
                    }
                    Loading.show(this, false);
                    if (mAdapter.getItemCount() == 0) hint("没有找到相关内容，换个关键词试试");
                    else if (!mHasMore) hint("已经到底了");
                    else clearHint();
                });
            } catch (Exception e) {
                AppLog.i("search", "读取失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoading = false;
                    Loading.show(this, false);
                    hint(DouyinApi.friendlyMessage(e));
                });
            }
        }, "search-load").start();
    }

    private boolean containsVideo(String id) {
        for (FeedVideo v : mVideos) if (v.awemeId.equals(id)) return true;
        return false;
    }
    private boolean containsUser(String id) {
        for (DouyinApi.UserSummary u : mUsers) if (u.secUid.equals(id)) return true;
        return false;
    }

    private void onRow(Object payload) {
        if (payload instanceof Integer) {
            int index = (Integer) payload;
            if (index < 0 || index >= mVideos.size()) return;
            Intent it = new Intent(this, FeedActivity.class);
            it.putExtra(FeedActivity.EXTRA_VIDEO_LIST, new ArrayList<>(mVideos));
            it.putExtra(FeedActivity.EXTRA_START_INDEX, index);
            VideoSource source = VideoSource.search(mKeyword, mOffset, mHasMore);
            source.searchId = mSearchId;
            it.putExtra(FeedActivity.EXTRA_VIDEO_SOURCE, source);
            startActivity(it);
        } else if (payload instanceof DouyinApi.UserSummary) {
            DouyinApi.UserSummary user = (DouyinApi.UserSummary) payload;
            Intent it = new Intent(this, UserActivity.class);
            it.putExtra(UserActivity.EXTRA_SEC_UID, user.secUid);
            it.putExtra(UserActivity.EXTRA_NAME, user.nickname);
            startActivity(it);
        } else if (payload instanceof DouyinApi.LiveRoom) {
            LiveActivity.open(this, (DouyinApi.LiveRoom) payload);
        }
    }

    private void renderHistory() {
        List<String> history = SearchHistoryStore.read(this);
        List<MediaRowAdapter.Item> rows = new ArrayList<>();
        for (String word : history) {
            rows.add(new MediaRowAdapter.Item(word, "最近搜索", "", "", word,
                    MediaRowAdapter.KIND_HISTORY));
        }
        mAdapter.submitList(rows);
        mClearHistory.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        hint(history.isEmpty() ? "输入关键词，查找视频、用户或直播" : "点历史词重新搜索 · 历史仅保存在本机");
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
