package com.dywatch.app.feed;

// 源自 DKVideoPlayer demo 的 TikTok2Activity（Apache-2.0，Copyright Doikki）——
// 官方推荐的仿抖音实现：VerticalViewPager 上下滑 + AndroidVideoCache 预加载 + 单播放器复用。
// 本项目改写：数据源接入真实 Feed（当前为实测 JSON 固件，M2 数据层接通后换接口）。
// License: Apache-2.0（见 lib/LICENSE-DKVideoPlayer.txt）

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.dywatch.app.ui.UiActivity;
import androidx.viewpager.widget.ViewPager;

import com.dywatch.app.R;
import com.dywatch.app.cache.PreloadManager;
import com.dywatch.app.util.AppLog;
import com.dywatch.app.widget.VerticalViewPager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import xyz.doikki.videoplayer.player.VideoView;

public class FeedActivity extends UiActivity implements FeedAdapter.ActionListener {

    /** 当前播放位置 */
    private int mCurPos;
    /**
     * 外部直接塞一批视频来播（「我的」页点喜欢/看过条目时用）。
     * 传了就不自己拉 feed——那批就是本次要刷的全部内容。
     */
    public static final String EXTRA_VIDEO_LIST = "video_list";
    public static final String EXTRA_START_INDEX = "start_index";
    /** 预置列表模式：不再自动拉流、翻页（列表就是这么多） */
    private boolean mPresetMode;
    /** 应用级缓存：重进页面直接放上次刷到的视频（固件只在真冷启动垫场，杜绝"每次进来都是那 2 条老视频"） */
    private static final List<FeedVideo> sCache = new ArrayList<>();
    private com.dywatch.app.net.DouyinApi mApi;
    /** 全局单例引擎（互动用；懒取，见 engineAction） */
    private com.dywatch.app.chat.ChatEngine mEngine;
    /**
     * 连续多少页"全是看过的内容"。
     *
     * 2026-10-02（代码审计 L5）：老实现每遇到一页全看过的就 300ms 后再拉一页，**没有次数上限**，
     * 而且这一路上既不收转圈也不给任何交代——服务端要是持续返回已看过的内容，
     * 就是"转圈不停 + 静默无限翻页"（用户看到的是卡住，实际在一直耗流量）。
     */
    private int mEmptyPages;
    /** 自动补拉的上限（一页 2~5 条，5 页≈十几条都看过了就停，别无限刷） */
    private static final int MAX_EMPTY_PAGES = 5;
    private volatile boolean mLoading;
    private final List<FeedVideo> mVideoList = new ArrayList<>();
    private FeedAdapter mAdapter;
    private VerticalViewPager mViewPager;
    private PreloadManager mPreloadManager;
    private TikTokController mController;
    private VideoView mVideoView;
    /** 全屏控制层（退出/进度/旋转 + 全屏手势）——挂在播放器容器里，跟着一起进全屏 */
    private FullscreenControlView mFullscreenView;
    /** 全屏状态（跟播放器实际状态同步；由 onPlayerStateChanged 维护） */
    private boolean mFullscreen;
    /** 当前 item 的 TikTokView（全屏时它其实被盖住了，但要同步"进/退全屏"的图标状态） */
    private TikTokView mCurTikTokView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed);
        initViewPager();
        initVideoView();
        mPreloadManager = PreloadManager.getInstance(this);

        // 「我的」页可能直接塞一批视频来播（喜欢/看过列表点进来）
        @SuppressWarnings("unchecked")
        java.io.Serializable extra = getIntent().getSerializableExtra(EXTRA_VIDEO_LIST);
        if (extra instanceof java.util.ArrayList) {
            java.util.List<FeedVideo> preset = (java.util.List<FeedVideo>) extra;
            if (!preset.isEmpty()) {
                mPresetMode = true;
                mVideoList.clear();
                mVideoList.addAll(preset);
                mAdapter.notifyDataSetChanged();
                final int start = Math.max(0, Math.min(
                        getIntent().getIntExtra(EXTRA_START_INDEX, 0), preset.size() - 1));
                mViewPager.setCurrentItem(start, false);
                com.dywatch.app.util.AppLog.i("feed", "预置列表播放 " + preset.size()
                        + " 条，从 #" + start + " 开始");
                startPlay(start);
                wireBack();
                return;
            }
        }

        loadFixtureOrCache(); // 冷启动=固件垫场；有缓存=直接放上次的视频（不闪老面孔）
        // 四态反馈：先给"加载中"。首屏要等 Rhino 签名 + 拉流，手表上几秒纯黑屏
        // 会被当成坏了——旧版只有失败/没有更多两态，加载过程没有任何交代。
        showHint("正在拉取视频…");
        loadFeed();           // 主源：原生 tab/feed（实测 2026-09-26：PC 推荐页换条时调的就是这个 endpoint，10 屏 55 条零重复）
        wireBack();
        mViewPager.post(new Runnable() {
            @Override
            public void run() {
                startPlay(0);
            }
        });
    }

    /** 手表硬件适配 §10：可见返回键（无手势/无按键设备可回主屏） */
    private void wireBack() {
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /**
     * 四态反馈统一出口：有事说事，没事收起来——刷视频页全屏，
     * 提示行常驻会一直压在画面上。
     */
    private void showHint(String text) {
        android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
        if (hint == null) return;
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
    }

    private void clearHint() {
        android.widget.TextView hint = findViewById(R.id.tv_feed_hint);
        if (hint != null) hint.setVisibility(View.GONE);
    }

    /** 网络优先加载真实 Feed（M2 数据层），失败回退固件。分页：抖音每页只回 2~5 条 */
    private void loadFeed() {
        loadMore(true);
    }

    /** 懒建 DouyinApi（签名 JS + 登录态 + 持久游标）；只在后台线程调 */
    private com.dywatch.app.net.DouyinApi ensureApi() throws Exception {
        if (mApi != null) return mApi;
        java.util.List<String> js = java.util.Arrays.asList(
                readAll(getAssets().open("sign/utils.js")),
                readAll(getAssets().open("sign/sm3.js")),
                readAll(getAssets().open("sign/vm_decode.js")));
        mApi = new com.dywatch.app.net.DouyinApi(new com.dywatch.app.sign.Signer(js));
        // 画质偏好来自设置页
        com.dywatch.app.net.DouyinApi.sQuality = com.dywatch.app.ui.Settings.qualityMode(this);
        // 带登录态拉流（个性化推荐）与互动身份
        mApi.setSessionCookie(com.dywatch.app.login.LoginManager.getCookies(this));
        // 翻页游标持久化：从上次进度继续（否则每次进页面都拉第1页=同样视频）
        mApi.setRefreshIndex(getSharedPreferences("feed_state", MODE_PRIVATE).getInt("refresh_index", 0));
        return mApi;
    }

    private void loadMore(final boolean first) {
        // 预置列表模式：列表就是这么多，不去拉流（拉了反而把人家的喜欢列表冲掉）
        if (mPresetMode) return;
        // 页面已经在关：别再起新的网络请求（补拉的延时任务会走到这里）
        if (isFinishing() || isDestroyed()) return;
        if (mLoading) return;
        mLoading = true;
        // 首屏要等签名 + 拉流（手表上更久），内容区正中给个在转的圈
        if (first) com.dywatch.app.ui.Loading.show(this, true);
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
                    // 封面/头像是否真的解析出来了：这两个字段以前都是坏的（封面被拼成播放端点、
                    // 头像压根没解析），光看"拉到几条"完全看不出来——必须单独记账才能验证。
                    int cov = 0, ava = 0;
                    for (FeedVideo v : list) {
                        if (v.coverUrl != null && !v.coverUrl.isEmpty()) cov++;
                        if (v.authorAvatar != null && !v.authorAvatar.isEmpty()) ava++;
                    }
                    com.dywatch.app.util.AppLog.i("feed", "本页封面 " + cov + "/" + list.size()
                            + " 条、作者头像 " + ava + "/" + list.size() + " 条");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mLoading = false;
                            // 列表里若还混着固件/缓存项（URL 是几小时甚至上次会话烤的，签名早过期→必死链），
                            // 首批真数据到货就整批换掉，否则播放器会一直卡在 #0 那个放不出画面的垫场项上。
                            boolean hasStale = false;
                            for (FeedVideo v : mVideoList) {
                                if (v.awemeId == null || v.awemeId.isEmpty()) { hasStale = true; break; }
                            }
                            int added = applyNewItems(list, first || hasStale);
                            com.dywatch.app.util.AppLog.i("feed", "新面孔 " + added + "/" + list.size()
                                    + " 条（共 " + mVideoList.size() + "）");
                            // notifyDataSetChanged 会重建页面视图 → 无条件重挂当前播放
                            startPlay(mViewPager.getCurrentItem());
                            if (list.isEmpty()) {
                                mEmptyPages = 0;
                                showHint("没有更多了");
                                com.dywatch.app.ui.Loading.show(FeedActivity.this, false);
                            } else if (added == 0) {
                                // 本页全是看过的内容 → 自动补拉下一页（有上限，见 MAX_EMPTY_PAGES）
                                mEmptyPages++;
                                com.dywatch.app.util.AppLog.i("feed", "本页全为看过内容，自动补拉（第 "
                                        + mEmptyPages + "/" + MAX_EMPTY_PAGES + " 页）");
                                if (mEmptyPages >= MAX_EMPTY_PAGES) {
                                    // 到顶：必须收圈 + 明确交代。老实现这里什么都不做，
                                    // 用户看到的是"转圈永远不停、流量一直在跑"。
                                    com.dywatch.app.util.AppLog.i("feed", "连续 " + MAX_EMPTY_PAGES
                                            + " 页全为看过内容，停止自动补拉");
                                    showHint("这一批都看过了，稍后再来");
                                    com.dywatch.app.ui.Loading.show(FeedActivity.this, false);
                                } else {
                                    mViewPager.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isFinishing() || isDestroyed()) return;
                                            loadMore(false);
                                        }
                                    }, 300);
                                }
                            } else {
                                mEmptyPages = 0;
                                // 有内容就不占画面（全屏刷视频，提示行压在画面上很碍事）
                                clearHint();
                                com.dywatch.app.ui.Loading.show(FeedActivity.this, false);
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
                            mEmptyPages = 0;   // 失败就重新开始计数，别把上一次的失败算进上限
                            com.dywatch.app.ui.Loading.show(FeedActivity.this, false);
                            if (isFinishing() || isDestroyed()) return;
                            if (first) loadFixtureOrCache();
                            showHint((first ? "网络加载失败，已回退内置数据。\n" : "翻页失败: ") + err);
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

        // 全屏控制层（软件内问题 ⑤）：作为"非游离"组件挂进 controller（false = 视图交给 controller 管理），
        // 这样它就在 mPlayerContainer 内部 → 进全屏时跟着容器一起搬到 DecorView，不会被留在 ViewPager 里。
        // 它自己只在 PLAYER_FULL_SCREEN 时可见（见 FullscreenControlView.onPlayerStateChanged），
        // 所以非全屏时对信息流零影响（包括触摸）。
        mFullscreenView = new FullscreenControlView(this);
        mController.addControlComponent(mFullscreenView, false);
        mFullscreenView.setListener(new FullscreenControlView.Listener() {
            @Override public void onExitFullscreen() {
                exitFullscreen();
            }

            @Override public void onRotate() {
                toggleRotation();
            }
        });
        // 渲染视图每次换条会重建，所以给"取"的入口而不是缓存一个引用
        mFullscreenView.setRenderTargetProvider(new FullscreenControlView.RenderTargetProvider() {
            @Override public View get() {
                return mVideoView == null ? null : mVideoView.getRenderView();
            }
        });
        // 播放器状态全量落日志（真机教训：拉流失败被静默吞掉=只显封面，无日志根本查不出）
        mVideoView.addOnStateChangeListener(new VideoView.OnStateChangeListener() {
            @Override
            public void onPlayerStateChanged(int playerState) {
                com.dywatch.app.util.AppLog.i("feed", "播放器状态: " + playerStateName(playerState));
                // 全屏状态跟**实际**走（软件内问题 ⑤）：进全屏的不一定是我们点的那个键——
                // dkplayer 的传感器监听在横屏时也会自动进全屏（见 BaseVideoController.onOrientationLandscape）。
                // 所以内缩清零 / 图标 / 缩放复位都挂在这里，而不是只挂在按钮回调里。
                boolean full = playerState == VideoView.PLAYER_FULL_SCREEN;
                if (full != mFullscreen) {
                    mFullscreen = full;
                    if (full) {
                        getWindow().getDecorView().getRootView().setPadding(0, 0, 0, 0);
                    } else {
                        applyPageInsets();                                  // 按设置重放圆屏内缩
                        if (mFullscreenView != null) mFullscreenView.resetZoom();
                    }
                    if (mCurTikTokView != null) mCurTikTokView.setFullscreenState(full);
                    com.dywatch.app.util.AppLog.i("feed", "全屏状态变更："
                            + (full ? "进入（内缩清零）" : "退出（内缩已重放）"));
                }
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
                // 快到底时预取下一页
                if (!mPresetMode && position >= mVideoList.size() - 2) {
                    loadMore(false);
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
                    // 同一件事的第二个用途：给「我的」页的"看过"列表留一份可读记录
                    // （去重账本只存 id，显示要标题和封面，所以另存一份）
                    HistoryStore.mark(this, video.awemeId, video.title, video.coverUrl);
                }
                String playUrl = mPreloadManager.getPlayUrl(video.playUrl);
                mVideoView.setUrl(playUrl, com.dywatch.app.net.DouyinApi.playHeaders());
                mController.addControlComponent(viewHolder.mTikTokView, true);
                // 全屏入口接线（每个 item 的 TikTokView 都要接：换条后拿到的是新的实例）
                viewHolder.mTikTokView.setOnFullscreenClickListener(new TikTokView.OnFullscreenClick() {
                    @Override public void onFullscreenClick() {
                        if (mVideoView.isFullScreen()) {
                            exitFullscreen();
                        } else {
                            enterFullscreen();
                        }
                    }
                });
                viewHolder.mTikTokView.setFullscreenState(mVideoView.isFullScreen());
                mCurTikTokView = viewHolder.mTikTokView;
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

    /**
     * 真冷启动的垫场数据（M2 时代的接口快照）。
     *
     * ⚠️ 2026-09-27 复盘：固件里的签名播放 URL 几小时就过期（代码注释自己写着"必死链"），
     * 而真冷启动如今已极少走到这里——首屏失败会 showHint"网络加载失败"并自动重试，
     * 垫几条黑屏死链反而是负体验。所以改成**空列表 + 提示**，不再读死链固件。
     * （固件文件保留在 assets 里，等哪天要做"离线演示模式"再启用。）
     */
    private void loadFixture() {
        applyNewItems(new java.util.ArrayList<FeedVideo>(), false);
        showHint("正在连接网络，首次加载需要几秒…");
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
        if (actionBusy("like", video.awemeId)) return;
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
        if (actionBusy("collect", video.awemeId)) return;
        video.collected = target;
        FeedAdapter.bindActions(holder, video);
        AppLog.i("feed", "收藏 " + (target ? "收藏" : "取消") + " aweme=" + video.awemeId);
        engineAction("collect", video, holder, target, false);
    }

    /**
     * 分享（2026-09-27 补做）。
     *
     * 实现取舍：手表上没有几个能接收 ACTION_SEND 的应用，弹系统分享面板大概率是
     * "无应用可处理"——所以这里落成「复制作品链接到剪贴板」，这是手表上真正可用的分享路径：
     * 复制后到手机上粘贴即可发给别人。零风控、零依赖、任何设备都成立。
     * （若之后要"分享给私信好友"，那是另一件事：需要会话选择器 + 引擎发送，见工作日志待办。）
     */
    @Override
    public void onShare(FeedVideo video) {
        if (video.awemeId == null || video.awemeId.isEmpty()) {
            android.widget.Toast.makeText(this, "示例视频没有分享链接", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String url = "https://www.douyin.com/video/" + video.awemeId;
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                android.widget.Toast.makeText(this, "本机没有剪贴板服务", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("抖音作品链接", url));
            // 手表屏窄，toast 只报结果，不把整条 URL 铺出来
            android.widget.Toast.makeText(this, "链接已复制，去手机粘贴分享", android.widget.Toast.LENGTH_SHORT).show();
            AppLog.i("feed", "分享=复制链接 aweme=" + video.awemeId + " url=" + url);
        } catch (Throwable t) {
            // 个别 ROM 的剪贴板服务会抛（权限/厂商改），不能让分享把页面搞崩
            AppLog.i("feed", "分享失败：" + t);
            android.widget.Toast.makeText(this, "复制失败：" + t.getClass().getSimpleName(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 点作者头像 → 进主页。作者主页是纯只读（作品列表 + 用户信息），
     * 走原生 API 直连，不动 WebView 引擎。
     */
    @Override
    public void onAvatar(FeedVideo video) {
        if (video.authorSecUid == null || video.authorSecUid.isEmpty()) {
            android.widget.Toast.makeText(this, "拿不到作者信息（示例视频不支持）",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, com.dywatch.app.ui.UserActivity.class);
        it.putExtra(com.dywatch.app.ui.UserActivity.EXTRA_SEC_UID, video.authorSecUid);
        it.putExtra(com.dywatch.app.ui.UserActivity.EXTRA_NAME, video.authorName);
        startActivity(it);
        AppLog.i("feed", "进作者主页 " + video.authorName);
    }

    /**
     * 同一条视频的同一个互动在途时不接受第二次。必须在"乐观翻转"之前拦：翻转发生在
     * 调用 engineAction 之前，拦在里面会留下翻了却没人回滚的 UI（桥侧 __inFlight
     * 只挡 JS 那层，挡不住这里已经翻掉的显示）。
     *
     * ⚠️ 2026-09-27 修：原先是一个 String 单槽（mActionBusy = "like:xxx"），
     * 于是「同一条视频先点赞、再收藏」会把槽覆盖成 "collect:xxx"，
     * 而点赞那条的 30s 兜底定时器判的是 endsWith(":"+awemeId) —— 它会把**收藏**的
     * 在途标记提前清掉，收藏就能被重复提交。改成按 key 独立记账。
     *
     * ⚠️ 2026-10-02 修（代码审计 M17）：值从"布尔"变成**请求号**。
     * 原因是引擎侧的回调槽也被第二次互动顶掉了：点赞的结果会被当成收藏的结果处理
     * （弹错 toast、回滚错的状态），而收藏的结果谁都收不到、只能等 30 秒兜底，
     * 界面上留下"看着已收藏、其实没收藏"的假状态。现在每次互动带一个请求号，
     * 引擎按请求号把结果投回本次调用自己的闭包，互不干扰。
     */
    private final java.util.Map<String, String> mActionsInFlight = new java.util.HashMap<>();

    /** 互动超时兜底专用 Handler：onDestroy 一次性清空，免得定时器抱着已销毁的页面不放 */
    private final android.os.Handler mActionTimeout = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean actionBusy(String kind, String awemeId) {
        String key = kind + ":" + awemeId;
        if (mActionsInFlight.containsKey(key)) {
            android.widget.Toast.makeText(this, "上一次操作还在进行中…", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    /**
     * 互动走 WebView 引擎（全局单例复用，用户方案：一个 web 走天下）：
     * 引擎打开该视频的网页、点页面自带的赞/藏按钮——页面 SDK 承担全部签名/风控，零 KICK 风险。
     * 乐观更新 + 失败回滚。
     */
    private void engineAction(final String kind, final FeedVideo video, final FeedAdapter.ViewHolder holder,
                              final boolean target, final boolean isLike) {
        if (mEngine == null) mEngine = com.dywatch.app.chat.ChatEngine.getInstance(this, null);
        final com.dywatch.app.chat.ChatEngine engine = mEngine;
        final String awemeId = video.awemeId;
        final String key = kind + ":" + awemeId;
        com.dywatch.app.chat.ChatEngine.ActionListener cb = new com.dywatch.app.chat.ChatEngine.ActionListener() {
            @Override
            public void onActionResult(String action, boolean ok, String detail) {
                mActionsInFlight.remove(key);
                // 页面已经在关（用户在等结果的几秒里退出了）：回调和超时都别再动 UI
                if (isFinishing() || isDestroyed()) return;
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
        };
        String reqId = isLike ? engine.likeVideo(awemeId, target, cb)
                : engine.collectVideo(awemeId, target, cb);
        mActionsInFlight.put(key, reqId);
        // 桥没回音（页面异常/被吞）时不能把互动按钮永久锁死。只清自己这一条 key。
        //
        // ⚠️ 2026-10-02 真机实测修正：这里**只解锁，不撤销回调登记**。
        // 第一版我在超时时顺手调了 engine.cancelAction(reqId)，结果真机上一次真实的点赞是这样：
        //   点击 → 引擎跳 /video/<id> → 等页面就绪 → 点击 → 4 轮复查 → **34 秒**后才回报 ok=true，
        // 而 30 秒的兜底先到，把登记撤了 → 日志变成"互动结果无接收方（reqId=r1）"，
        // 用户**永远等不到"已点赞"**（成功也静默、失败也不回滚）。
        // 也就是说：慢一点的成功会被我们自己丢掉。现在超时只解锁按钮，迟到的结果照样投递
        // （页面若已销毁，回调里的 isFinishing/isDestroyed 会拦住 UI 操作）。
        mActionTimeout.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mActionsInFlight.remove(key) != null) {
                    AppLog.i("feed", "互动超时解锁按钮（结果仍会投递） " + key);
                }
            }
        }, 30000);
    }

    /** 刷视频要常亮：手表抬腕亮屏时长有限，不常亮会看着看着黑屏（设置里可关） */
    @Override
    protected boolean keepScreenOnWhileVisible() {
        return true;
    }

    // ---------- 全屏（软件内问题 ⑤，2026-10-01）----------

    /**
     * 进全屏（软件内问题 ⑤，2026-10-01）。
     *
     * ① `mController.enterFullscreen()` 把播放器容器搬到 DecorView 并转横屏——搬完之后
     *    ViewPager 再也收不到事件，所以"全屏里加缩放手势"对信息流手势是**零回归**的
     *    （方案 §5.2 的关键收益，也是不必去改 2700 行 ViewPager 的原因）；
     * ② 内缩清零 / 图标 / 缩放复位统一在 onPlayerStateChanged 里做（传感器自动全屏也走那条路）。
     */
    private void enterFullscreen() {
        if (mVideoView == null || mVideoView.isFullScreen()) return;
        mController.enterFullscreen();
        com.dywatch.app.util.AppLog.i("feed", "点全屏键 → 进入全屏");
    }

    /** 退全屏：转回竖屏 + 内缩按设置重放（都在 dkplayer/状态回调里完成） */
    private void exitFullscreen() {
        if (mVideoView == null || !mVideoView.isFullScreen()) return;
        mController.exitFullscreen();
        com.dywatch.app.util.AppLog.i("feed", "点退出全屏");
    }

    /**
     * 全屏里手动旋转。手表屏 372×430：横屏视频在竖屏里只占 **48.6%** 高，转成横屏后能到 **65.1%**
     * （方案 §5.1 的实测数字）。
     *
     * ⚠️ 真机实测（2026-10-01）：dkplayer 的传感器监听**在全屏时是开着的**——把表转成横屏拿，
     *    它会自动转过去（`BaseVideoController.onOrientationLandscape`）。所以这里做成"手动覆盖"：
     *    直接读当前 requestedOrientation 决定往哪边翻，不自己维护一个可能和现实不符的布尔值。
     *    （反过来它不会自动转回竖屏：`onOrientationPortrait` 要求 `mEnableOrientation`，默认关。）
     */
    private void toggleRotation() {
        int cur = getRequestedOrientation();
        boolean toLandscape = cur != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(toLandscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        // 尺寸变了 → 缩放/平移的夹取范围也变了，复位免得画面跑偏
        if (mFullscreenView != null) mFullscreenView.resetZoom();
        com.dywatch.app.util.AppLog.i("feed", "全屏旋转：" + (toLandscape ? "横屏" : "竖屏"));
    }

    /** 全屏时返回键先退全屏（用户多半只是想回信息流，不是想退出页面） */
    @Override
    public void onBackPressed() {
        if (mVideoView != null && mVideoView.isFullScreen()) {
            exitFullscreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) mVideoView.resume();
        // 预加载跟着页面可见性走（审计 L6/L8）：后台时整队停，回来时把没预热好的补齐
        if (mPreloadManager != null) mPreloadManager.resumeAll();
        com.dywatch.app.chat.ChatEngine.attachTo(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.dywatch.app.chat.ChatEngine.detachFrom(this);
        // ⚠️ 原来只停了播放器：预加载队列不管，用户按 Home 走后还在继续下（每条最多 1MB）
        if (mPreloadManager != null) mPreloadManager.pauseAll();
        if (mVideoView != null) mVideoView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ⚠️ 2026-10-02 修（代码审计 H5）：引擎是**进程单例**，互动回调里抓着本页的
        // video/holder/this。退出刷视频页时不主动注销，回调会一直挂在引擎上，
        // 整棵信息流视图树（含每个 item 的封面、播放器容器）跟着单例活到进程结束。
        if (mEngine != null) {
            for (String reqId : mActionsInFlight.values()) {
                mEngine.cancelAction(reqId);
            }
        }
        mActionsInFlight.clear();
        mActionTimeout.removeCallbacksAndMessages(null);
        if (mVideoView != null) mVideoView.release();
        if (mPreloadManager != null) mPreloadManager.removeAllPreloadTask();
    }
}
