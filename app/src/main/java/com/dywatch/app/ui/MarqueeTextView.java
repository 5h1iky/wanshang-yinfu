package com.dywatch.app.ui;

// 手表专用的单行滚动文本。
//
// 为什么需要它：手表屏窄（1.4 寸），视频标题、页名经常一行放不下。原生 TextView 的
// marquee 只在**拿到焦点**时才滚，而刷视频页根本没有可聚焦控件。
//
// ⚠️ 2026-09-27 三修（方案 B 复盘）：前两版都没修好的真根因——
//   setEllipsize(MARQUEE) 让 TextView 的 TextLayout 按"可用宽度"排版并把溢出文本
//   替换成省略号。之后无论 onDraw 里怎么 translate、limit 怎么算，画出来的永远是
//   **被截断后的那串文本**——后半段字符在 Layout 层就没了，画布位移救不回来。
//   这就是用户两轮报"往左移之后后面的文字出不来"的实锤。
//
//   三修 = 不再依赖 TextView 的 Layout 机制：
//   1) 去掉 ellipsize，滚动帧用 canvas.drawText 直画整条文本（画布上画多少是
//      自己说了算，不经过 Layout 截断）；
//   2) 位移终点 limit = 文本宽 - 可用宽：终点时刻末尾字符右端贴视口右缘，
//      末段完整静止可读 1 秒（前版 limit=textW 是滚过头，末尾反而滑出左缘）；
//   3) 静止帧走 super.onDraw（行为与普通 TextView 一致）。

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextView;

public class MarqueeTextView extends TextView implements Runnable {

    private static final int START_DELAY = 1200;   // 开头停顿
    private static final int END_DELAY = 1000;     // 末尾停顿（末段静止可读）
    private static final int STEP_DELAY = 40;      // 每步间隔
    private static final float STEP_DP = 1.2f;     // 每步滚动距离

    private float mStepPx;
    private float mOffset;
    private boolean mNeedScroll;
    private boolean mRunning;
    /** 位移终点：末尾字符右端贴视口右缘 */
    private float mLimit;
    /** 供验证用：末尾文字是否已停在视口内（日志取证） */
    private boolean mTailShown;
    /** 复用矩形：可见性判断每帧都要用，别每帧 new */
    private final android.graphics.Rect mVisibleRect = new android.graphics.Rect();
    /** 已离开视口（用于"只记一次状态变化"的日志取证） */
    private boolean mOffscreen;

    /**
     * 离开视口时的轮询间隔（2026-10-02，代码审计 L4）。
     *
     * 问题：刷视频页的 VerticalViewPager 设了 offscreenPageLimit=3，最多 7 个页面同时挂在
     * 视图树里，而它们**同属一个窗口** → onWindowVisibilityChanged 全是 VISIBLE，
     * 于是每页标题各跑一条 25Hz 的跑马灯（STEP_DELAY=40ms），7 页 ≈ 175 次
     * post+invalidate/秒，绝大多数在屏幕上根本看不见。手表上这就是白耗电。
     *
     * 解法：滚动前先问一句"我真在视口里吗"（getLocalVisibleRect 会把祖先的滚动/裁剪算进去）。
     * 不在就改成低频轮询（2Hz 而不是 25Hz，省 12 倍），等滑回来自己续上——
     * 不需要页面配合，也不会出现"滑回来标题永远不滚了"的死状态。
     */
    private static final int OFFSCREEN_CHECK_DELAY = 500;

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
        // ⚠️ 不设任何 ellipsize：设了（尤其 MARQUEE）Layout 会截断文本，滚动画不出后半段
        setHorizontallyScrolling(false);
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

    /** 重算"要不要滚 / 滚多远"。文本、字号、宽度任一变化都来一遍。 */
    private void recalc() {
        float textW = textWidth();
        int avail = availWidth();
        mNeedScroll = textW > avail && avail > 0;
        mLimit = mNeedScroll ? (textW - avail) : 0;
        mOffset = 0f;
        mTailShown = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            recalc();
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
            mRunning = false;   // 文本没超宽：不再自我调度，避免空转耗电
            return;
        }
        // 不在视口里就别画了（审计 L4）：ViewPager 的相邻页同属一个可见窗口，
        // 光靠 onWindowVisibilityChanged 挡不住它们空转。
        if (!isVisibleInViewport()) {
            if (!mOffscreen) {
                mOffscreen = true;
                com.dywatch.app.util.AppLog.i("marquee", "离开视口，暂停滚动");
            }
            removeCallbacks(this);
            postDelayed(this, OFFSCREEN_CHECK_DELAY);
            return;
        }
        if (mOffscreen) {
            mOffscreen = false;
            com.dywatch.app.util.AppLog.i("marquee", "回到视口，恢复滚动");
        }
        mOffset += mStepPx;
        if (mOffset >= mLimit) {
            // 滚到终点：末段完整贴视口右缘静止 → 停顿可读 → 弹回开头循环
            mOffset = mLimit;
            mTailShown = true;
            com.dywatch.app.util.AppLog.i("marquee", "末尾文字已停视口 offset="
                    + (int) mOffset + "/" + (int) mLimit
                    + " textW=" + (int) textWidth() + " avail=" + availWidth());
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
        CharSequence text = getText();
        // 静止帧走父类绘制（行为与普通 TextView 一致）
        if (!mRunning || !mNeedScroll || mOffset == 0f || text == null || text.length() == 0) {
            super.onDraw(canvas);
            return;
        }
        // 滚动帧：自己 drawText，绕开 Layout 的 ellipsize 截断（三修核心）
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        float x = getPaddingLeft() - mOffset;
        float baseline = getBaseline();
        canvas.drawText(text, 0, text.length(), x, baseline, getPaint());
        canvas.restore();
    }

    /** 验证用：末尾文字是否已停在视口内（日志取证） */
    public boolean isTailShown() {
        return mTailShown;
    }

    /**
     * 这个 View 当前是否真的落在视口里（把祖先的滚动与裁剪都算进去）。
     * 拿不到 attach 信息 / 尺寸为 0 时按"不显示"处理——那种情况下滚动本来也没意义。
     */
    private boolean isVisibleInViewport() {
        if (getWidth() <= 0 || getHeight() <= 0) return false;
        if (!isShown()) return false;
        return getLocalVisibleRect(mVisibleRect);
    }
}
