package com.dywatch.app.ui;

// 全站 Activity 基类：只负责两件跟"手表这块屏"绑死的事——
// ① 按用户设置应用全局缩放；② 需要时保持屏幕常亮（刷视频页不常亮会看着看着黑屏）。
// 页面自身的业务逻辑不放这里，避免基类变成垃圾场。

import android.os.Bundle;
import android.view.WindowManager;

import com.dywatch.app.R;

import androidx.appcompat.app.AppCompatActivity;

public class UiActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(UiScale.wrap(this, newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPageInsets();
    }

    /**
     * 圆屏/异形屏的内缩统一施加在 DecorView 根上：一处生效，不必每页布局各写一套 padding，
     * 也不会漏掉新页面。圆屏底部再多留一点——圆形边缘在下巴处切得最深。
     *
     * ⚠️ **可重复调用**（软件内问题 ②，2026-10-01）：设置页的调节面板每改一次边距就重放一次
     * → 用户当场看到页面内缩/展开（实时预览）。弹窗是**独立窗口**，不受这个 padding 影响，
     * 所以面板自己不会被推走。setPadding 是幂等的，重复调不会累积。
     */
    public final void applyPageInsets() {
        int ph = Settings.paddingHPercent(this);
        int pv = Settings.paddingVPercent(this);
        android.view.View root = getWindow().getDecorView().getRootView();
        if (ph == 0 && pv == 0) {
            root.setPadding(0, 0, 0, 0);
            return;
        }
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int side = m.widthPixels * ph / 100;
        int vert = m.heightPixels * pv / 100;
        int bottom = Settings.isRound(this) ? vert + m.heightPixels * 3 / 100 : vert;
        root.setPadding(side, vert, side, bottom);
    }

    /** 子类覆写：本页是否需要常亮 */
    protected boolean keepScreenOnWhileVisible() {
        return false;
    }

    /** 设置单行页头的标题；页面无 include_header 时静默跳过，不强迫每页都带页头 */
    protected void setPageTitle(String title) {
        android.widget.TextView tv = findViewById(R.id.tv_page_name);
        if (tv != null) tv.setText(title);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (keepScreenOnWhileVisible() && Settings.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }
}
