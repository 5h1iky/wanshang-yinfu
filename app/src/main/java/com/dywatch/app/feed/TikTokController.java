package com.dywatch.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dywatch.app.R;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.player.VideoView;

/**
 * 抖音式控制层（方案 D；2026-09-27 六修后职责收窄）：
 *
 * 面板控件（暂停键/进度条）已并入 TikTokView（item 最上层触点，事件天然可达；
 * 放本层时被 TikTokView 盖住：键点不动、条拖不动——用户实测两连报）。
 * 本类现在只负责：
 *   - getLayoutId() 返回空壳布局（无面板控件）；
 *   - 双击 ±10s 的兜底手势（面板隐藏时的主要 seek 路径）；
 *   - onPlayStateChanged 驱动进度刷新开关（startProgress/stopProgress），
 *     setProgress 只打日志取证（UI 更新在 TikTokView.setProgress）。
 */
public class TikTokController extends BaseVideoController {

    private static final long SEEK_STEP_MS = 10_000;   // 双击快进/快退步长

    private GestureDetector mGesture;

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
    }

    @Override
    protected int getLayoutId() {
        return R.layout.layout_tiktok_control_bar;
    }

    @Override
    public boolean showNetWarning() {
        return false;
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        if (mControlWrapper == null) return;
        // 进度刷新开关（TikTokView.setProgress 消费回调更新 UI）
        if (playState == VideoView.STATE_PLAYING || playState == VideoView.STATE_PAUSED) {
            mControlWrapper.startProgress();
        } else if (playState == VideoView.STATE_BUFFERING) {
            mControlWrapper.stopProgress();
        } else if (playState == VideoView.STATE_IDLE
                || playState == VideoView.STATE_ERROR
                || playState == VideoView.STATE_PLAYBACK_COMPLETED) {
            mControlWrapper.stopProgress();
        }
    }

    @Override
    protected void setProgress(int duration, int position) {
        // UI 更新已由 TikTokView.setProgress 承担（组件分发的同一份数据）；
        // 这里不再重复驱动控件，保留空实现避免 super 噪音。
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 不消费：穿透给上层 TikTokView（单击呼出面板/双击 seek 都在那一层有主路径）；
        // 本手势只做双击兜底。
        mGesture.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    private void doSeek(long delta) {
        if (mControlWrapper == null) return;
        long duration = mControlWrapper.getDuration();
        if (duration <= 0) return;
        long target = mControlWrapper.getCurrentPosition() + delta;
        if (target < 0) target = 0;
        if (target > duration) target = duration;
        mControlWrapper.seekTo(target);
        com.dywatch.app.util.AppLog.i("seek", (delta >= 0 ? "双击+10s" : "双击-10s")
                + " → " + (target / 1000) + "s/" + (duration / 1000) + "s");
    }
}
