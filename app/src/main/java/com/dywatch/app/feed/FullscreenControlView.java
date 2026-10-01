package com.dywatch.app.feed;

// 全屏控制层（软件内问题 ⑤，2026-10-01）。
//
// 为什么另做一层：信息流那套面板（TikTokView）是 **item 的视图**，全屏时播放器容器被搬到
// DecorView，item 还留在 ViewPager 里 → 面板被全屏画面盖住，看不见也点不到。
// 本视图作为"非游离"控制组件挂进 controller（`addControlComponent(v, false)`），
// 于是它就在 mPlayerContainer 内部，跟着一起进全屏；非全屏时整体 GONE。
//
// 职责：
//   · 全屏手势（双指缩放/平移/双击复位——见 FullscreenGestureHelper；单击显隐底部条）
//   · 底部控件条：退出全屏 / 进度条 / 旋转
//   · 进度回调：controller 分发的 setProgress 驱动这里的进度条（与信息流各自独立）

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dywatch.app.R;
import com.dywatch.app.ui.Settings;

import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.player.VideoView;

public class FullscreenControlView extends FrameLayout implements IControlComponent {

    /** 页面要接的两个动作（退出全屏、旋转）；进度/播放态由组件回调自带 */
    public interface Listener {
        void onExitFullscreen();
        void onRotate();
    }

    private ControlWrapper mControlWrapper;
    private Listener mListener;
    private FullscreenGestureHelper mGesture;

    private View mBar;
    private SeekBar mSeek;
    private TextView mExit;
    private TextView mRotate;
    private TextView mReset;

    /** 拖动进度条期间不吃进度回调（否则手指还在拖、UI 被回放位置拽回去） */
    private boolean mFromUser;
    private long mPendingSeek = -1;
    private long mSeekSettleUntil;
    private long mLastDragLogSec = -1;

    public FullscreenControlView(@NonNull Context context) {
        this(context, null);
    }

    public FullscreenControlView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        View content = inflate(context, R.layout.layout_fullscreen_controls, this);
        mBar = content.findViewById(R.id.fs_bar);
        mSeek = content.findViewById(R.id.fs_seek);
        mExit = content.findViewById(R.id.fs_exit);
        mRotate = content.findViewById(R.id.fs_rotate);
        mReset = content.findViewById(R.id.fs_reset);

