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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dywatch.app.R;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.L;

public class TikTokView extends FrameLayout implements IControlComponent {

    private final ImageView thumb;
    private final ImageView mPlayBtn;

    private ControlWrapper mControlWrapper;
    private final int mScaledTouchSlop;
    private int mStartX, mStartY;
    /** 双击 seek（方案 D）：挂在 TikTokView 上——它是 item 的最上层触点，
     *  TikTokController 在 VideoView 容器里被本视图盖住，双击事件到不了它。 */
    private final GestureDetector mDoubleTapGesture;
    /** 双击步长与 TikTokController 保持一致 */
    private static final long SEEK_STEP_MS = 10_000;

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
        mScaledTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        mDoubleTapGesture = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        // 单击确认（~300ms 无第二击）：真正的暂停/继续
                        if (mToggleOnSingleTap && mControlWrapper != null) {
                            mControlWrapper.togglePlay();
                        }
                        mToggleOnSingleTap = false;
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        // 双击来了：取消挂起的单击 toggle
                        mToggleOnSingleTap = false;
                        if (mControlWrapper == null) return true;
                        long duration = mControlWrapper.getDuration();
                        if (duration <= 0) return true;   // 还没 prepared
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

    /** 单击确认锁：双击的第一击会先到 ACTION_UP，此时不能 togglePlay（否则双击必然闪一下暂停）。
     *  onSingleTapConfirmed 在双击窗口（~300ms）过后才回调，那时才真正 toggle。 */
    private boolean mToggleOnSingleTap;
    /** 横向拖动 seek（方案 D）：TikTokView 是最上层触点，横向拖动在这里检测——
     *  落在底部进度条区域（触控高 36dp）且横向位移明显、纵向位移小 → 按比例 seek。
     *  纵向滑动不拦（ViewPager 上下滑），双击/单击走 GestureDetector。 */
    private boolean mDraggingSeek;
    private float mDragStartX;
    private long mDragStartPos;
    private long mDragDuration;
    private static final float SEEK_ZONE_DP = 48f;     // 触控热区：SeekBar 本体 24dp + 手指容差
    private static final float SEEK_DRAG_RATIO = 2.5f;   // 拖满一屏宽 = 2.5 倍时长

    /**
     * 解决点击和VerticalViewPager滑动冲突问题。
     * 单击 = onSingleTapConfirmed 里 togglePlay（带 ~300ms 双击窗口确认，抖音手机版同款取舍：
     * 不确认的话双击的第一击会先暂停一下，视觉上闪一帧）。
     * 双击 = onDoubleTap 里 seek ±10s。
     * 底部进度条区横向拖动 = 按比例 seek（在 TikTokView 里检测——SeekBar 被本视图盖住，
     * 触摸到不了 controller 层，这是 dkplayer 分层决定的；在此检测是唯一可达路径）。
     * 纵向滑动不归这里：事件照旧被 ViewPager 拦截（onInterceptTouchEvent），不影响上下滑。
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mDoubleTapGesture.onTouchEvent(event);
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mStartX = (int) event.getX();
                mStartY = (int) event.getY();
                mToggleOnSingleTap = false;
                // 底部 seek 热区：手指落在这里且后续横向移动 → 进入拖动 seek 模式。
                // ⚠️ getY() 是视图本地坐标（屏坐标-状态栏等偏移），与 getHeight() 同系才对；
                //    之前 36dp 热区实际只盖住本地 2159 以下，手表手指落点差几像素就 miss。
                float zonePx = SEEK_ZONE_DP * getResources().getDisplayMetrics().density;
                mDraggingSeek = mControlWrapper != null
                        && event.getY() >= getHeight() - zonePx
                        && mControlWrapper.getDuration() > 0;
                if (mDraggingSeek) {
                    mDragStartX = event.getX();
                    mDragStartPos = mControlWrapper.getCurrentPosition();
                    mDragDuration = mControlWrapper.getDuration();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDraggingSeek) {
                    float dx = event.getX() - mDragStartX;
                    long target = mDragStartPos
                            + (long) (dx / getWidth() * mDragDuration * SEEK_DRAG_RATIO);
                    if (target < 0) target = 0;
                    if (target > mDragDuration) target = mDragDuration;
                    mControlWrapper.seekTo(target);
                    if (Math.abs(dx) > 40 && (mLastDragLogTarget / 1000 != target / 1000)) {
                        mLastDragLogTarget = target;
                        com.dywatch.app.util.AppLog.i("seek", "拖动 → "
                                + (target / 1000) + "s/" + (mDragDuration / 1000) + "s");
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mDraggingSeek) {
                    mDraggingSeek = false;
                    return true;   // 拖动结束，这一下不是点击
                }
                int endX = (int) event.getX();
                int endY = (int) event.getY();
                if (Math.abs(endX - mStartX) < mScaledTouchSlop
                        && Math.abs(endY - mStartY) < mScaledTouchSlop) {
                    performClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mDraggingSeek = false;
                break;
        }
        return false;
    }

    /** 拖动 seek 日志节流：同一秒内不重复记 */
    private long mLastDragLogTarget;

    @Override
    public boolean performClick() {
        // performClick 由 ACTION_UP 的位移判断触发；真正 toggle 推迟到单击确认
        mToggleOnSingleTap = true;
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
    }

    @Override
    public void onPlayerStateChanged(int playerState) {

    }

    @Override
    public void setProgress(int duration, int position) {

    }

    @Override
    public void onLockStateChanged(boolean isLocked) {

    }
}
