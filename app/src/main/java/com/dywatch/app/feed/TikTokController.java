package com.dywatch.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dywatch.app.R;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 抖音式控制层（方案 D，2026-09-27 五修：可呼出控制面板，用户拍板的交互逻辑）：
 *
 *   单击画面 = 呼出控制面板（中央暂停键 + 进度条）；
 *   面板显示时点暂停键 = 暂停/继续；拖进度条 = seek；
 *   面板显示 4 秒无操作自动隐藏（BaseVideoController 自带 fadeOut 计时）；
 *   双击左右半屏 = ±10s（保留，与面板显隐无关）；
 *   上下滑切换视频仍归 ViewPager。
 *
 * 实现分工（触控事件的可达性是 dkplayer 分层决定的，真机实测过）：
 *   - TikTokView（item 最上层触点）：单击确认→toggleShowState() 呼出/收起面板，
 *     双击→±10s seek，底部热区拖动→seek；
 *   - TikTokController（本类，被 TikTokView 盖住）：只在面板可见时接收其子控件
 *     （暂停键/SeekBar）的点击，其余触点返回 false 让 TikTokView 继续拿事件。
 *
 * 面板显隐用 BaseVideoController 的 show()/hide()（内部 mShowing + 4s fadeOut），
 * 不自造计时器；暂停键图标随播放状态切换（播放中显"暂停"icon，暂停时显"播放"icon）。
 */
public class TikTokController extends BaseVideoController {

    private static final long SEEK_STEP_MS = 10_000;   // 双击快进/快退步长

    private SeekBar mSeekBar;
    private View mPlayToggle;
    private ImageView mPlayStateIcon;
    private GestureDetector mGesture;
    /** 用户拖动进度条期间：不吃 setProgress 回调（否则滑块被播放进度拽回去） */
    private boolean mFromUser;
    /** seek 落定窗口截止时刻：窗口内忽略进度回调（MediaPlayer 位置还没跳过去，会闪回） */
    private long mSeekSettleUntil;
    /** 控制层尺寸取证只打一次 */
    private boolean mSizeLogged;

    public TikTokController(@NonNull Context context) {
        super(context);
    }

