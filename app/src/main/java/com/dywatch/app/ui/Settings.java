package com.dywatch.app.ui;

// 全局 UI 设置（SharedPreferences 落盘）。放在这里是为了让"缩放/屏形/画质"这些
// 手表适配开关只有一处定义，别散到各 Activity 里各读各的。

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

public final class Settings {

    public static final String PREF = "ui_settings";

    /** 屏形：跟随系统判定 / 强制圆屏 / 强制方屏。圆屏内容要横向内缩，否则四角被裁。 */
    public static final int SHAPE_AUTO = 0, SHAPE_ROUND = 1, SHAPE_SQUARE = 2;
    /** 画质档：决定 DouyinApi 选码率时的偏好（省流量优先低分辨率，清晰档放开到 1080p）。 */
    public static final int Q_SAVE = 0, Q_BALANCED = 1, Q_CLEAR = 2;

    private Settings() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 界面缩放系数：>1 表示控件更大。默认 1.0（按设备真实密度渲染）。 */
    public static float scale(Context c) {
        return sp(c).getFloat("ui_scale", 1.0f);
    }

    public static void setScale(Context c, float v) {
        sp(c).edit().putFloat("ui_scale", v).apply();
    }

    public static int shapeMode(Context c) {
        return sp(c).getInt("shape_mode", SHAPE_AUTO);
    }

    public static void setShapeMode(Context c, int v) {
        sp(c).edit().putInt("shape_mode", v).apply();
    }

    /** 当前到底算不算圆屏：手动覆盖优先，否则问系统。 */
    public static boolean isRound(Context c) {
        int mode = shapeMode(c);
        if (mode == SHAPE_ROUND) return true;
        if (mode == SHAPE_SQUARE) return false;
        int roundBits = c.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_ROUND_MASK;
        return roundBits == Configuration.SCREENLAYOUT_ROUND_YES;
    }

    /** 默认走省流量档：手表屏 1.4 寸看不出 720/1080 的差别，但解码功耗和流量是实打实的。 */
    public static int qualityMode(Context c) {
        return sp(c).getInt("quality_mode", Q_SAVE);
    }

    public static void setQualityMode(Context c, int v) {
        sp(c).edit().putInt("quality_mode", v).apply();
    }

    /** 评论滚到底自动续拉（关掉就退回"点按钮加载"） */
    public static boolean commentAutoLoad(Context c) {
        return sp(c).getBoolean("comment_autoload", true);
    }

    public static void setCommentAutoLoad(Context c, boolean v) {
        sp(c).edit().putBoolean("comment_autoload", v).apply();
    }

    /** 刷视频时保持屏幕常亮（手表抬腕亮屏有限，不常亮会看着看着黑屏） */
    public static boolean keepScreenOn(Context c) {
        return sp(c).getBoolean("keep_screen_on", true);
    }

    public static void setKeepScreenOn(Context c, boolean v) {
        sp(c).edit().putBoolean("keep_screen_on", v).apply();
    }
}
