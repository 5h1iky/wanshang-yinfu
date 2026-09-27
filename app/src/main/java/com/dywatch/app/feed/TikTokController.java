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
 * 抖音式控制层（方案 D 阶段 1，2026-09-27）：
 * ① 底部细长进度条：SeekBar 贴底 24dp 触控区，不挡画面；进度色走品牌点缀色。
 *    进度刷新由 BaseVideoController 的 mShowProgress 驱动（STATE_PLAYING 时 startProgress()），
 *    回调进 {@link #setProgress(int, int)}，更新 SeekBar（拖动中不吃回调防跳）。
 * ② 双击右半屏 +10s / 左半屏 -10s：GestureDetector.onDoubleTap。
 *    单击必须穿透给 item 层的 TikTokView（暂停/继续是它的 onClick）——
 *    所以本控制器不消费 DOWN（返回 false），只在 onDoubleTap 里做 seek；
 *    双击的第二击起 GestureDetector 会自动消费后续事件，不会触发 TikTokView 的单击。
 *    但垂直滑动仍归 ViewPager：我们不碰 onScroll/onFling，DOWN 返回 false 让父级正常接管。
 *
 * 为什么不用 IControlComponent 拆组件：只有一个 SeekBar + 一个手势，组件化是过度设计；
 * BaseVideoController 的 getLayoutId() 机制本来就是给"整块控制层"用的。
 */
public class TikTokController extends BaseVideoController {

    private static final long SEEK_STEP_MS = 10_000;   // 双击快进/快退步长

    private SeekBar mSeekBar;
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
                        // 布局是全屏的：x < 半宽 = 左半屏（后退），否则右半屏（前进）
                        boolean forward = e.getX() >= getWidth() / 2f;
                        doSeek(forward ? SEEK_STEP_MS : -SEEK_STEP_MS);
                        return true;
                    }
                });
        // ⚠️ 实例初始化块在 super 构造器（含 initView()→inflate）之后执行，
        // 所以这里 findViewById 一定拿得到 getLayoutId() inflate 进来的子视图。
        // （此前挂在 onFinishInflate 里是无效的：本类由 Java new 出来，XML inflate 的
        //   只是 getLayoutId() 的内容，TikTokController 自身的 onFinishInflate 永远不回调
        //   ——mSeekBar 一直 null，进度条监听从没挂上。真机取证：日志里该行从未出现。）
        mSeekBar = findViewById(R.id.sb_progress);
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
                }
            });
        } else {
            com.dywatch.app.util.AppLog.i("seek", "seekBar 未找到（布局 inflate 异常）");
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.layout_tiktok_control_bar;
    }

    @Override
    public boolean showNetWarning() {
        //不显示移动网络播放警告
        return false;
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        // 一次性取证：控制层与进度条的真实尺寸（uiautomator 的 bounds 对挂载中的层会报 0，
        // 结论只认内层 getHeight/getWidth —— 项目验证纪律）
        if (mSeekBar != null && !mSizeLogged && playState == VideoView.STATE_PREPARED) {
            mSizeLogged = true;
            post(new Runnable() {
                @Override
                public void run() {
                    com.dywatch.app.util.AppLog.i("seek", "控制层尺寸 controller="
                            + getWidth() + "x" + getHeight() + " seekBar="
                            + mSeekBar.getWidth() + "x" + mSeekBar.getHeight()
                            + " visible=" + (mSeekBar.getVisibility() == VISIBLE)
                            + " attached=" + mSeekBar.isAttachedToWindow());
                }
            });
        }
        // 手动开/关进度刷新（VodControlView 同款姿势）：只有 PLAYING 有稳定位置可刷
        if (mControlWrapper == null) return;
        if (playState == VideoView.STATE_PLAYING) {
            mControlWrapper.startProgress();
        } else if (playState == VideoView.STATE_BUFFERING) {
            mControlWrapper.stopProgress();
        } else if (playState == VideoView.STATE_PAUSED) {
            mControlWrapper.startProgress();   // 暂停时也要刷一次把当前位置画上
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
        // DOWN 不消费：单击穿透给 TikTokView（暂停/继续），垂直滑动归 ViewPager。
        // 双击在 onDoubleTap 里处理，第二击不再产生 TikTokView 的 onClick。
        mGesture.onTouchEvent(event);
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
