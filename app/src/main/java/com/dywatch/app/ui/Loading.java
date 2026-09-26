package com.dywatch.app.ui;

// 加载小圆圈的开关（2026-09-27 用户提出：页面没加载出来时只有顶部一行字，看不出是在转还是死了）。
// 布局是共享的 include_loading（覆盖在内容区正中的 CircularProgressIndicator）。
// 页面只需要 Loading.show(this, true/false)，不必各自 findViewById + setVisibility。

import android.app.Activity;
import android.view.View;

import com.dywatch.app.R;

public final class Loading {

    private Loading() {}

    /**
     * ⚠️ 参数是 Activity 而不是 View：Activity 本身**不是** View（不是"View 树根"），
     * 一开始按 View 写，编译期就报了 "MineActivity 无法转换为 View"。
     * Activity.findViewById 会从 content view 往下找，语义正是我们要的。
     * 布局里没有 include_loading 时静默跳过（不强迫每页都加）。
     */
    public static void show(Activity page, boolean show) {
        if (page == null) return;
        View overlay = page.findViewById(R.id.loading_overlay);
        if (overlay == null) return;
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
