package com.dywatch.app.ui;

// 列表行的统一画法：标题行（主文本 + 右侧次要元信息）+ 下方摘要。
// 会话列表与评论列表形状相同，之前各写一遍且都是"一个 TextView 塞三段同字号同颜色"，
// 读起来没有层级——收拢到这里，改一处两页同时生效。

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.dywatch.app.R;

public final class Rows {

    private Rows() {}

    /**
     * @param title   主文本（昵称），白色 t_body
     * @param meta    右上角元信息（时间 · 赞数），灰色 t_caption，可为空
     * @param summary 下方摘要（正文/最近消息），次级色 t_body，可为空
     */
    public static LinearLayout card(Context c, String title, String meta, String summary) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_settings_row);
        int pad = dp(c, 12);
        box.setPadding(pad, dp(c, 10), pad, dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 6);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(head);

        head.addView(text(c, title, R.dimen.t_body, R.color.text_primary, true, 1f));
        if (meta != null && !meta.isEmpty()) {
            head.addView(text(c, meta, R.dimen.t_caption, R.color.text_muted, false, 0f));
        }
        if (summary != null && !summary.isEmpty()) {
            TextView s = text(c, summary, R.dimen.t_body, R.color.text_secondary, false, 0f);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 4);
            s.setLayoutParams(sp);
            box.addView(s);
        }
        return box;
    }

    private static TextView text(Context c, String s, int sizeDimen, int colorDimen,
                                 boolean ellipsizeEnd, float weight) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, c.getResources().getDimension(sizeDimen));
        tv.setTextColor(ContextCompat.getColor(c, colorDimen));
        if (weight > 0f) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, weight);
            tv.setLayoutParams(p);
        }
        if (ellipsizeEnd) {
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else {
            tv.setPadding(dp(c, 6), 0, 0, 0);
        }
        return tv;
    }

    public static int dp(Context c, int v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }
}
