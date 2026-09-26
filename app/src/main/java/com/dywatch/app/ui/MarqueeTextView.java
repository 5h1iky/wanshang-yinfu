package com.dywatch.app.ui;

// 手表专用的单行滚动文本。
//
// 为什么需要它：手表屏窄（1.4 寸），视频标题、页名经常一行放不下。原生 TextView 的
// marquee 只在**拿到焦点**时才滚（ellipsize=marquee + selected=true 的常规写法在
// RecyclerView/ListView 里还会因为焦点竞争而时滚时不滚），而刷视频页根本没有可聚焦控件。
//
// 做法：不管焦点，只要文本超出宽度就自己滚（先停一下 → 滚到末尾 → 停一下 → 弹回开头）。
// 放不下才滚，放得下就当普通单行文本，不做无意义的动画。

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

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        // 放得下就不滚：测量一次文本实宽，比可用宽度还窄就安安静静当普通文本
        float textW = getPaint().measureText(getText() == null ? "" : getText().toString());
        int avail = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        mNeedScroll = textW > avail && avail > 0;
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
        float limit = getPaint().measureText(getText().toString())
                - (getWidth() - getPaddingLeft() - getPaddingRight());
        if (mOffset > limit) {
            // 滚到末尾：停顿后弹回开头，形成循环
            mOffset = limit;
            invalidate();
            removeCallbacks(this);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mRunning) return;
                    mOffset = 0f;
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
        if (!mRunning || !mNeedScroll) {
            super.onDraw(canvas);
            return;
        }
        canvas.save();
        canvas.translate(-mOffset, 0f);
        super.onDraw(canvas);
        canvas.restore();
    }
}
