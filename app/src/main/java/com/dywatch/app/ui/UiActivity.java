package com.dywatch.app.ui;

// 全站 Activity 基类：只负责两件跟"手表这块屏"绑死的事——
// ① 按用户设置应用全局缩放；② 需要时保持屏幕常亮（刷视频页不常亮会看着看着黑屏）。
// 页面自身的业务逻辑不放这里，避免基类变成垃圾场。

import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

public class UiActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(UiScale.wrap(this, newBase));
    }

    /** 子类覆写：本页是否需要常亮 */
    protected boolean keepScreenOnWhileVisible() {
        return false;
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
