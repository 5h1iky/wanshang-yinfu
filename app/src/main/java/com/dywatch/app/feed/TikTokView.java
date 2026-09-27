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
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.L;

/**
 * 抖音式 item 控制视图（方案 D，2026-09-27 五修：单击呼出面板）。
 *
 * 交互（用户拍板）：
 *   单击画面 = 呼出/收起控制面板（中央暂停键 + 进度条，4s 无操作自动隐藏）；
 *   面板里的暂停键 = 暂停/继续，进度条拖动 = seek（都在 TikTokController，面板可见时可达）；
 *   双击左右半屏 = ±10s（本类 GestureDetector）；
 *   上下滑切换视频 = ViewPager（本类只处理点击类手势，不碰纵向滑动）。
 *
 * 层级事实（真机实测）：本视图是 item 的最上层触点，TikTokController 在 VideoView 容器里
 * 被本视图盖住——所以"呼出面板"由本类转调 controller；面板显示后其中的暂停键/SeekBar
 * 作为 controller 的子视图在本视图之上（addView 顺序），可以直接点、可以拖。
 */
public class TikTokView extends FrameLayout implements IControlComponent {

    private final ImageView thumb;
    private final ImageView mPlayBtn;

    private ControlWrapper mControlWrapper;
    private final int mScaledTouchSlop;
    private int mStartX, mStartY;
    private final GestureDetector mGesture;
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
        mGesture = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        // 单击确认（~300ms 无第二击）：呼出/收起控制面板
                        //（老逻辑是 togglePlay，用户拍板改为面板式：暂停键在面板里点）
                        if (mControlWrapper != null) mControlWrapper.toggleShowState();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
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

    /**
     * 只做手势分发：单击/双击交 GestureDetector；纵向滑动不消费（ViewPager 拦截）。
     * 面板呼出后，暂停键/SeekBar 是 controller 层的子视图、叠在本视图之上，
     * 它们自己的点击/拖动由 Android 事件路由直接送达（不经过本方法）。
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
