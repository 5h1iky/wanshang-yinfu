package com.dywatch.app.feed;

// 源自 DKVideoPlayer demo 的 TikTok2Activity（Apache-2.0，Copyright Doikki）——
// 官方推荐的仿抖音实现：VerticalViewPager 上下滑 + AndroidVideoCache 预加载 + 单播放器复用。
// 本项目改写：数据源接入真实 Feed（当前为实测 JSON 固件，M2 数据层接通后换接口）。
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager.widget.ViewPager;

import com.dywatch.app.R;
import com.dywatch.app.cache.PreloadManager;
import com.dywatch.app.util.AppLog;
import com.dywatch.app.widget.VerticalViewPager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import xyz.doikki.videoplayer.player.VideoView;

public class FeedActivity extends AppCompatActivity implements FeedAdapter.ActionListener,
        com.dywatch.app.chat.ChatEngine.FeedListener {

    /** 当前播放位置 */
    private int mCurPos;
    /** 应用级缓存：重进页面直接放上次刷到的视频（固件只在真冷启动垫场，杜绝"每次进来都是那 2 条老视频"） */
    private static final List<FeedVideo> sCache = new ArrayList<>();
    private com.dywatch.app.net.DouyinApi mApi;
    private volatile boolean mLoading;
    private final List<FeedVideo> mVideoList = new ArrayList<>();
    private FeedAdapter mAdapter;
    private VerticalViewPager mViewPager;
    private PreloadManager mPreloadManager;
    private TikTokController mController;
    private VideoView mVideoView;
    private com.dywatch.app.chat.ChatEngine mEngine;
    /** 推荐源（引擎抓精选页）是否已证实不可用→本次只用原生 feed，不再反复唤引擎 */
    private boolean mRecommendBroken;
    /** “这批全已看过→再抓”的连续次数：页面不再吐新内容时防止无限循环 */
    private int mFeedRetries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed);
        initViewPager();
        initVideoView();
        mPreloadManager = PreloadManager.getInstance(this);

        loadFixtureOrCache(); // 冷启动=固件垫场；有缓存=直接放上次的视频（不闪老面孔）
        loadRecommend();      // 主源：PC 精选页推荐（用户 2026-09-26 定案）
        // 手表硬件适配 §10：可见返回键
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                startPlay(0);
            }
        });
    }

    /** 网络优先加载真实 Feed（M2 数据层），失败回退固件。分页：抖音每页只回 2~5 条 */
    private void loadFeed() {
        loadMore(true);
    }

    /**
     * 主数据源（用户定案）：让引擎打开 PC 精选页抓 data-aweme-id 卡片列表（与电脑同源推荐），
     * 再逐条用 detail 接口换可播地址——播放始经原生。任一环不通则回退原生 tab/feed。
     */
    private void loadRecommend() {
        if (mRecommendBroken) { loadFeed(); return; }
        try {
            mEngine = com.dywatch.app.chat.ChatEngine.getInstance(this, null,
                    com.dywatch.app.chat.ChatEngine.FEED_URL); // 首建就在精选页，不白跑一次 /chat
            mEngine.setFeedListener(this);
            // 引擎可能是本次刚建的，onResume 那次挂载赶不上 → 再挂一次拿真视口
            com.dywatch.app.chat.ChatEngine.attachTo(this);
            mEngine.fetchRecommendFeed(false);
            AppLog.i("feed", "推荐源：引擎抓精选页 id 中");
            mViewPager.postDelayed(mRecommendWatchdog, 25000);
        } catch (Exception e) {
            AppLog.i("feed", "推荐源启动失败，回退原生 feed: " + e);
            mRecommendBroken = true;
            loadFeed();
        }
    }

    /** 兜底：引擎 25s 还没回 id（未登录/页面异常）→ 转原生 feed，不让用户面对空页 */
    private final Runnable mRecommendWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mVideoList.isEmpty()) {
                AppLog.i("feed", "推荐源超时未回数据，回退原生 feed");
                mRecommendBroken = true;
                loadFeed();
            }
        }
    };

    /** 引擎抓到推荐 id → 过滤已看过 → 后台换可播地址 → 走单一入口进列表 */
    @Override
    public void onFeedIds(final java.util.List<String> ids) {
        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                mViewPager.removeCallbacks(mRecommendWatchdog);
                if (ids == null || ids.isEmpty()) {
                    if (mVideoList.isEmpty()) { mRecommendBroken = true; loadFeed(); }
                    return;
                }
                final java.util.List<String> fresh = new java.util.ArrayList<>();
                for (String id : ids) {
                    if (id == null || id.isEmpty() || SeenStore.isSeen(FeedActivity.this, id)) continue;
                    boolean dup = false;
                    for (FeedVideo v : mVideoList) {
                        if (id.equals(v.awemeId)) { dup = true; break; }
                    }
                    if (!dup && fresh.size() < 6) fresh.add(id);
                }
                if (fresh.isEmpty()) {
                    // 防死循环：页面不再吐新 id 时不要无限“再抓”（每轮要 2.5s+网络）
                    if (++mFeedRetries > 3) {
                        AppLog.i("feed", "连续 3 批无新内容，停止自动再抓（等用户翻页）");
                        return;
                    }
                    AppLog.i("feed", "推荐 id 全已看过（" + ids.size() + " 条）→ 滚动再抓 第" + mFeedRetries + "次");
                    if (mEngine != null) mEngine.fetchRecommendFeed(true);
                    return;
                }
                mFeedRetries = 0;
                fetchDetails(fresh);
            }
        });
    }

    /** 后台逐条换地址（单条失败只跳过，不拖死整批） */
    private void fetchDetails(final java.util.List<String> ids) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<FeedVideo> got = new java.util.ArrayList<>();
                for (String id : ids) {
                    try {
                        com.dywatch.app.net.DouyinApi api = ensureApi();
                        got.add(api.fetchDetail(id));
                        // 真机取证：服务端给了哪些播放候选主机（选错＝只显封面类问题的第一手证据）
                        AppLog.i("feed", "换址 " + id + " 候选[" + api.lastDiagnostics + "]");
                    } catch (Exception e) {
                        AppLog.i("feed", "detail 失败跳过 " + id + ": " + e);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (got.isEmpty()) {
                            AppLog.i("feed", "推荐源换址全部失败 → 回退原生 feed");
                            mRecommendBroken = true;
                            loadFeed();
                            return;
                        }
                        // 列表里还混着固件/无 id 项（URL 是几小时前抓的直连 CDN，已过期不可播）
                        // → 首批整批换血，不让用户停在“只能显封面”的垫场项上；
                        //   否则（翻页批次）追加，不弄没用户正在看的那条。
                        boolean hasFixture = false;
                        for (FeedVideo v : mVideoList) {
                            if (v.awemeId == null || v.awemeId.isEmpty()) { hasFixture = true; break; }
                        }
                        int added = applyNewItems(got, hasFixture);
                        AppLog.i("feed", "推荐源进列表 " + added + "/" + got.size() + " 条（共 "
                                + mVideoList.size() + (hasFixture ? "，整批换掉垫场" : "，追加") + "）");
                        startPlay(mViewPager.getCurrentItem());
                    }
                });
            }
        }, "detail-load").start();
    }

    /** 懒建 DouyinApi（签名 JS + 登录态 + 持久游标）；只在后台线程调 */
    private com.dywatch.app.net.DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        java.util.List<String> js = java.util.Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")));
        mApi = new com.dywatch.app.net.DouyinApi(new com.dywatch.app.sign.Signer(js));
        // 带登录态拉流（个性化推荐）与互动身份
        mApi.setSessionCookie(com.dywatch.app.login.LoginManager.getCookies(this));
        // 翻页游标持久化：从上次进度继续（否则每次进页面都拉第1页=同样视频）
        mApi.setRefreshIndex(getSharedPreferences("feed_state", MODE_PRIVATE).getInt("refresh_index", 0));
        return mApi;
    }

    private void loadMore(final boolean first) {
        if (mLoading) return;
        mLoading = true;
        com.dywatch.app.util.AppLog.i("feed", "拉取下一页（已 " + mVideoList.size() + " 条）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.util.List<FeedVideo> list = ensureApi().fetchFeed(10);
                    // 游标落盘 + 登录态标记进日志（验证个性化）
                    getSharedPreferences("feed_state", MODE_PRIVATE).edit()
                            .putInt("refresh_index", mApi.getRefreshIndex()).apply();
                    com.dywatch.app.util.AppLog.i("feed", "本页拿到 " + list.size() + " 条（游标="
                            + mApi.getRefreshIndex() + " 登录态=" + com.dywatch.app.login.LoginManager.hasSession(FeedActivity.this) + "）");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            int added = applyNewItems(list, first);
                            com.dywatch.app.util.AppLog.i("feed", "新面孔 " + added + "/" + list.size()
                                    + " 条（共 " + mVideoList.size() + "）");
                            // notifyDataSetChanged 会重建页面视图 → 无条件重挂当前播放
                            startPlay(mViewPager.getCurrentItem());
                            if (list.isEmpty()) {
                                android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
                                if (hint != null) {
                                    hint.setText("没有更多了");
                                    hint.setVisibility(View.VISIBLE);
                                }
                            } else if (added == 0) {
                                // 本页全是看过的内容 → 自动补拉下一页
                                com.dywatch.app.util.AppLog.i("feed", "本页全为看过内容，自动补拉");
                                mViewPager.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        loadMore(false);
                                    }
                                }, 300);
                            }
                        }
                    });
                } catch (Exception e) {
                    com.dywatch.app.util.AppLog.i("feed", "网络加载失败: " + e);
                    final String err = String.valueOf(e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            if (first) loadFixtureOrCache();
                            android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
                            if (hint != null) {
                                hint.setText((first ? "网络加载失败，已回退内置数据。\n" : "翻页失败: ") + err);
                                hint.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            }
        }, "feed-load").start();
    }

    private void initVideoView() {
        mVideoView = new VideoView(this);
        mVideoView.setLooping(true);
        // 竖屏视频居中裁剪、横屏默认显示（demo 的 TikTokRenderView 行为）
        mVideoView.setRenderViewFactory(TikTokRenderViewFactory.create());
        mController = new TikTokController(this);
        mVideoView.setVideoController(mController);
        // 播放器状态全量落日志（真机教训：拉流失败被静默吞掉=只显封面，无日志根本查不出）
        mVideoView.addOnStateChangeListener(new VideoView.OnStateChangeListener() {
            @Override
            public void onPlayerStateChanged(int playerState) {
                com.dywatch.app.util.AppLog.i("feed", "播放器状态: " + playerStateName(playerState));
            }

            @Override
            public void onPlayStateChanged(int playState) {
                com.dywatch.app.util.AppLog.i("feed", "播放状态: " + playerStateName(playState));
            }
        });
    }

    private static String playerStateName(int s) {
        switch (s) {
            case VideoView.STATE_IDLE: return "IDLE";
            case VideoView.STATE_PREPARING: return "PREPARING(缓冲中)";
            case VideoView.STATE_PREPARED: return "PREPARED";
            case VideoView.STATE_PLAYING: return "PLAYING(首帧已渲染)";
            case VideoView.STATE_PAUSED: return "PAUSED";
            case VideoView.STATE_BUFFERING: return "BUFFERING";
            case VideoView.STATE_BUFFERED: return "BUFFERED";
            case VideoView.STATE_PLAYBACK_COMPLETED: return "COMPLETED";
            case VideoView.STATE_ERROR: return "ERROR(播放失败)";
            default: return "state=" + s;
        }
    }

    private void initViewPager() {
        mViewPager = findViewById(R.id.vvp);
        mViewPager.setOffscreenPageLimit(3);
        mAdapter = new FeedAdapter(mVideoList, this);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {

            private int mCurItem;
            private boolean mIsReverseScroll;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (position == mCurItem) {
                    return;
                }
                mIsReverseScroll = position < mCurItem;
            }

            @Override
            public void onPageSelected(final int position) {
                if (position == mCurPos) return;
                // 等 ViewPager populate 完成（新页实例化）再挂播放器
                mViewPager.post(new Runnable() {
                    @Override
                    public void run() {
                        startPlay(position);
                    }
                });
                // 快到底时预取下一页：优先让引擎滚精选页抓下一批，推荐源坏了才用原生 feed
                if (position >= mVideoList.size() - 2) {
                    if (!mRecommendBroken && mEngine != null) mEngine.fetchRecommendFeed(true);
                    else loadMore(false);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == VerticalViewPager.SCROLL_STATE_DRAGGING) {
                    mCurItem = mViewPager.getCurrentItem();
                }
                if (state == VerticalViewPager.SCROLL_STATE_IDLE) {
                    mPreloadManager.resumePreload(mCurPos, mIsReverseScroll);
                } else {
                    mPreloadManager.pausePreload(mCurPos, mIsReverseScroll);
                }
            }
        });
    }

    private void startPlay(int position) {
        startPlay(position, 0);
    }

    /**
     * 挂载播放器到指定页。两个真机踩过的坑：
     * ① notifyDataSetChanged 会重建当前页视图、冲掉已挂的播放器 → 数据变更后必须重挂；
     * ② onPageSelected 时新页可能尚未实例化 → 找不到视图不能静默放弃，要重试。
     */
    private void startPlay(final int position, final int attempt) {
        if (position < 0 || position >= mVideoList.size()) return;
        int count = mViewPager.getChildCount();
        for (int i = 0; i < count; i++) {
            View itemView = mViewPager.getChildAt(i);
            FeedAdapter.ViewHolder viewHolder = (FeedAdapter.ViewHolder) itemView.getTag();
            if (viewHolder != null && viewHolder.mPosition == position) {
                mVideoView.release();
                if (mVideoView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mVideoView.getParent()).removeView(mVideoView);
                }

                FeedVideo video = mVideoList.get(position);
                // 播放即记账（真正的"看过"信号，喂给 SeenStore 做跨会话去重）
                if (video.awemeId != null && !video.awemeId.isEmpty()) {
                    SeenStore.markSeen(this, java.util.Collections.singletonList(video.awemeId));
                }
                String playUrl = mPreloadManager.getPlayUrl(video.playUrl);
                mVideoView.setUrl(playUrl);
                mController.addControlComponent(viewHolder.mTikTokView, true);
                viewHolder.mPlayerContainer.addView(mVideoView, 0);
                mVideoView.start();
                mCurPos = position;
                com.dywatch.app.util.AppLog.i("feed", "开始播放 #" + position + " " + playUrl.substring(0, Math.min(40, playUrl.length())));
                return;
            }
        }
        // 页面视图还没实例化/刚被重建 → 延时重试
        if (attempt < 6) {
            com.dywatch.app.util.AppLog.i("feed", "页视图未就绪，重试挂载 #" + position + " (第" + attempt + "次)");
            mViewPager.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startPlay(position, attempt + 1);
                }
            }, 250);
        } else {
            com.dywatch.app.util.AppLog.i("feed", "挂载失败，放弃 #" + position);
        }
    }

    /** 加载固件数据（真实接口数据的快照；数据层接通后替换为网络源） */
    private void loadFixture() {
        try {
            InputStream is = getAssets().open("feed_fixture.json");
            String json = readAll(is);
            JSONArray arr = new JSONArray(json);
            java.util.List<FeedVideo> fixture = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                fixture.add(new FeedVideo(
                        o.optString("title"),
                        o.optString("playUrl"),
                        o.optString("coverUrl")));
            }
            applyNewItems(fixture, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 列表变更单一入口：过滤（看过/重复）→ 入列 → 记账 → 缓存同步 → 通知。
     * ⚠️ 所有对 mVideoList 的批量改动必须走这里（真机教训：散落多处的列表改动
     * 难审计，4 次 IndexOutOfBounds 闪退即源于变更与回收的竞态）。
     * @return 实际新增条数
     */
    private int applyNewItems(java.util.List<FeedVideo> incoming, boolean replace) {
        // 先算后换（原子语义）：accepted 全部算妥才动列表——杜绝"清空后全被过滤=空屏"
        java.util.List<FeedVideo> accepted = new java.util.ArrayList<>();
        java.util.List<String> acceptedIds = new java.util.ArrayList<>();
        for (FeedVideo v : incoming) {
            if (SeenStore.isSeen(this, v.awemeId)) continue;
            boolean dup = false;
            // replace=整批换血，无需比对旧列表；append=连旧列表一起去重
            if (!replace) {
                for (FeedVideo old : mVideoList) {
                    if (old.awemeId != null && old.awemeId.equals(v.awemeId)) { dup = true; break; }
                }
            }
            if (!dup) {
                for (FeedVideo a : accepted) {
                    if (a.awemeId != null && a.awemeId.equals(v.awemeId)) { dup = true; break; }
                }
            }
            if (!dup) {
                accepted.add(v);
                acceptedIds.add(v.awemeId);
            }
        }
        if (replace && accepted.isEmpty()) {
            // 整批都是看过的内容 → 保持现状不空屏，交由调用方补拉
            com.dywatch.app.util.AppLog.i("feed", "整批为看过内容，保持现状待补拉");
            return 0;
        }
        if (replace) mVideoList.clear();
        mVideoList.addAll(accepted);
        SeenStore.markSeen(this, acceptedIds);
        // 应用级缓存：重进页面直接接着放（不再闪固件老视频）
        if (!mVideoList.isEmpty()) {
            sCache.clear();
            sCache.addAll(mVideoList);
        }
        mAdapter.notifyDataSetChanged();
        return accepted.size();
    }

    /** 进页面垫场：有应用级缓存=放上次刷到的视频（不闪老面孔）；真冷启动才用固件 */
    private void loadFixtureOrCache() {
        if (!sCache.isEmpty()) {
            // 缓存回放=恢复现状（这些都已记账），不走"新面孔"过滤
            mVideoList.clear();
            mVideoList.addAll(sCache);
            mAdapter.notifyDataSetChanged();
            com.dywatch.app.util.AppLog.i("feed", "载入缓存 " + mVideoList.size() + " 条");
            return;
        }
        loadFixture();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- 互动（操作栏）：乐观更新 + 后台直写（用户已授权直写尝试，KICK 则重登）----

    @Override
    public void onLike(final FeedVideo video, final FeedAdapter.ViewHolder holder) {
        if (video.awemeId == null || video.awemeId.isEmpty()) {
            android.widget.Toast.makeText(this, "示例视频不支持互动", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean target = !video.liked;
        video.liked = target;
        video.diggCount += target ? 1 : -1;
        if (video.diggCount < 0) video.diggCount = 0;
        FeedAdapter.bindActions(holder, video);
        AppLog.i("feed", "点赞 " + (target ? "＋1" : "取消") + " aweme=" + video.awemeId);
        engineAction("like", video, holder, target, true);
    }

    @Override
    public void onComment(FeedVideo video) {
        if (video.awemeId == null || video.awemeId.isEmpty()) {
            android.widget.Toast.makeText(this, "示例视频没有评论", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.dywatch.app.chat.CommentActivity.class);
        it.putExtra(com.dywatch.app.chat.CommentActivity.EXTRA_AWEME_ID, video.awemeId);
        startActivity(it);
    }

    @Override
    public void onCollect(final FeedVideo video, final FeedAdapter.ViewHolder holder) {
        if (video.awemeId == null || video.awemeId.isEmpty()) {
            android.widget.Toast.makeText(this, "示例视频不支持互动", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean target = !video.collected;
        video.collected = target;
        FeedAdapter.bindActions(holder, video);
        AppLog.i("feed", "收藏 " + (target ? "收藏" : "取消") + " aweme=" + video.awemeId);
        engineAction("collect", video, holder, target, false);
    }

    @Override
    public void onShare(FeedVideo video) {
        android.widget.Toast.makeText(this, "分享开发中", android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 互动走 WebView 引擎（全局单例复用，用户方案：一个 web 走天下）：
     * 引擎打开该视频的网页、点页面自带的赞/藏按钮——页面 SDK 承担全部签名/风控，零 KICK 风险。
     * 乐观更新 + 失败回滚。
     */
    private void engineAction(String kind, final FeedVideo video, final FeedAdapter.ViewHolder holder,
                              final boolean target, final boolean isLike) {
        final com.dywatch.app.chat.ChatEngine engine =
                com.dywatch.app.chat.ChatEngine.getInstance(this, null);
        engine.setActionListener(new com.dywatch.app.chat.ChatEngine.ActionListener() {
            @Override
            public void onActionResult(String action, boolean ok, String detail) {
                engine.setActionListener(null);
                if (ok) {
                    android.widget.Toast.makeText(FeedActivity.this,
                            isLike ? (target ? "已点赞" : "已取消点赞") : (target ? "已收藏" : "已取消收藏"),
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    if (isLike) {
                        video.liked = !target;
                        video.diggCount += target ? -1 : 1;
                    } else {
                        video.collected = !target;
                    }
                    FeedAdapter.bindActions(holder, video);
                    android.widget.Toast.makeText(FeedActivity.this,
                            "操作失败（" + detail + "）", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        if (isLike) {
            engine.likeVideo(video.awemeId, target);
        } else {
            engine.collectVideo(video.awemeId, target);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) mVideoView.resume();
        com.dywatch.app.chat.ChatEngine.attachTo(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.dywatch.app.chat.ChatEngine.detachFrom(this);
        if (mVideoView != null) mVideoView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mViewPager != null) mViewPager.removeCallbacks(mRecommendWatchdog);
        if (mEngine != null) mEngine.setFeedListener(null);
        if (mVideoView != null) mVideoView.release();
        if (mPreloadManager != null) mPreloadManager.removeAllPreloadTask();
    }
}
