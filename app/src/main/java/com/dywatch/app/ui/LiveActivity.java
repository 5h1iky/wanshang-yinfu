package com.dywatch.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.dywatch.app.R;
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

import xyz.doikki.videoplayer.player.VideoView;

/** 独立直播播放页：无弹幕、无送礼、不混入短视频滑动。 */
public class LiveActivity extends UiActivity {
    private static final String EXTRA_ROOM = "room";
    public static void open(Activity activity, DouyinApi.LiveRoom room) {
        Intent it = new Intent(activity, LiveActivity.class);
        it.putExtra(EXTRA_ROOM, room);
        activity.startActivity(it);
    }

    private DouyinApi.LiveRoom mRoom;
    private DouyinApi mApi;
    private VideoView mPlayer;
    private TextView mHint, mQuality;
    private View mControls;
    private Button mFullscreenBtn;
    private ProgressBar mLoading;
    private final List<DouyinApi.LiveStream> mStreams = new ArrayList<>();
    private int mStreamIndex, mGeneration;
    private boolean mLoadingRoom, mControlsVisible = true, mStarted, mFullscreen;
    private com.dywatch.app.feed.FullscreenGestureHelper mGesture;
    private final android.os.Handler mUi = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mHideControls = () -> {
        if (!isFinishing() && !isDestroyed()) hideControls();
    };
    private final Runnable mHideHint = () -> {
        if (mHint != null) mHint.setVisibility(View.GONE);
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        mRoom = (DouyinApi.LiveRoom) getIntent().getSerializableExtra(EXTRA_ROOM);
        mPlayer = findViewById(R.id.live_player);
        mHint = findViewById(R.id.tv_live_hint);
        mControls = findViewById(R.id.live_controls);
        mQuality = findViewById(R.id.btn_live_quality);
        mFullscreenBtn = findViewById(R.id.btn_live_fullscreen);
        mLoading = findViewById(R.id.live_progress);
        if (mRoom == null || mRoom.webRid.isEmpty()) {
            hint("直播间信息不完整，请返回重试");
            findViewById(R.id.btn_live_exit).setOnClickListener(v -> finish());
            return;
        }
        TextView title = findViewById(R.id.tv_live_title);
        title.setText(mRoom.title.isEmpty() ? "直播" : mRoom.title);
        ((TextView) findViewById(R.id.tv_live_owner)).setText(mRoom.ownerName);
        ImageView cover = findViewById(R.id.iv_live_cover);
        Glide.with(this).load(mRoom.coverUrl).centerCrop().into(cover);
        mPlayer.setLooping(false);
        mPlayer.addOnStateChangeListener(new VideoView.OnStateChangeListener() {
            @Override public void onPlayerStateChanged(int state) { }
            @Override public void onPlayStateChanged(int state) {
                if (state == VideoView.STATE_PLAYING) {
                    mLoading.setVisibility(View.GONE);
                    hint("直播中 · 单击画面显示控制");
                    showControls();
                } else if (state == VideoView.STATE_ERROR) {
                    mLoading.setVisibility(View.GONE);
                    hint("播放失败，可点“重连”或切换清晰度");
                    showControls();
                } else if (state == VideoView.STATE_PLAYBACK_COMPLETED) {
                    hint("直播已结束或连接中断，可点“重连”");
                    showControls();
                }
            }
        });
        setupGestures();
        findViewById(R.id.btn_live_reconnect).setOnClickListener(v -> { showControls(); loadRoom(); });
        findViewById(R.id.btn_live_exit).setOnClickListener(v -> finish());
        findViewById(R.id.btn_live_fullscreen).setOnClickListener(v -> toggleFullscreen());
        findViewById(R.id.btn_live_rotate).setOnClickListener(v -> toggleRotation());
        findViewById(R.id.btn_live_reset).setOnClickListener(v -> {
            resetZoom();
            showControls();
            android.widget.Toast.makeText(this, "画面已复位", android.widget.Toast.LENGTH_SHORT).show();
        });
        mQuality.setOnClickListener(v -> {
            if (mStreams.isEmpty()) return;
            showControls();
            mStreamIndex = (mStreamIndex + 1) % mStreams.size();
            playCurrent();
        });
        showControls();
        loadRoom();
    }

