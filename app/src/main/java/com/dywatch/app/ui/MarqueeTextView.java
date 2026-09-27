package com.dywatch.app.ui;

// 手表专用的单行滚动文本。
//
// 为什么需要它：手表屏窄（1.4 寸），视频标题、页名经常一行放不下。原生 TextView 的
// marquee 只在**拿到焦点**时才滚（ellipsize=marquee + selected=true 的常规写法在
// RecyclerView/ListView 里还会因为焦点竞争而时滚时不滚），而刷视频页根本没有可聚焦控件。
//
// 做法：不管焦点，只要文本超出宽度就自己滚（先停一下 → 滚到末尾 → 停一下 → 弹回开头）。
// 放不下才滚，放得下就当普通单行文本，不做无意义的动画。
//
// ⚠️ 2026-09-27 修复"后半段不显示"（方案 B，用户报的老 bug："往左移之后后面的文字出不来"）。
//   根因是**位移终点算短了**：旧 limit = 文本宽 - 容器宽，位移到终点时视口里是文本
//   末段贴着容器右缘——末尾文字虽然"刚进视口"，但在 shadow/可复用布局里根本读不到；
//   用户看到的现象就是"滚着滚着后面没了"。
//   修复三件（对齐交接文档方案 B）：
//   1) onDraw 加 canvas.clipRect 视口约束（防画出界 + 防滚动残留）；
//   2) 位移终点改为「末尾文字完全进入视口」：limit = 文本宽 + 容器宽（末端推到视口最左），
//      到末尾停顿后回开头循环——末尾文字在停顿期完整可读；
//   3) 宽度/字号/文本变化时重算 limit 并回开头（旧版只在 onMeasure 算一次，复用即错）。

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

public class MarqueeTextView extends TextView implements Runnable {

    private static final int START_DELAY = 1200;   // 开头停顿
    private static final int END_DELAY = 1000;     // 滚到末尾后的停顿
    private static final int STEP_DELAY = 40;      // 每步间隔
    private static final float STEP_DP = 1.2f;     // 每步滚动距离

    private float mStepPx;
    private float mOffset;
    private boolean mNeedScroll;
    private boolean mRunning;
    /** 位移终点：文本从"开头贴左"滚到"末尾贴左"所需距离（+容器宽的观察余量） */
    private float mLimit;
    /** 供验证用：末尾文字是否已完全进入视口（dump/单测断言用） */
    private boolean mTailShown;

    public MarqueeTextView(Context c) {
        super(c);
        init(c);
    }

    public MarqueeTextView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    public MarqueeTextView(Context c, AttributeSet attrs, int defStyle) {
        super(c, attrs, defStyle);
        init(c);
    }

    private void init(Context c) {
        mStepPx = STEP_DP * c.getResources().getDisplayMetrics().density;
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
    }

    private float textWidth() {
        return getPaint().measureText(getText() == null ? "" : getText().toString());
    }

    private int availWidth() {
        return getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        recalc();
    }

    /** 重算"要不要滚 / 滚多远"。文本、字号、宽度任一变化都要来一遍。 */
    private void recalc() {
        float textW = textWidth();
        int avail = availWidth();
        mNeedScroll = textW > avail && avail > 0;
        if (mNeedScroll) {
            // 终点 = 末尾文字完全进入视口：位移 (文本宽 - 可用宽) 后末段贴容器右缘，
            // 再加一个容器宽的余量，让末尾一路推到视口左侧完整展示后再回开头。
            mLimit = (textW - avail) + avail;
        } else {
            mLimit = 0;
        }
        // 宽度/文本变化后从开头重新滚，且必须重置"末尾已展示"标记
        mOffset = 0f;
        mTailShown = false;
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        // 文本变了：重算（要在下一次 measure 后生效，这里先置脏，onMeasure 会再算）
        mTailShown = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            recalc();
            // 宽度变了：重算 + 回开头 + 重启（旧实现只在 measure 算一次，旋转/缩放后 limit 全错）
            if (mRunning) {
                removeCallbacks(this);
                postDelayed(this, START_DELAY);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // 页面不可见就别滚了（刷视频页切走时省电）
        if (visibility == VISIBLE) start();
        else stop();
    }

    private void start() {
        if (mRunning) return;
        mRunning = true;
        mOffset = 0f;
        postDelayed(this, START_DELAY);
    }

    private void stop() {
        mRunning = false;
        removeCallbacks(this);
        mOffset = 0f;
        mTailShown = false;
        invalidate();
    }

    @Override
    public void run() {
        if (!mRunning) return;
        if (!mNeedScroll) {
            // 文本没超宽：不再自我调度，避免空转耗电
            mRunning = false;
            return;
        }
        mOffset += mStepPx;
        if (mOffset >= mLimit) {
            // 滚到终点：末尾文字已完全进视口 → 停顿展示 → 弹回开头循环
            mOffset = mLimit;
            mTailShown = true;
            // 方案 B 验收取证：末尾文字到位时落一条日志（offset/limit/文本宽/容器宽），
            // 无 adb 手表环境下用户拍照诊断页也能看到这条证据。
            com.dywatch.app.util.AppLog.i("marquee", "末尾文字已进视口 offset="
                    + (int) mOffset + "/" + (int) mLimit
                    + " textW=" + (int) textWidth() + " avail=" + availWidth()
                    + " text=" + getText());
            invalidate();
            removeCallbacks(this);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mRunning) return;
                    mOffset = 0f;
                    mTailShown = false;
                    invalidate();
                    removeCallbacks(this);
                    MarqueeTextView.this.postDelayed(MarqueeTextView.this, START_DELAY);
                }
            }, END_DELAY);
            return;
        }
        invalidate();
        postDelayed(this, STEP_DELAY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 只有真的在滚时才做位移：否则走父类绘制，行为与普通 TextView 完全一致
        if (!mRunning || !mNeedScroll || mOffset == 0f) {
            super.onDraw(canvas);
            return;
        }
        canvas.save();
        // 方案 B 修复点：clipRect 视口约束。onDraw 的 canvas 是**视图本地坐标系**（0..宽），
        // 不是父容器坐标——裁剪区域按本地 bounds 算，防画出界 + 每帧整片重绘防残留。
        canvas.clipRect(0, 0, getWidth(), getHeight());
        canvas.translate(-mOffset, 0f);
        super.onDraw(canvas);
        canvas.restore();
    }

    /** 验证用：末尾文字是否已推进视口（真机 dump 取证/单测断言，方案 B 验收口径） */
    public boolean isTailShown() {
        return mTailShown;
    }

    /** 验证用：当前位移量（px） */
    public float currentOffset() {
        return mOffset;
    }

    /** 验证用：位移终点（px） */
    public float limit() {
        return mLimit;
    }
}
