package com.dywatch.app.ui;

// 快捷回复内容编辑面板（软件内问题 ③b，2026-10-01）。
//
// 形态：4 行槽位（显示当前文案）→ 点某行 → 弹 DyInputDialog 单行输入 → 保存即生效；
//      另有「恢复默认」（删掉 4 个键，回到 好/在忙/稍等/😂）。
//
// 为什么是"4 行 + 逐行改"而不是"一个多行文本框"：手表屏 320dp 宽、键盘一占就剩一条缝，
// 多行编辑在小屏上是灾难；逐行改每次只面对一行，且**留空即隐藏该按钮**（QuickReply.wire 已支持）。
// 文案存哪、怎么清洗：Settings.quickReplyTexts / QuickReplyTexts（纯逻辑，可 JVM 单测）。

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import com.dywatch.app.R;

public final class QuickReplyEditDialog {

    /** 面板关闭时回调（调用方刷新设置页那一行的摘要） */
    public interface OnChanged {
        void onChanged();
    }

    private static final int[] ROW_IDS = {R.id.qre1, R.id.qre2, R.id.qre3, R.id.qre4};

    private QuickReplyEditDialog() {}

    public static void show(final Activity act, final OnChanged cb) {
        if (act == null || act.isFinishing()) return;

        final View content = act.getLayoutInflater().inflate(R.layout.dialog_quickreply, null);
        final TextView[] rows = new TextView[ROW_IDS.length];
        for (int i = 0; i < ROW_IDS.length; i++) {
            rows[i] = content.findViewById(ROW_IDS[i]);
        }

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(content);
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(true);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(Window.FEATURE_NO_TITLE);
        }

        // 每行的显示与点击都在这里接：改完立刻回填这一行（不必关面板重开）
        final Runnable render = new Runnable() {
            @Override public void run() {
                String[] texts = Settings.quickReplyTexts(act);
                for (int i = 0; i < rows.length; i++) {
                    String t = texts[i];
                    rows[i].setText((i + 1) + ". "
                            + (QuickReplyTexts.isEmpty(t) ? "（留空 · 该按钮隐藏）" : t));
                }
            }
        };
        render.run();

        for (int i = 0; i < rows.length; i++) {
            final int slot = i + 1;
            rows[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String[] cur = Settings.quickReplyTexts(act);
                    DyInputDialog.show(act, "第 " + slot + " 个快捷回复", cur[slot - 1],
                            QuickReplyTexts.MAX_LEN, null, new DyInputDialog.OnText() {
                                @Override public void onText(String text) {
                                    Settings.setQuickReplyText(act, slot, text);
                                    com.dywatch.app.util.AppLog.i("settings", "快捷回复 #" + slot
                                            + " 改为「" + (text.isEmpty() ? "（留空）" : text) + "」");
                                    render.run();
                                    if (cb != null) cb.onChanged();
                                }
                            });
                }
            });
        }

        content.findViewById(R.id.qre_reset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Settings.resetQuickReplyTexts(act);
                render.run();
                if (cb != null) cb.onChanged();
                android.widget.Toast.makeText(act, "已恢复默认文案",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        content.findViewById(R.id.qre_done).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (cb != null) cb.onChanged();
            }
        });

        dlg.show();
        Window win = dlg.getWindow();
        if (win != null) {
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            win.setLayout((int) (screenW * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
