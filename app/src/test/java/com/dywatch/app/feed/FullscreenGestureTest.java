package com.dywatch.app.feed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 全屏手势的纯数学单测（软件内问题 ⑤，2026-10-01）。
 *
 * 为什么单测这两条：缩放/平移的**夹取**是"手感对不对"的根，也是唯一能在 JVM 里证伪的部分
 * （真机上双指捏合的注入 adb 做不到，见台账 §8.4 的验证限制）。
 * 两条规则来自方案 §5.3（照 BiliClient）：
 *   · 缩放 1x~5x
 *   · 平移不许把画面拖出可视区 → 以中心为轴缩放，最大平移量 = size*(scale-1)/2
 */
public class FullscreenGestureTest {

    private static final float EPS = 0.0001f;

    @Test
    public void 缩放夹取到一比五() {
        assertEquals(1f, FullscreenGestureHelper.clampScale(0.3f, 1f, 5f), EPS);
        assertEquals(1f, FullscreenGestureHelper.clampScale(1f, 1f, 5f), EPS);
        assertEquals(2.5f, FullscreenGestureHelper.clampScale(2.5f, 1f, 5f), EPS);
        assertEquals(5f, FullscreenGestureHelper.clampScale(9f, 1f, 5f), EPS);
    }

    @Test
    public void NaN_缩放退回下限而不是崩() {
        assertEquals(1f, FullscreenGestureHelper.clampScale(Float.NaN, 1f, 5f), EPS);
    }

    @Test
    public void 未缩放时平移必须归零() {
        // scale=1 → 没有可平移空间（这正是"未缩放时横拖不该动画面"的数学表达）
        assertEquals(0f, FullscreenGestureHelper.clampTranslation(50f, 372f, 1f), EPS);
        assertEquals(0f, FullscreenGestureHelper.clampTranslation(-50f, 372f, 1f), EPS);
        assertEquals(0f, FullscreenGestureHelper.clampTranslation(50f, 372f, 0.5f), EPS);
    }

    @Test
    public void 放大两倍时最多平移半个宽度() {
        // 372 宽放大 2x → 每边多出 186，平移量必须落在 ±186
        assertEquals(186f, FullscreenGestureHelper.clampTranslation(500f, 372f, 2f), EPS);
        assertEquals(-186f, FullscreenGestureHelper.clampTranslation(-500f, 372f, 2f), EPS);
        assertEquals(100f, FullscreenGestureHelper.clampTranslation(100f, 372f, 2f), EPS);
    }

    @Test
    public void 高度方向同样按自身尺寸夹取() {
        // 430 高放大 5x → 每边多出 430*(5-1)/2 = 860
        assertEquals(860f, FullscreenGestureHelper.clampTranslation(9999f, 430f, 5f), EPS);
    }

    @Test
    public void 边界值本身允许_不抖() {
        assertEquals(186f, FullscreenGestureHelper.clampTranslation(186f, 372f, 2f), EPS);
        assertEquals(-186f, FullscreenGestureHelper.clampTranslation(-186f, 372f, 2f), EPS);
    }
}
