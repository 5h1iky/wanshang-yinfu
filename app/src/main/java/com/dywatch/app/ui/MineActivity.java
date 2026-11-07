package com.dywatch.app.ui;

// 「我的」页：喜欢（服务端只读接口） + 看过（本地账本）。
//
// 为什么这两个能走原生 API 而不是 WebView 引擎：
// 风控严的是**写入**（裸 digg 实测 403 → 会话当场死亡），读取宽松。
// 2026-09-26 实测 /aweme/v1/web/aweme/favorite/ 直连拿到 13 条真数据。
// 而"看过"压根没有服务端接口（抖音 Web 端无观看历史 API），用本地 HistoryStore 拼。

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.feed.HistoryStore;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.net.PageResult;
import com.dywatch.app.sign.Signer;
import com.dywatch.app.util.AppLog;
import com.dywatch.app.util.InFlightGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MineActivity extends UiActivity {

    /** 0 = 喜欢，1 = 看过 */
    private static final int TAB_LIKE = 0, TAB_HISTORY = 1;

    private androidx.recyclerview.widget.RecyclerView mList;
    private MediaRowAdapter mAdapter;
    private TextView mHint;
    private Button mTabLike, mTabHistory;
    private DouyinApi mApi;
    private int mTab = TAB_LIKE;
    private boolean mLoading;
    /** 喜欢第一页的服务端 next cursor / has_more；进播放页续拉时必须用它，不能用列表条数。 */
    private long mLikesCursor = -1;
    private boolean mLikesHasMore;
    /** 历史条目点击换址一次只允许一个在途，防止连点重复 fetch/startActivity。 */
    private final InFlightGuard mHistoryOpenGuard = new InFlightGuard();
    /** 当前 tab 的数据源（点击后进播放页要用） */
    private final List<FeedVideo> mVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mine);
        setPageTitle("我的");

        mList = findViewById(R.id.rv_mine);
        mList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        // “作品”区先显示封面 + 标题，点具体一条才进全屏视频流。
        mAdapter = new MediaRowAdapter(payload -> {
            if (!(payload instanceof Integer)) return;
            openAt((Integer) payload);
        });
        mList.setAdapter(mAdapter);
        mHint = findViewById(R.id.tv_mine_hint);
        mTabLike = findViewById(R.id.btn_tab_like);
        mTabHistory = findViewById(R.id.btn_tab_history);

        findViewById(R.id.btn_following).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, FollowingActivity.class)));
        findViewById(R.id.btn_collections).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, CollectionActivity.class)));

        mTabLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_LIKE); }
        });
        mTabHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { switchTab(TAB_HISTORY); }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        switchTab(TAB_LIKE);
    }

    private void switchTab(int tab) {
        if (mTab == tab && !mVideos.isEmpty()) return;
        mTab = tab;
        mTabLike.setSelected(mTab == TAB_LIKE);
        mTabHistory.setSelected(mTab == TAB_HISTORY);
        mTabLike.setTextColor(getColorCompat(mTab == TAB_LIKE
                ? R.color.accent : R.color.text_secondary));
        mTabHistory.setTextColor(getColorCompat(mTab == TAB_HISTORY
                ? R.color.accent : R.color.text_secondary));
        mVideos.clear();
        mAdapter.clear();
        clearHint();
        if (tab == TAB_LIKE) loadLikes();
        else loadHistory();
    }

    private int getColorCompat(int res) {
        return androidx.core.content.ContextCompat.getColor(this, res);
    }

    // ---- 喜欢：服务端只读接口 ----

    private void loadLikes() {
        if (!LoginManager.hasSession(this)) {
            hint("未登录，看不到喜欢列表（主屏→登录→扫码）");
            return;
        }
        if (mLoading) return;
        mLoading = true;
        final int tab = TAB_LIKE;
        hint("正在拉取喜欢…");
        Loading.show(this, true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 顺带修：原来写成 ensureApi().fetchFavorite(ensureApi().fetchSelfSecUid(), 0)，
                    // 同一个 api 对象取两遍（第一次还要读 3 个 assets 建签名器），没必要
                    final DouyinApi api = ensureApi();
                    final PageResult<FeedVideo> page =
                            api.fetchFavorite(api.fetchSelfSecUid(), 0);
                    final List<FeedVideo> list = page.items();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            if (mTab != tab) return; // 请求回来时已切到“看过”，别覆盖当前列表
                            // 记录服务端下一页游标：播放页续拉喜欢列表时用它，
                            // 不能再用 mVideos.size() 冒充 max_cursor。
                            mLikesCursor = page.cursor();
                            mLikesHasMore = page.hasMore();
                            // 成功路径也落一行：只记失败的话，"列表是空的"到底是没数据还是没拉到就分不清
                            AppLog.i("mine", "喜欢列表拉到 " + list.size() + " 条，hasMore="
                                    + mLikesHasMore);
                            show(list, "还没有喜欢过的视频");
                        }
                    });
                } catch (final Exception e) {
                    AppLog.i("mine", "喜欢列表失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            if (mTab != tab) return; // 别把喜欢页的风控提示带到已切换的“看过”页
                            Loading.show(MineActivity.this, false);
                            // ⚠️ 2026-10-25：原来直接拼 e.getMessage()，Gson 的
                            //    "MalformedJsonException: Use JsonReader.setLenient..." 就甩到表盘上了。
                            //    风控拦截是上游收紧（403 + ArgusSecurityPlugin），我们改不了，
                            //    但至少要说人话：接口暂时不可用 + 稍后再试。
                            hint(com.dywatch.app.net.DouyinApi.friendlyMessage(e));
                        }
                    });
                }
            }
        }, "mine-like").start();
    }

    // ---- 看过：本地账本 ----

    private void loadHistory() {
        // 本地账本，没有网络等待 → 不需要转圈（上一段的圈要确保收掉）
        Loading.show(this, false);
        List<FeedVideo> out = new ArrayList<>();
        for (HistoryStore.Entry e : HistoryStore.read(this)) {
            // 看过只有 id/标题/封面，没有播放地址——点击后要去换址，
            // 这里先按"可点进详情"处理，播放地址留空由播放页兜底。
            out.add(new FeedVideo(e.title, "", e.coverUrl, e.awemeId, "", 0, 0, 0));
        }
        show(out, "还没有看过记录");
    }

    private void show(List<FeedVideo> list, String emptyText) {
        mVideos.clear();
        mVideos.addAll(list);
        List<MediaRowAdapter.Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            items.add(VideoRows.of(list.get(i), i));
        }
        mAdapter.submitList(items);
        Loading.show(this, false);
        if (list.isEmpty()) {
            // 空态也要把页名切过来，否则上一段的"喜欢 13"会一直挂着，看着像切了 tab 没生效
            setPageTitle(mTab == TAB_LIKE ? "喜欢" : "看过");
            hint(emptyText);
        } else {
            clearHint();
            setPageTitle((mTab == TAB_LIKE ? "喜欢 " : "看过 ") + list.size());
        }
    }

    /** 点条目 → 进播放页（把整批传过去，可以上下滑着看） */
    private void openAt(int index) {
        if (index < 0 || index >= mVideos.size()) return;
        // 看过的记录只存了 id/标题/封面（没有播放地址）→ 先按 aweme/detail 换址再播。
        //
        // ⚠️ 2026-11-07 审计 M8：原来把**整个历史列表**逐条 fetchDetail 完才进播放页，
        //    历史越长等越久（50 条 = 50 次串行请求，手表上要几十秒），而且连点会叠加多批。
        //    现在只换**当前点的那一条**，进播放页后其余条目由 FeedActivity 的
        //    resolveAndPlayDetail 按需单条换址（它自带 mDetailInFlight 去重）。
        if (mTab == TAB_HISTORY) {
            // 2026-11-07 审计：历史条目换址是慢网络请求，连点会重复 fetch + 重复 startActivity。
            // CAS 闸门保证一次只在途一个；成功/失败/页面结束都会在 UI 回调里 release。
            if (!mHistoryOpenGuard.tryAcquire()) return;
            final FeedVideo stub = mVideos.get(index);
            final int targetIndex = index;
            // 固定点击时的列表快照：等待换址期间用户切 tab/列表被清空也不会串到别的条目。
            final java.util.List<FeedVideo> snapshot = new ArrayList<>(mVideos);
            hint("正在取回播放地址…");
            Loading.show(this, true);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    FeedVideo playable = stub;
                    try {
                        if (!isFinishing() && !isDestroyed()) {
                            DouyinApi api = ensureApi();
                            FeedVideo full = api.fetchDetail(stub.awemeId);
                            if (full != null) playable = full;
                        }
                    } catch (Exception e) {
                        // 换址失败就带 stub 进去（播放页自己会再试/报错，而不是黑屏）
                        AppLog.i("mine", "换址失败 " + stub.awemeId + ": " + e.getMessage());
                    }
                    final FeedVideo resolvedOne = playable;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mHistoryOpenGuard.release();
                            if (isFinishing() || isDestroyed()) return;
                            Loading.show(MineActivity.this, false);
                            clearHint();
                            // 进播放页的列表 = 点击时的历史快照，只把当前条换成换好址的版本。
                            // 其余条目在播放页里按需换址（FeedActivity 已实现单条换址路径）。
                            java.util.List<FeedVideo> list = new ArrayList<>(snapshot);
                            if (targetIndex >= 0 && targetIndex < list.size()) {
                                list.set(targetIndex, resolvedOne);
                            }
                            android.content.Intent it = new android.content.Intent(MineActivity.this,
                                    com.dywatch.app.feed.FeedActivity.class);
                            it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_VIDEO_LIST,
                                    new ArrayList<>(list));
                            it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_START_INDEX,
                                    Math.max(0, targetIndex));
                            // 标记"需要按需换址"，让播放页知道这些条目可能没有播放地址
                            it.putExtra("need_detail_resolve", true);
                            startActivity(it);
                        }
                    });
                }
            }, "mine-resolve").start();
            return;
        }
        android.content.Intent it = new android.content.Intent(this,
                com.dywatch.app.feed.FeedActivity.class);
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_VIDEO_LIST,
                new ArrayList<>(mVideos));
        it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_START_INDEX, index);
        if (mTab == TAB_LIKE) {
            com.dywatch.app.feed.VideoSource source =
                    com.dywatch.app.feed.VideoSource.likes(mLikesCursor, mLikesHasMore);
            source.seedLikes(mVideos);
            it.putExtra(com.dywatch.app.feed.FeedActivity.EXTRA_VIDEO_SOURCE, source);
        }
        startActivity(it);
    }

    private synchronized DouyinApi ensureApi() throws Exception {
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
