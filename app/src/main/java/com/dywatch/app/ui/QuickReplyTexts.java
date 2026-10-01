package com.dywatch.app.ui;

// 快捷回复文案的**纯逻辑**（软件内问题 ③b，2026-10-01）。
//
// 为什么单独抽一个类：这里全是可 JVM 单测的规则（默认值 / 清洗 / 留空 / 越界），
// 跟 Android 的 SharedPreferences 解耦——Settings 只负责读写，规则全在这里。
//
// 口径（用户拍板）：
//   · 聊天页与评论页**共用一套**文案（原来两页各写死在代码里）
//   · 默认值 = 原来聊天页那套：好 / 在忙 / 稍等 / 😂
//   · **留空即隐藏**该按钮（所以不需要再做"数量"设置）

public final class QuickReplyTexts {

    /** 槽位数（布局 include_quick_reply.xml 里就是 4 个按钮） */
    public static final int SLOTS = 4;

    /** 单个槽位最大长度（手表上再长也显示不下，且会撑破 28dp 细条） */
    public static final int MAX_LEN = 12;

    /** 默认文案（= 老版本聊天页写死的那套，保证升级用户观感不变） */
    private static final String[] DEFAULTS = {"好", "在忙", "稍等", "😂"};

    private QuickReplyTexts() {}

    /** 槽位在 SharedPreferences 里的键：**4 个独立键**（不拼字符串，免转义坑） */
    public static String key(int slot) {
        return "qr_" + slot;
    }

    /** 默认文案的副本（调用方可以改，不会污染常量） */
    public static String[] defaults() {
        return DEFAULTS.clone();
    }

    public static String defaultOf(int slot) {
        if (slot < 1 || slot > SLOTS) return "";
        return DEFAULTS[slot - 1];
    }

    /**
     * 单个槽位清洗：null → ""；**去掉换行**（单行胶囊里换行没法显示，留成空格会出现"稍 等"这种怪缝）；
     * 去掉首尾空白；超长截断。
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\n", "").replace("\r", "").trim();
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN);
        return s;
    }

    /** 清洗并把数组补齐/截断到 SLOTS 个（越界槽位给 ""，即隐藏） */
    public static String[] normalize(String[] raw) {
        String[] out = new String[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            out[i] = (raw == null || i >= raw.length) ? "" : sanitize(raw[i]);
        }
        return out;
    }

    /** 该槽位是不是空的（空 = 这个快捷按钮隐藏） */
    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 给设置页那行显示用的摘要，如 "好 / 在忙 / 稍等 / 😂"；全空时给一句说明 */
    public static String summary(String[] texts) {
        StringBuilder sb = new StringBuilder();
        for (String t : normalize(texts)) {
            if (isEmpty(t)) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(t);
        }
        return sb.length() == 0 ? "（全部留空 = 不显示快捷条）" : sb.toString();
    }

    /** 有几个槽位是有内容的 */
    public static int countNonEmpty(String[] texts) {
        int n = 0;
        for (String t : normalize(texts)) if (!isEmpty(t)) n++;
        return n;
    }
}
