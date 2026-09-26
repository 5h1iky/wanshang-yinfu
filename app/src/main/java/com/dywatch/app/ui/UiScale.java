package com.dywatch.app.ui;

// 全局缩放：改逻辑密度，让所有 dp/sp 按同一系数放大缩小。
// 用 createConfigurationContext 而不是逐个控件乘系数——后者要改遍所有布局还漏掉三方库视图。

import android.content.Context;
import android.content.res.Configuration;

public final class UiScale {

    private UiScale() {}

    /**
     * 在 Activity#attachBaseContext 里调用：super.attachBaseContext(UiScale.wrap(this, newBase))。
     * 缩放 >1 → 逻辑密度变小 → 同样的 dp 占更多像素，UI 整体变大（手表手指粗时救命）。
     */
    public static Context wrap(android.app.Activity activity, Context base) {
        float scale = Settings.scale(base);
        if (Math.abs(scale - 1f) < 0.005f) return base;
        int baseDpi = base.getResources().getDisplayMetrics().densityDpi;
        if (baseDpi <= 0) return base;
        int target = Math.max(80, Math.round(baseDpi / scale));
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.densityDpi = target;
        try {
            return base.createConfigurationContext(c);
        } catch (Exception e) {
            // 个别 ROM 对覆写密度有怪行为，宁可退回原始密度也不能让页面起不来
            return base;
        }
    }
}
