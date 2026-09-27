package com.dywatch.app.ui;

// 全局缩放：改逻辑密度（dp/sp 一起放大）。
// 用 createConfigurationContext 而不是逐个控件乘系数——后者要改遍所有布局还漏掉三方库视图。
//
// ⚠️ 2026-09-27 方案 C：「字体大小」不再走这里的 Configuration.fontScale——
// 全局 fontScale 会连行高/气泡一起放大，与「界面缩放」感知上一模一样（用户反馈 #4）。
// 字号现在只作用于正文视图（Fonts.scale 逐个施加），chrome 锁死。

import android.content.Context;
import android.content.res.Configuration;

public final class UiScale {

    private UiScale() {}

    /**
     * 在 Activity#attachBaseContext 里调用：super.attachBaseContext(UiScale.wrap(this, newBase))。
     *
     * scale（界面缩放）>1 → 逻辑密度变小 → 同样的 dp 占更多像素，UI 整体变大（手表手指粗时救命）。
     * 字号缩放不在这里（见 Fonts）。
     */
    public static Context wrap(android.app.Activity activity, Context base) {
        float scale = Settings.scale(base);
        boolean needDensity = Math.abs(scale - 1f) >= 0.005f;
        if (!needDensity) return base;

        Configuration c = new Configuration(base.getResources().getConfiguration());
        int baseDpi = base.getResources().getDisplayMetrics().densityDpi;
        if (baseDpi <= 0) return base;
        c.densityDpi = Math.max(80, Math.round(baseDpi / scale));
        try {
            return base.createConfigurationContext(c);
        } catch (Exception e) {
            // 个别 ROM 对覆写密度/字号有怪行为，宁可退回原始配置也不能让页面起不来
            return base;
        }
    }
}
