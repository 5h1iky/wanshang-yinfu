package com.dywatch.app.feed;

// 全屏手势（软件内问题 ⑤，2026-10-01）。
//
// 做法照 BiliClient（huanli233/BiliClient，GPL-3.0）的三个文件：
//   activity/player/ScaleGestureDetector.java、ViewScaleGestureListener.java、PlayerActivity.java
// 但**不抄它的 ScaleGestureDetector**——那是给安卓 4.1 用的 AOSP 拷贝，本项目 minSdk 21
// 系统自带 android.view.ScaleGestureDetector（API 8+），少 500 行。
//
// 关键差异：BiliClient 的 PlayerActivity 是独立全屏播放器，没有 ViewPager 竞争；
// 我们在信息流里靠"**只在全屏启用**"达到同样效果（全屏后容器在 DecorView 上，ViewPager
// 根本收不到事件）——所以信息流手势零回归。
//
// 作用对象：**渲染视图**（视频画面本体），不是整个容器——容器里还有控件层，
// 一起缩放会把按钮和进度条也放大（不是我们要的）。
//
// 手势表（仅全屏）：
//   双指捏合 = 缩放 1x~5x       单指拖动 = 已缩放时平移（带边界夹取）
//   双击     = 已缩放时复位；未缩放时保留 ±10s（由调用方决定）
//   单击     = 显隐底部控件条（由调用方决定）
//   ⚠️ gesture_scaled 标志：缩放过的手势不许再被当成单击/双击（BiliClient 同款防误触）

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class FullscreenGestureHelper {

    /** 缩放上下限（与 BiliClient 的 1x~5x 一致） */
    public static final float MIN_SCALE = 1f;
    public static final float MAX_SCALE = 5f;
    /** 小于这个值视为"没缩放"（浮点误差） */
    private static final float EPS = 0.01f;

    /** 渲染视图的提供者：渲染视图会随换条重建，所以每次手势开始现取，不缓存 */
    public interface TargetProvider {
        @Nullable View get();
    }

    /** 回调（单击/双击未缩放时交给页面：显隐控件条、±10s） */
    public interface Callback {
        void onSingleTap();
        void onDoubleTap(MotionEvent e, boolean zoomed);
    }

    private final Context mContext;
    private final TargetProvider mProvider;
    private final Callback mCallback;
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mTapDetector;
    private final int mTouchSlop;

    private float mScale = 1f;
    private float mTx, mTy;
    /** 本次手势里发生过缩放/平移 → 不许再当单击 */
    private boolean mScaled;
    private float mLastX, mLastY;
    private boolean mDragging;

    public FullscreenGestureHelper(Context ctx, TargetProvider provider, Callback cb) {
        mContext = ctx;
        mProvider = provider;
        mCallback = cb;
        mTouchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        mScaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setScale(mScale * detector.getScaleFactor());
                mScaled = true;
                return true;
            }
        });
        mTapDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (mScaled) return true;          // 缩放过的手势不当单击
                if (mCallback != null) mCallback.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (mScaled) return true;
                if (isZoomed()) {
                    reset();                        // 已缩放 → 双击复位（BiliClient 同款）
                } else if (mCallback != null) {
                    mCallback.onDoubleTap(e, false); // 未缩放 → 交给页面（±10s）
                }
                return true;
            }
        });
    }

    /** 是否已缩放（外部可用来决定双击语义/提示） */
    public boolean isZoomed() {
        return mScale > 1f + EPS;
    }

    public float scale() {
        return mScale;
    }

    /**
     * 事件入口：返回 true 表示本次手势被全屏手势消费（调用方据此不再往下传）。
     * 只有在"已缩放"时单指拖动才会被消费——否则信息流那套单击/双击语义保持原样。
     */
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mScaled = false;
                mDragging = false;
                mLastX = e.getX();
                mLastY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mDragging = false;      // 第二根手指落下 → 交给缩放，别当平移
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() == 1 && isZoomed() && !mScaleDetector.isInProgress()) {
                    float dx = e.getX() - mLastX;
                    float dy = e.getY() - mLastY;
                    if (mDragging || Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop) {
                        mDragging = true;
                        pan(dx, dy);
                        mScaled = true;
                    }
                    mLastX = e.getX();
                    mLastY = e.getY();
                }
                break;
            default:
                break;
        }
        // 顺序要紧：缩放先吃，剩下的再给点击判定（且缩放过就不给）
        mScaleDetector.onTouchEvent(e);
        if (!mScaled) mTapDetector.onTouchEvent(e);
        return mScaled;
    }

    /** 设定缩放（夹取到 1~5），并立刻夹取平移量（缩小后原来的平移可能越界） */
    public void setScale(float s) {
        mScale = clampScale(s, MIN_SCALE, MAX_SCALE);
        if (!isZoomed()) {
            mTx = 0;
            mTy = 0;
        }
        applyTransform();
    }

    public void pan(float dx, float dy) {
        View t = target();
        if (t == null) return;
        mTx += dx;
        mTy += dy;
        mTx = clampTranslation(mTx, t.getWidth(), mScale);
        mTy = clampTranslation(mTy, t.getHeight(), mScale);
        applyTransform();
    }

    /** 复位：缩放回 1、平移回 0（退出全屏、换条、旋转后都调它） */
    public void reset() {
        mScale = 1f;
        mTx = 0;
        mTy = 0;
        mScaled = false;
        mDragging = false;
        applyTransform();
    }

    private void applyTransform() {
        View t = target();
        if (t == null) return;
        t.setScaleX(mScale);
        t.setScaleY(mScale);
        t.setTranslationX(mTx);
        t.setTranslationY(mTy);
    }

    @Nullable
    private View target() {
        return mProvider == null ? null : mProvider.get();
    }

    // ---------- 纯数学部分（可 JVM 单测，见 FullscreenGestureTest）----------

    /** 缩放夹取：1x~5x */
    public static float clampScale(float s, float min, float max) {
        if (Float.isNaN(s)) return min;
        if (s < min) return min;
        return Math.min(s, max);
    }

    /**
     * 平移夹取：视图以**中心**为轴缩放，scale 倍后左右各多出 (scale-1)/2 * size，
     * 所以平移量必须落在 ±该值之内——否则会把画面拖出黑边（BiliClient 的 videoMoveTo 同思路）。
     */
    public static float clampTranslation(float t, float size, float scale) {
        float max = size * (scale - 1f) / 2f;
        if (max <= 0f) return 0f;
        if (t > max) return max;
        if (t < -max) return -max;
        return t;
    }
}
