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

    /**
     * 页面边距百分比（圆屏适配的真正手段，参考 BiliClient）。
     * 比"猜 round/square 然后套一套固定值"通用——每块表的圆形裁切深度不一样，
     * 让用户自己调 0~30% 才收得住；开关只负责填一组合理默认值。
     */
    public static int paddingHPercent(Context c) {
        int def = isRound(c) ? 5 : 0;
        return clampPercent(sp(c).getInt("padding_h_percent", def));
    }

    public static int paddingVPercent(Context c) {
        int def = isRound(c) ? 3 : 0;
        return clampPercent(sp(c).getInt("padding_v_percent", def));
    }

    public static void setPaddingPercent(Context c, int h, int v) {
        sp(c).edit()
                .putInt("padding_h_percent", clampPercent(h))
                .putInt("padding_v_percent", clampPercent(v))
                .apply();
    }

    /** 越界不保存（照抄 BiliClient 的保护思路，但给个明确上限而不是静默丢弃） */
    private static int clampPercent(int p) {
        if (p < 0) return 0;
        return Math.min(p, 30);
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

    /**
     * 表冠滚动：默认关。有的手表表冠本来就能靠焦点导航滚动，我们再抢一次事件会双重滚，
     * 所以做成开关 + 灵敏度，而不是无条件接管（方案 §10.5：表冠是加分项，不依赖）。
     */
    public static boolean rotaryEnabled(Context c) {
        return sp(c).getBoolean("rotary_enabled", false);
    }

    public static void setRotaryEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("rotary_enabled", v).apply();
    }

    /** 表冠灵敏度：0=不接管，越大一格滚得越多 */
    public static float rotarySensitivity(Context c) {
        return rotaryEnabled(c) ? sp(c).getFloat("rotary_sens", 1.0f) : 0f;
    }

    public static void setRotarySensitivity(Context c, float v) {
        sp(c).edit().putFloat("rotary_sens", v).apply();
    }
}