        mExit.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (mListener != null) mListener.onExitFullscreen();
            }
        });
        mRotate.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (mListener != null) mListener.onRotate();
            }
        });
        // 复位（用户真机验收时提的需求）：双击也能复位，但那个手势没有任何可见提示，
        // 所以给一个看得见的按钮；没放大时点它给一句提示，别让人以为"点了没反应"。
        mReset.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                boolean zoomed = isZoomed();
                resetZoom();
                com.dywatch.app.util.AppLog.i("feed", "点复位（当时" + (zoomed ? "已放大" : "未放大") + "）");
                android.widget.Toast.makeText(getContext(),
                        zoomed ? "画面已复位" : "当前没有放大",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // 进度条：与信息流同一口径——拖动只记目标，松手才 seek 一次
        // （逐次 seekTo 会让内核高频 BUFFERING，最后一跳落在未缓冲区时状态收不回来）
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || mControlWrapper == null) return;
                long duration = mControlWrapper.getDuration();
                if (duration <= 0) return;
                mPendingSeek = (long) (progress / (float) seekBar.getMax() * duration);
                if (mLastDragLogSec != mPendingSeek / 1000) {
                    mLastDragLogSec = mPendingSeek / 1000;
                    com.dywatch.app.util.AppLog.i("seek", "全屏拖动至 "
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
                if (mControlWrapper != null && mPendingSeek >= 0) {
                    long duration = mControlWrapper.getDuration();
                    if (duration > 0) {
                        mControlWrapper.seekTo(mPendingSeek);
                        mSeekSettleUntil = System.currentTimeMillis() + 600;
                        com.dywatch.app.util.AppLog.i("seek", "全屏松手生效 → "
                                + (mPendingSeek / 1000) + "s/" + (duration / 1000) + "s");
                    }
                    mPendingSeek = -1;
                }
            }
        });

        // 手势层：只有全屏时本视图可见，所以这里不必再判状态
        View layer = content.findViewById(R.id.fs_gesture_layer);
        mGesture = new FullscreenGestureHelper(context, new FullscreenGestureHelper.TargetProvider() {
            @Override public View get() {
                return mTargetProvider == null ? null : mTargetProvider.get();
            }
        }, new FullscreenGestureHelper.Callback() {
            @Override public void onSingleTap() {
                toggleBar();   // 单击显隐控件条（与信息流"单击呼出面板"同语义）
            }

            @Override
            public void onDoubleTap(MotionEvent e, boolean zoomed) {
                // 未缩放时的双击保留 ±10s（与信息流一致）；已缩放的双击在 helper 内部复位
                if (mControlWrapper == null) return;
                long duration = mControlWrapper.getDuration();
                if (duration <= 0) return;
                boolean forward = e.getX() >= getWidth() / 2f;
                long target = mControlWrapper.getCurrentPosition() + (forward ? 10_000 : -10_000);
                if (target < 0) target = 0;
                if (target > duration) target = duration;
                mControlWrapper.seekTo(target);
                com.dywatch.app.util.AppLog.i("seek", "全屏双击" + (forward ? "+10s" : "-10s")
                        + " → " + (target / 1000) + "s/" + (duration / 1000) + "s");
            }
        });
        layer.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                // 设置里关了全屏手势 → 只保留单击显隐条（双击 ±10s 仍在信息流层可用）
                if (!Settings.fullscreenGesture(getContext())) {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) toggleBar();
                    return true;
                }
                boolean consumed = mGesture.onTouchEvent(event);
                return consumed || event.getActionMasked() == MotionEvent.ACTION_DOWN;
            }
        });
    }

    /** 渲染视图提供者（换条后渲染视图会重建，所以每次手势现取） */
    public interface RenderTargetProvider {
        View get();
    }

    private RenderTargetProvider mTargetProvider;

    public void setRenderTargetProvider(RenderTargetProvider p) {
        mTargetProvider = p;
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /** 复位缩放（退出全屏 / 换条 / 旋转后调） */
    public void resetZoom() {
        if (mGesture != null) mGesture.reset();
    }

    public boolean isZoomed() {
        return mGesture != null && mGesture.isZoomed();
    }

    public float currentScale() {
        return mGesture == null ? 1f : mGesture.scale();
    }

    /** 底部条的显隐（全屏里单击切换） */
    public void toggleBar() {
        if (mBar == null) return;
        boolean show = mBar.getVisibility() != VISIBLE;
        mBar.setVisibility(show ? VISIBLE : GONE);
        com.dywatch.app.util.AppLog.i("feed", "全屏控件条：" + (show ? "显示" : "隐藏"));
    }

    // ---------- IControlComponent ----------

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
        // controller 的 4s 自动收起针对信息流面板；本层显隐由玩家单击控制，这里不跟随
    }

    @Override
    public void onPlayStateChanged(int playState) {
        // 缓冲/暂停时进度条不必特殊处理：位置由 setProgress 驱动
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
        // 只在全屏时出现（非全屏 GONE → 信息流那套完全不受影响，包括触摸）
        boolean full = playerState == VideoView.PLAYER_FULL_SCREEN;
        setVisibility(full ? VISIBLE : GONE);
        if (full && mBar != null) mBar.setVisibility(VISIBLE);
        if (!full) resetZoom();
        com.dywatch.app.util.AppLog.i("feed", "全屏控制层：" + (full ? "显示" : "隐藏"));
    }

    @Override
    public void setProgress(int duration, int position) {
        if (mSeek == null || duration <= 0) return;
        if (mFromUser) return;
        if (System.currentTimeMillis() < mSeekSettleUntil) return;
        mSeek.setMax(1000);
        mSeek.setProgress((int) (position * 1000L / duration));
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }
}
