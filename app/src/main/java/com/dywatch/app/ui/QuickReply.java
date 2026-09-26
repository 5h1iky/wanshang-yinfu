package com.dywatch.app.ui;

// 快捷回复条的接线：文案由调用方给（聊天和评论用不同的常用短语），
// 点击回调统一。布局是共享的 include_quick_reply，避免两页各写一份。

import android.view.View;
import android.widget.TextView;

import com.dywatch.app.R;

public final class QuickReply {

    public interface Pick {
        void onPick(String text);
    }

    private static final int[] IDS = {R.id.qr1, R.id.qr2, R.id.qr3, R.id.qr4};

    private QuickReply() {}

    /** texts 少于 4 个时，多出来的按钮直接隐藏而不是留个空位 */
    public static void wire(View page, final String[] texts, final Pick cb) {
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
