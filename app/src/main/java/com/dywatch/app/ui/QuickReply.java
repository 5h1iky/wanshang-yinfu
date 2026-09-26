package com.dywatch.app.ui;

// 快捷回复条的接线：文案由调用方给（聊天和评论用不同的常用短语），
// 点击回调统一。布局是共享的 include_quick_reply，避免两页各写一份。

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.dywatch.app.R;

public final class QuickReply {

    public interface Pick {
        void onPick(String text);
    }

    private static final int[] IDS = {R.id.qr1, R.id.qr2, R.id.qr3, R.id.qr4};

    private QuickReply() {}

    /**
     * texts 少于 4 个时，多出来的按钮直接隐藏而不是留个空位。
     *
     * ⚠️ 参数是 Activity（不是 bar 自己）：
     * 历史上调用方传的是 findViewById(R.id.quick_reply_bar)，靠"View.findViewById 命中自身"
     * 这条边角行为才能找到 qr1~qr4；同一个写法在要找**整条 bar**（下面控制显隐）时就自相矛盾了，
     * 所以统一改成传页面。传 Activity 也更符合"这是页面级组件"的语义。
     */
    public static void wire(Activity page, final String[] texts, final Pick cb) {
        if (page == null) return;
        // 设置里把快捷回复条关了 → 整条收掉（用户反馈"太占位置"，给他一个总开关）
        View bar = page.findViewById(R.id.quick_reply_bar);
        if (bar != null) {
            bar.setVisibility(Settings.quickReplyVisible(page) ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < IDS.length; i++) {
            View b = page.findViewById(IDS[i]);
            if (b == null) continue;
            if (texts == null || i >= texts.length || texts[i] == null || texts[i].isEmpty()) {
                b.setVisibility(View.GONE);
                continue;
            }
            ((TextView) b).setText(texts[i]);
            b.setVisibility(View.VISIBLE);
            final String t = texts[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onPick(t);
                }
            });
        }
    }
}