    private void loadRoom() {
        if (mLoadingRoom) return;
        mLoadingRoom = true;
        final int generation = mGeneration;
        mPlayer.release();
        mLoading.setVisibility(View.VISIBLE);
        hint("正在进入直播间…");
        new Thread(() -> {
            try {
                List<DouyinApi.LiveStream> streams = ensureApi().enterLive(mRoom.webRid);
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoadingRoom = false;
                    mStreams.clear();
                    // HLS 优先、低分辨率档优先；不会因为“原画”默认拉最大流量。
                    streams.sort((a, b) -> {
                        if (a.hls != b.hls) return a.hls ? -1 : 1;
                        return streamRank(a) - streamRank(b);
                    });
                    for (DouyinApi.LiveStream stream : streams) {
                        boolean duplicate = false;
                        for (DouyinApi.LiveStream old : mStreams) if (old.url.equals(stream.url)) { duplicate = true; break; }
                        if (!duplicate) mStreams.add(stream);
                    }
                    mStreamIndex = 0;
                    playCurrent();
                });
            } catch (Exception e) {
                AppLog.i("live", "进房失败: " + e.getClass().getSimpleName());
                runOnUiThread(() -> {
                    if (generation != mGeneration || isFinishing() || isDestroyed()) return;
                    mLoadingRoom = false;
                    mLoading.setVisibility(View.GONE);
                    hint(DouyinApi.friendlyMessage(e));
                });
            }
        }, "live-enter").start();
    }

    private int streamRank(DouyinApi.LiveStream stream) {
        String name = stream.name.toUpperCase(java.util.Locale.US);
        if (stream.codec.toUpperCase(java.util.Locale.US).contains("265")) return 50;
        if (name.contains("SD") || name.contains("540") || name.contains("LD")) return 0;
        if (name.contains("HD") || name.contains("720")) return 10;
        if (name.contains("FULL") || name.contains("1080") || name.contains("ORIGIN")) return 20;
        return 5;
    }

    private void playCurrent() {
        if (mStreamIndex < 0 || mStreamIndex >= mStreams.size()) return;
        DouyinApi.LiveStream stream = mStreams.get(mStreamIndex);
        mQuality.setText((stream.hls ? "HLS " : "FLV ") + qualityName(stream.name));
        mPlayer.release();
        mPlayer.setUrl(stream.url, DouyinApi.livePlayHeaders());
        mLoading.setVisibility(View.VISIBLE);
        mPlayer.start();
        mStarted = true;
        hint("正在连接直播…");
        AppLog.i("live", "开始播放 protocol=" + (stream.hls ? "HLS" : "FLV") + " quality=" + stream.name);
    }

    private static String qualityName(String raw) {
        if (raw == null || raw.isEmpty()) return "流畅";
        String upper = raw.toUpperCase(java.util.Locale.US);
        if (upper.contains("ORIGIN")) return "原画";
        if (upper.contains("FULL")) return "超清";
        if (upper.contains("HD")) return "高清";
        if (upper.contains("SD")) return "标清";
        return raw;
    }

    private void setupGestures() {
        mGesture = new com.dywatch.app.feed.FullscreenGestureHelper(this,
                () -> mPlayer == null ? null : mPlayer.getRenderView(),
                new com.dywatch.app.feed.FullscreenGestureHelper.Callback() {
                    @Override public void onSingleTap() { toggleControls(); }
                    @Override public void onDoubleTap(android.view.MotionEvent e, boolean zoomed) {
                        // 直播没有进度可 seek：双击已缩放时由 helper 复位，未缩放时不动作。
                    }
                });
        findViewById(R.id.live_touch).setOnTouchListener((v, event) -> {
            if (!Settings.fullscreenGesture(LiveActivity.this)) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) toggleControls();
                return true;
            }
            boolean consumed = mGesture.onTouchEvent(event);
            return consumed || event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN;
        });
    }

    private void toggleControls() {
        if (mControlsVisible) hideControls(); else showControls();
    }

    private void showControls() {
        mControlsVisible = true;
        mControls.setVisibility(View.VISIBLE);
        findViewById(R.id.live_title_bar).setVisibility(View.VISIBLE);
        mUi.removeCallbacks(mHideControls);
        mUi.postDelayed(mHideControls, 4000);
    }

    private void hideControls() {
        mControlsVisible = false;
        mControls.setVisibility(View.GONE);
        findViewById(R.id.live_title_bar).setVisibility(View.GONE);
        mUi.removeCallbacks(mHideControls);
        findViewById(R.id.tv_live_hint).setVisibility(View.GONE);
    }

    private void toggleFullscreen() {
        mFullscreen = !mFullscreen;
        if (mFullscreen) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            applyImmersive(true);
            mFullscreenBtn.setText("退出");
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            applyImmersive(false);
            mFullscreenBtn.setText("全屏");
        }
        resetZoom();
        showControls();
        AppLog.i("live", "全屏切换：" + (mFullscreen ? "进入" : "退出"));
    }

    private void toggleRotation() {
        int cur = getRequestedOrientation();
        boolean toLandscape = cur != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        setRequestedOrientation(toLandscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        resetZoom();
        showControls();
        AppLog.i("live", "旋转：" + (toLandscape ? "横屏" : "竖屏"));
    }

    private void applyImmersive(boolean on) {
        android.view.View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void resetZoom() {
        if (mGesture != null) mGesture.reset();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mFullscreen) applyImmersive(true);
    }

    @Override
    public void onBackPressed() {
        if (mFullscreen) {
            toggleFullscreen();
            return;
        }
        super.onBackPressed();
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
    private void hint(String text) {
        mHint.setText(text);
        mHint.setVisibility(View.VISIBLE);
        mUi.removeCallbacks(mHideHint);
        mUi.postDelayed(mHideHint, 2500);
    }
    @Override protected boolean keepScreenOnWhileVisible() { return true; }
    @Override protected void onPause() { super.onPause(); if (mPlayer != null) mPlayer.pause(); }
    @Override protected void onResume() { super.onResume(); if (mStarted && mPlayer != null) mPlayer.resume(); }
    @Override protected void onDestroy() {
        mGeneration++;
        mUi.removeCallbacksAndMessages(null);
        if (mPlayer != null) mPlayer.release();
        super.onDestroy();
    }
}