    public TikTokController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TikTokController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        mGesture = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        boolean forward = e.getX() >= getWidth() / 2f;
                        doSeek(forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
                        return true;
                    }
                });
        // ⚠️ 实例初始化块在 super 构造器（含 initView()→inflate）之后执行，
        // findViewById 一定拿得到 getLayoutId() inflate 进来的子视图。
        mSeekBar = findViewById(R.id.sb_progress);
        mPlayToggle = findViewById(R.id.fl_play_toggle);
        mPlayStateIcon = findViewById(R.id.iv_play_state);
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || mControlWrapper == null) return;
                    long duration = mControlWrapper.getDuration();
                    if (duration <= 0) return;
                    mControlWrapper.seekTo((long) (progress / (float) seekBar.getMax() * duration));
                    logSeek("拖动", (long) (progress / (float) seekBar.getMax() * duration), duration);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mFromUser = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mFromUser = false;
                    markSeekSettling();
                    startFadeOut();   // 拖完 4s 自动收
                }
            });
        }
        if (mPlayToggle != null) {
            mPlayToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mControlWrapper != null) {
                        mControlWrapper.togglePlay();
                        // 暂停时面板保持常显（不计时收起），继续播放才重新计时
                        if (mControlWrapper.isPlaying()) startFadeOut();
                        else stopFadeOut();
                    }
                }
            });
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.layout_tiktok_control_bar;
    }

    @Override
    public boolean showNetWarning() {
        return false;
    }

    /** 面板显示/隐藏：暂停键 + 进度条一起显隐 */
    @Override
    protected void onVisibilityChanged(boolean isVisible, android.view.animation.Animation anim) {
        super.onVisibilityChanged(isVisible, anim);
        if (mPlayToggle != null) mPlayToggle.setVisibility(isVisible ? VISIBLE : GONE);
        if (mSeekBar != null) mSeekBar.setVisibility(isVisible ? VISIBLE : GONE);
    }

    /** 手表适配：进度条收窄到父宽 70% 并水平居中（圆屏左右两端被圆形边框裁切，铺满必被切）。
     *  XML 写不了"父宽的 70%"，运行时设。幂等；onSizeChanged 与 PREPARED 双保险。 */
    private void applyWatchWidth() {
        if (mSeekBar == null || getWidth() <= 0) return;
        android.view.ViewGroup.LayoutParams raw = mSeekBar.getLayoutParams();
        int target = (int) (getWidth() * 0.7f);
        if (raw.width != target) {
            raw.width = target;
            if (raw instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) raw).gravity =
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            }
            mSeekBar.setLayoutParams(raw);
            com.dywatch.app.util.AppLog.i("seek", "进度条收窄 " + target + "/" + getWidth());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyWatchWidth();
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        // 中央暂停键图标：播放中显"暂停"，暂停时显"播放"（dkplayer 的 selector 按状态切换）
        if (mPlayStateIcon != null && mControlWrapper != null) {
            mPlayStateIcon.setSelected(playState != VideoView.STATE_PLAYING);
        }
        // 面板显示中且暂停 → 停掉自动收起；恢复播放 → 重新计时
        if (mControlWrapper != null && isShowing()) {
            if (playState == VideoView.STATE_PAUSED) stopFadeOut();
            else if (playState == VideoView.STATE_PLAYING) startFadeOut();
        }
        // 一次性取证
        if (mSeekBar != null && !mSizeLogged && playState == VideoView.STATE_PREPARED) {
            mSizeLogged = true;
            post(new Runnable() {
                @Override
                public void run() {
                    applyWatchWidth();
                    com.dywatch.app.util.AppLog.i("seek", "控制层尺寸 controller="
                            + getWidth() + "x" + getHeight() + " seekBar="
                            + mSeekBar.getWidth() + "x" + mSeekBar.getHeight()
                            + " attached=" + mSeekBar.isAttachedToWindow());
                }
            });
        }
        // 进度刷新开关：PLAYING/PAUSED 时刷（暂停也要把当前位置画上），缓冲/错误停
        if (mControlWrapper == null) return;
        if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_PAUSED) {
            mControlWrapper.startProgress();
        } else if (playState == VideoView.STATE_BUFFERING) {
            mControlWrapper.stopProgress();
        } else if (playState == VideoView.STATE_IDLE
                || playState == VideoView.STATE_ERROR
                || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
            mControlWrapper.stopProgress();
            if (mSeekBar != null) mSeekBar.setProgress(0);
        }
    }

    @Override
    protected void setProgress(int duration, int position) {
        if (mSeekBar == null || duration <= 0) return;
        if (mFromUser) return;
        if (System.currentTimeMillis() < mSeekSettleUntil) return;   // seek 落定窗口
        mSeekBar.setMax(1000);
        mSeekBar.setProgress((int) (position * 1000L / duration));
        mSeekBar.setSecondaryProgress(mControlWrapper != null
                ? mControlWrapper.getBufferedPercentage() * 10 : 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 双击 ±10s 任何时候都可用
        mGesture.onTouchEvent(event);
        // 面板显示时，点在面板子控件（暂停键/SeekBar）上的事件已被它们各自消费；
        // 落到这里的触摸不消费（返回 false），让 TikTokView 处理（再次单击=收起面板
        // 由 TikTokView 的 onSingleTapConfirmed→toggleShowState 完成）。
        return super.onTouchEvent(event);
    }

    private void doSeek(long delta) {
        if (mControlWrapper == null) return;
        long duration = mControlWrapper.getDuration();
        if (duration <= 0) return;   // 还没 prepared，seek 无意义
        long target = mControlWrapper.getCurrentPosition() + delta;
        if (target < 0) target = 0;
        if (target > duration) target = duration;
        mControlWrapper.seekTo(target);
        markSeekSettling();
        logSeek(delta >= 0 ? "双击+10s" : "双击-10s", target, duration);
    }

    private void markSeekSettling() {
        mSeekSettleUntil = System.currentTimeMillis() + 600;
    }

    private void logSeek(String how, long target, long duration) {
        com.dywatch.app.util.AppLog.i("seek", how + " → " + (target / 1000) + "s/"
                + (duration / 1000) + "s");
    }
}
