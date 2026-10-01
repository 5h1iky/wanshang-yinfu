package com.dywatch.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dywatch.app.R;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.L;

/**
 * 抖音式 item 控制视图（方案 D；2026-09-27 六修：面板控件并入本视图）。
 *
 * 交互（用户拍板 + 两轮实测反馈定稿）：
 *   单击画面 = 呼出/收起面板（中央暂停键 + 进度条，4s 无操作自动收，暂停时常显）；
 *   点暂停键 = 暂停/继续；拖进度条 = seek——两者都是**本视图的直接子视图**，
 *   触摸天然可达（上一版放 controller 层被本视图盖住：键点不动、条拖不动，用户两连报）；
 *   双击左右半屏 = ±10s；上下滑切视频 = ViewPager。
 *
 * 面板显隐仍走 BaseVideoController 的 show()/hide()（自带 4s fadeOut 计时），
 * 本类实现 IControlComponent.onVisibilityChanged 同步子视图显隐——
 * controller 的 handleVisibilityChanged 会把事件分发给所有注册过的 component。
 */
public class TikTokView extends FrameLayout implements IControlComponent {

    private final ImageView thumb;
    private final ImageView mPlayBtn;

    private ControlWrapper mControlWrapper;
    private final int mScaledTouchSlop;
    private int mStartX, mStartY;
    private final GestureDetector mGesture;
    private static final long SEEK_STEP_MS = 10_000;   // 双击 ±10s

    /** 面板子视图（本布局直接孩子，六修后触摸天然可达） */
    private View mPlayToggle;
    private ImageView mPlayStateIcon;
    private SeekBar mSeekBar;
    /** 进度行（2026-10-01：进度条 + 左侧全屏键，见 layout_tiktok_controller.xml 注释） */
    private View mProgressRow;
    private ImageView mFullscreenBtn;
    /** 全屏入口回调（页面接：进/退全屏都走它） */
    public interface OnFullscreenClick {
        void onFullscreenClick();
    }
    private OnFullscreenClick mFullscreenClick;
    /** 当前是否全屏：决定按钮图标（进=四角向外 / 退=四角向内） */
    private boolean mFullscreen;
    /** 拖动进度条中：吃掉手势（不触发单击收起），不吃进度回调 */
    private boolean mFromUser;
    /** seek 落定窗口：窗口内忽略进度回调（MediaPlayer 位置没跳过去会闪回） */
    private long mSeekSettleUntil;

    public TikTokView(@NonNull Context context) {
        super(context);
    }

