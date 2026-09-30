package com.dywatch.app.ui;

// 自绘弹窗组件（2026-09-30）。
//
// 为什么不用 AlertDialog：用户反馈「怎么又用系统自带的那个了」——系统弹窗的灰底、
// 系统按钮字体、系统圆角，跟 App 的深色卡片 + 胶囊按钮完全不是一套语言。
// 这里用 android.app.Dialog + 自绘布局，视觉与「设置行」一致（bg_dialog / DyDialogButton）。
//
// 行为约定：
//   - 宽度 = 屏宽 92%（手表圆屏也不贴边），高度自适应，正文超高则限高滚动（手表屏矮必须有）
//   - 正文是"阅读面" → 交给 Fonts.scale 跟随「字体大小」设置；标题/按钮属 chrome，不缩放
//   - 任何关闭方式（按钮 / 返回键 / 点外部）都会回调 onDismiss，供调用方记账（如公告记已读）
//   - 用 Activity 做 Context（弹窗要挂在 Activity 上，且需要主题）

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import com.dywatch.app.R;

public final class DyDialog {

    /** 弹窗配置 */
    public static final class Opt {
        public String title = "";
        public CharSequence body = "";
        /** 标题配色（默认正文亮白）；公告的 warn/danger 用它区分级别 */
        public int titleColorRes = R.color.text_primary;
        public String positiveText = "知道了";
        public Runnable onPositive;
        /** 次按钮（留空则不显示），如公告的「查看详情」 */
        public String negativeText = "";
        public Runnable onNegative;
        public boolean cancelable = true;
        /** 任何方式关闭都会执行（公告在这里记 lastReadId） */
        public Runnable onDismiss;

        public Opt title(String t) { this.title = t; return this; }
        public Opt body(CharSequence b) { this.body = b; return this; }
        public Opt titleColor(int colorRes) { this.titleColorRes = colorRes; return this; }
        public Opt positive(String text, Runnable r) { this.positiveText = text; this.onPositive = r; return this; }
        public Opt negative(String text, Runnable r) { this.negativeText = text; this.onNegative = r; return this; }
        public Opt cancelable(boolean c) { this.cancelable = c; return this; }
        public Opt onDismiss(Runnable r) { this.onDismiss = r; return this; }
    }

    private DyDialog() {}

    /** 显示；返回 Dialog 以便调用方在需要时手动关闭（可能返回 null：Activity 已结束/无内容） */
    public static Dialog show(final Activity act, final Opt o) {
        if (act == null || act.isFinishing() || o == null) return null;

        final View content = act.getLayoutInflater().inflate(R.layout.dialog_dy, null);
        final TextView title = content.findViewById(R.id.dlg_title);
        final TextView body = content.findViewById(R.id.dlg_body);
        final android.widget.ScrollView scroll = content.findViewById(R.id.dlg_scroll);
        final TextView btnPos = content.findViewById(R.id.dlg_btn_pos);
        final TextView btnNeg = content.findViewById(R.id.dlg_btn_neg);

        title.setText(o.title);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(act, o.titleColorRes));
        if (o.title == null || o.title.isEmpty()) title.setVisibility(View.GONE);

        body.setText(o.body == null ? "" : o.body);
        // 正文跟随「字体大小」设置（chrome 不缩放，见 Fonts 的纪律注释）
        Fonts.scale(body, R.dimen.t_body);

        btnPos.setText(o.positiveText);
        btnNeg.setText(o.negativeText);
        btnNeg.setVisibility(o.negativeText == null || o.negativeText.isEmpty() ? View.GONE : View.VISIBLE);

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(content);
        dlg.setCanceledOnTouchOutside(o.cancelable);
        dlg.setCancelable(o.cancelable);

        Window w = dlg.getWindow();
        if (w != null) {
            // 去掉系统弹窗那层灰底/白边，只留我们自己的卡片
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(Window.FEATURE_NO_TITLE);
        }

        btnPos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (o.onPositive != null) o.onPositive.run();
                dlg.dismiss();
            }
        });
        btnNeg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (o.onNegative != null) o.onNegative.run();
                if (o.cancelable) dlg.dismiss();
            }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (o.onDismiss != null) o.onDismiss.run();
            }
        });

        dlg.show();

        // 宽度：屏宽 92%，水平居中（手表圆屏留边）
        if (w != null) {
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            int dialogW = (int) (screenW * 0.92f);
            w.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT);

            // 正文限高：超过屏高 42% 就滚动，别把按钮顶出屏幕（手表屏矮，长公告必须有这条路）
            int maxBody = (int) (screenH * 0.42f);
            int padding = content.getPaddingLeft() + content.getPaddingRight();
            int availW = Math.max(1, dialogW - padding);
            body.measure(View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (body.getMeasuredHeight() > maxBody) {
                ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                lp.height = maxBody;
                scroll.setLayoutParams(lp);
            }
        }
        return dlg;
    }
}
