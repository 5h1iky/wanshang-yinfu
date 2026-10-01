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

    /**
     * 字号缩放（2026-09-27 新增，用户要求"能调字体大小"）。
     *
     * 与「界面缩放」的分工（两者可叠加，互不干扰）：
     *   - 界面缩放 = 改逻辑密度 → dp 和 sp **一起**变大（整体变大，控件也变大）
     *   - 字体大小 = 只改 Configuration.fontScale → **只有 sp 文字**变大，控件尺寸不动
     * 所以"字看不清但控件够大"时调这个，"整页都太小"时调界面缩放。
     * 上限 1.6：再大 11sp 的小字会挤成两行，手表上反而更难读。
     */
    public static float fontScale(Context c) {
        float v = sp(c).getFloat("font_scale", 1.0f);
        if (v < 0.7f || v > 1.6f) return 1.0f;   // 脏数据兜底（改坏了不至于起不来）
        return v;
    }

    public static void setFontScale(Context c, float v) {
        sp(c).edit().putFloat("font_scale", v).apply();
    }

    /**
     * 快捷回复条是否显示。
     * ⚠️ 默认 **false**（软件内问题 ③a，2026-10-01 用户拍板"默认改关"）：
     * 它是"省打字"的辅助，不是每个人都想要，默认别占那一行。
     * 注意这里**只改默认值**：老用户 SharedPreferences 里已经有这个键（true/false），
     * 读取时一律以存的值为准 → 已装旧版的人不会因为升级而突然发现快捷条消失。
     */
    public static boolean quickReplyVisible(Context c) {
        return sp(c).getBoolean("quick_reply_visible", false);
    }

    public static void setQuickReplyVisible(Context c, boolean v) {
        sp(c).edit().putBoolean("quick_reply_visible", v).apply();
    }

    /**
     * 一次性迁移（0.8.0 起，软件内问题 ③a）。
     *
     * 起因：把「快捷回复条」的默认值从 true 改成 false 时，**只改默认值是不够的**——
     * 老用户里凡是**从没点过那个开关**的人，存储里压根没有这个键，于是升级后
     * 会跟着新默认值变成"关"，快捷条凭空消失。方案里承诺的"老用户不受影响"就不成立。
     *
     * 做法：老安装（判定 = 免责声明已同意过，说明这个 App 数据早就存在）若从没存过这个键，
     * 就补写 `true`——保持它原来的观感；全新安装（还没同意过声明）不写 → 用新默认 false，
     * 这就是用户要的"默认改关"。用独立键记"迁移已做"，只跑一次，之后用户怎么改都不会被覆盖。
     *
     * 调用点：MainActivity.onCreate 最前面（早于任何设置读写）。
     */
    public static void migrateQuickReplyDefaultOnce(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean("migrate_qr_default_080", false)) return;
        boolean hasOldData = com.dywatch.app.util.Disclaimer.isAccepted(c);
        SharedPreferences.Editor e = p.edit().putBoolean("migrate_qr_default_080", true);
        if (hasOldData && !p.contains("quick_reply_visible")) {
            e.putBoolean("quick_reply_visible", true);   // 老用户：保持原样（别拿走他已在用的东西）
        }
        e.apply();
    }

    /**
     * 快捷回复的 4 个槽位文案（软件内问题 ③b，2026-10-01）。
     * 聊天页与评论页**共用这一套**；某个槽位留空 → 该按钮隐藏（所以无需"数量"设置）。
     * 键是 4 个独立的 `qr_1..qr_4`（不拼字符串、无转义坑）。
     */
    public static String[] quickReplyTexts(Context c) {
        SharedPreferences p = sp(c);
        String[] raw = new String[QuickReplyTexts.SLOTS];
        for (int i = 1; i <= QuickReplyTexts.SLOTS; i++) {
            raw[i - 1] = p.getString(QuickReplyTexts.key(i), QuickReplyTexts.defaultOf(i));
        }
        return QuickReplyTexts.normalize(raw);
    }

    /** 改单个槽位（slot 从 1 开始；传空串 = 隐藏该按钮） */
    public static void setQuickReplyText(Context c, int slot, String text) {
        if (slot < 1 || slot > QuickReplyTexts.SLOTS) return;
        sp(c).edit().putString(QuickReplyTexts.key(slot), QuickReplyTexts.sanitize(text)).apply();
    }

    /** 恢复出厂文案：把 4 个键删掉（删掉即回到默认值，不给老用户留脏数据） */
    public static void resetQuickReplyTexts(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (int i = 1; i <= QuickReplyTexts.SLOTS; i++) e.remove(QuickReplyTexts.key(i));
        e.apply();
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

    /**
     * 全屏手势开关（软件内问题 ⑤，2026-10-01，默认开）。
     *
     * 借鉴 BiliClient 的 `player_scale` / `player_doublemove` 开关思路：双指缩放/平移是"用得上的人
     * 很需要、用不上的人会误触"的功能，给一个总开关比争论默认值省事。
     * 关掉后全屏里仍可单击显隐控件条、按钮照常可用，只是不再缩放/平移。
     */
    public static boolean fullscreenGesture(Context c) {
        return sp(c).getBoolean("fullscreen_gesture", true);
    }

    public static void setFullscreenGesture(Context c, boolean v) {
        sp(c).edit().putBoolean("fullscreen_gesture", v).apply();
    }
}
