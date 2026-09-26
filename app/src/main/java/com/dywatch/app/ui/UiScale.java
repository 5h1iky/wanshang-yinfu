package com.dywatch.app.ui;

// 全局缩放：改逻辑密度（dp/sp 一起放大）与字号系数（只放大 sp）。
// 用 createConfigurationContext 而不是逐个控件乘系数——后者要改遍所有布局还漏掉三方库视图。

import android.content.Context;
import android.content.res.Configuration;

public final class UiScale {

    private UiScale() {}

    /**
     * 在 Activity#attachBaseContext 里调用：super.attachBaseContext(UiScale.wrap(this, newBase))。
     *
     * 两个系数各管一件事（2026-09-27 起字号独立成设置项）：
     *   - scale（界面缩放）>1 → 逻辑密度变小 → 同样的 dp 占更多像素，UI 整体变大（手表手指粗时救命）
     *   - fontScale（字体大小）→ 只改 Configuration.fontScale → 只有 sp 文字变大，控件不动
     * 两者都为 1 时原样返回，不额外包一层 Context（少一层包装 = 少一类怪问题）。
     */
    public static Context wrap(android.app.Activity activity, Context base) {
        float scale = Settings.scale(base);
        float font = Settings.fontScale(base);
        boolean needDensity = Math.abs(scale - 1f) >= 0.005f;
        boolean needFont = Math.abs(font - 1f) >= 0.005f;
        if (!needDensity && !needFont) return base;

        Configuration c = new Configuration(base.getResources().getConfiguration());
        if (needDensity) {
            int baseDpi = base.getResources().getDisplayMetrics().densityDpi;
            if (baseDpi <= 0) needDensity = false;
            else c.densityDpi = Math.max(80, Math.round(baseDpi / scale));
        }
        if (needFont) c.fontScale = font;
        if (!needDensity && !needFont) return base;
        try {
            return base.createConfigurationContext(c);
        } catch (Exception e) {
            // 个别 ROM 对覆写密度/字号有怪行为，宁可退回原始配置也不能让页面起不来
            return base;
        }
    }
}
