package com.dywatch.app.ui;

// 正文文字缩放（方案 C，2026-09-27）：
//
// 「字体大小」设置项的语义改为**只放大正文（阅读面）**——聊天气泡正文、评论正文、
// 视频标题、输入框；行高/按钮/页头/间距（chrome）锁死不动。
// 「界面缩放」维持原语义：改逻辑密度，dp+sp 整页一起变大。
//
// 为什么不用 Configuration.fontScale 全局缩放：那就是 v0.5.0 之前的行为——wrap_content
// 的行高、气泡全都跟着字号长，用户感知上和「界面缩放」是一回事（用户反馈 #4 原话）。
// 所以 fontScale 从 UiScale 里摘掉，改为在**正文视图上逐个**施加：
//   目标 px = 基准 px(来自 dimens) × Settings.fontScale
//
// 纪律：正文视图清单收口在本文件 applyTo() 一处，新页面要接就在那里加一行；
// chrome 视图永远不要传进来。

import android.widget.EditText;
import android.widget.TextView;

public final class Fonts {

    private Fonts() {}

    /** 单个正文视图：按 fontScale 放大其字号（基准取自 dimens 的 sp 值） */
    public static void scale(TextView tv, int baseDimenRes) {
        if (tv == null) return;
        float basePx = tv.getResources().getDimension(baseDimenRes);
        float f = Settings.fontScale(tv.getContext());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, basePx * f);
    }

    /** 输入框同款（EditText 是 TextView 子类，语义一致） */
    public static void scale(EditText et, int baseDimenRes) {
        scale((TextView) et, baseDimenRes);
    }
}