    public TikTokView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TikTokView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        LayoutInflater.from(getContext()).inflate(R.layout.layout_tiktok_controller, this, true);
        thumb = findViewById(R.id.iv_thumb);
        mPlayBtn = findViewById(R.id.play_btn);
        mPlayToggle = findViewById(R.id.fl_play_toggle);
        mPlayStateIcon = findViewById(R.id.iv_play_state);
        mSeekBar = findViewById(R.id.sb_progress);
        mProgressRow = findViewById(R.id.ll_progress_row);
        mFullscreenBtn = findViewById(R.id.btn_fullscreen);
        mScaledTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        // 全屏入口（2026-10-01 软件内问题 ⑤）：进度条左侧那个键。
        // 只发通知，进/退全屏由页面（FeedActivity）统一处理——播放器实例在页面手里。
        if (mFullscreenBtn != null) {
            mFullscreenBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    com.dywatch.app.util.AppLog.i("feed", "全屏键被点击（回调"
                            + (mFullscreenClick == null ? "为空！" : "已接") + "）");
                    if (mFullscreenClick != null) mFullscreenClick.onFullscreenClick();
                }
            });
        }

        // 暂停键：就在本视图的孩子上，点击一定可达（六修核心修复）
        if (mPlayToggle != null) {
            mPlayToggle.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mControlWrapper == null) return;
                    mControlWrapper.togglePlay();
                    refreshPlayIcon();
                    // 暂停 → 面板常显；继续播放 → 重新计时 4s 收起
                    if (mControlWrapper.isPlaying()) {
                        mControlWrapper.startFadeOut();
                    } else {
                        mControlWrapper.stopFadeOut();
                    }
                }
            });
        }
        // 进度条：面板呼出后直接拖。⚠️ 拖动期间只记目标位置、松手才 seek 一次——
        // （用户实测：逐次 seekTo 会让内核高频进 BUFFERING，最后一跳落在未缓冲区时
        //   状态收不回来 = 拖完变暂停；单次 seek 无此问题，还省一堆无用流量）
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || mControlWrapper == null) return;
                    long duration = mControlWrapper.getDuration();
                    if (duration <= 0) return;
                    // 只记目标，不发网络请求；UI 位置就是 SeekBar 自己的视觉
                    mPendingSeek = (long) (progress / (float) seekBar.getMax() * duration);
                    if (mLastDragLogSec != mPendingSeek / 1000) {
                        mLastDragLogSec = mPendingSeek / 1000;
                        com.dywatch.app.util.AppLog.i("seek", "拖动至 "
                                + (mPendingSeek / 1000) + "s/" + (duration / 1000) + "s（松手生效）");
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mFromUser = true;
                    mPendingSeek = -1;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mFromUser = false;
                    // 松手：把拖动期间累积的最终位置一次性 seek 过去
                    if (mControlWrapper != null && mPendingSeek >= 0) {
                        long duration = mControlWrapper.getDuration();
                        if (duration > 0) {
                            mControlWrapper.seekTo(mPendingSeek);
                            mSeekSettleUntil = System.currentTimeMillis() + 600;
                            com.dywatch.app.util.AppLog.i("seek", "松手生效 → "
                                    + (mPendingSeek / 1000) + "s/" + (duration / 1000) + "s");
                        }
                        mPendingSeek = -1;
                    }
                    // 播放中才重启 4s 收起计时（暂停时常显）
                    if (mControlWrapper != null && mControlWrapper.isPlaying()) {
                        mControlWrapper.startFadeOut();
                    }
                }
            });
        }
        mGesture = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        // 单击确认：呼出/收起面板（显隐走 controller 的 show/hide 统一计时）
                        if (mControlWrapper != null) mControlWrapper.toggleShowState();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        if (mControlWrapper == null) return true;
                        long duration = mControlWrapper.getDuration();
                        if (duration <= 0) return true;
                        boolean forward = e.getX() >= getWidth() / 2f;
                        long target = mControlWrapper.getCurrentPosition()
                                + (forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
                        if (target < 0) target = 0;
                        if (target > duration) target = duration;
                        mControlWrapper.seekTo(target);
                        com.dywatch.app.util.AppLog.i("seek",
                                (forward ? "双击+10s" : "双击-10s") + " → "
                                        + (target / 1000) + "s/" + (duration / 1000) + "s");
                        return true;
                    }
                });
    }

    /** 拖动 seek 日志节流 */
    private long mLastDragLogSec = -1;
    /** 拖动期间累积的目标位置（松手才真正 seek），-1=无待生效 seek */
    private long mPendingSeek = -1;

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 手表适配：**进度行**收窄到父宽 70% 水平居中（圆屏左右是圆形裁切区，铺满必被切）。
        // ⚠️ 2026-10-01（软件内问题 ⑤）连带修复：进度条挪进横向容器后，SeekBar 的
        //    LayoutParams 变成 LinearLayout.LayoutParams —— 老代码在这里强转成
        //    FrameLayout.LayoutParams 会**直接 ClassCastException 崩掉**。
        //    现在只改"行"自己的参数（行是 FrameLayout 的孩子），SeekBar 宽度交给 weight。
        if (mProgressRow != null && w > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mProgressRow.getLayoutParams();
            int target = (int) (w * 0.7f);
            if (lp.width != target) {
                lp.width = target;
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                mProgressRow.setLayoutParams(lp);
            }
        }
    }

    /** 页面接线：全屏键回调 */
    public void setOnFullscreenClickListener(OnFullscreenClick l) {
        mFullscreenClick = l;
    }

    /** 页面告知当前是否全屏（换图标：进=四角向外 / 退=四角向内） */
    public void setFullscreenState(boolean fullscreen) {
        mFullscreen = fullscreen;
        if (mFullscreenBtn != null) {
            mFullscreenBtn.setImageResource(fullscreen
                    ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
            mFullscreenBtn.setContentDescription(fullscreen ? "退出全屏" : "全屏");
        }
    }

    public boolean isFullscreenState() {
        return mFullscreen;
    }

    /** 暂停键图标随播放态切换（selected=true=暂停 icon，false=播放 icon，selector 定义） */
    private void refreshPlayIcon() {
        if (mPlayStateIcon != null && mControlWrapper != null) {
            mPlayStateIcon.setSelected(mControlWrapper.isPlaying());
        }
    }

    /**
     * 事件路由：点在面板子视图（暂停键/进度条）上 → 事件本来就直接派发给它们
     * （Android 按 z 序从上往下找触点，它们可见时就在最上层），不经过这里；
     * 点在画面其他区域 → 本方法处理：喂手势（单击确认=呼出/收起，双击=±10s）。
     * 拖动进度条期间 UP 不触发 performClick（mFromUser 拦截，防拖完顺手收起面板）。
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGesture.onTouchEvent(event);
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mStartX = (int) event.getX();
                mStartY = (int) event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (mFromUser) {
                    mFromUser = false;
                    return true;
                }
                int endX = (int) event.getX();
                int endY = (int) event.getY();
                if (Math.abs(endX - mStartX) < mScaledTouchSlop
                        && Math.abs(endY - mStartY) < mScaledTouchSlop) {
                    performClick();
                }
                break;
        }
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
        mControlWrapper = controlWrapper;
    }

    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        // controller.show()/hide() 分发到这里：同步面板子视图显隐 + 图标态
        // ⚠️ 显隐的单位是**进度行**（进度条 + 全屏键一起）——只藏 SeekBar 会让全屏键孤零零留着
        if (mPlayToggle != null) mPlayToggle.setVisibility(isVisible ? VISIBLE : GONE);
        if (mProgressRow != null) mProgressRow.setVisibility(isVisible ? VISIBLE : GONE);
        if (isVisible) refreshPlayIcon();
    }

    @Override
    public void onPlayStateChanged(int playState) {
        switch (playState) {
            case VideoView.STATE_IDLE:
                L.e("STATE_IDLE " + hashCode());
                thumb.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PLAYING:
                L.e("STATE_PLAYING " + hashCode());
                thumb.setVisibility(GONE);
                mPlayBtn.setVisibility(GONE);
                break;
            case VideoView.STATE_PAUSED:
                L.e("STATE_PAUSED " + hashCode());
                thumb.setVisibility(GONE);
                mPlayBtn.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PREPARED:
                L.e("STATE_PREPARED " + hashCode());
                break;
            case VideoView.STATE_ERROR:
                L.e("STATE_ERROR " + hashCode());
                Toast.makeText(getContext(), R.string.dkplayer_error_message, Toast.LENGTH_SHORT).show();
                break;
        }
        refreshPlayIcon();
    }

    @Override
    public void onPlayerStateChanged(int playerState) {

    }

    @Override
    public void setProgress(int duration, int position) {
        if (mSeekBar == null || duration <= 0) return;
        if (mFromUser) return;
        if (System.currentTimeMillis() < mSeekSettleUntil) return;
        mSeekBar.setMax(1000);
        mSeekBar.setProgress((int) (position * 1000L / duration));
        mSeekBar.setSecondaryProgress(mControlWrapper != null
                ? mControlWrapper.getBufferedPercentage() * 10 : 0);
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {

    }
}
