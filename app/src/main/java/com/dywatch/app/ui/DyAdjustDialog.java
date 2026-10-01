package com.dywatch.app.ui;

// 数值调节面板（软件内问题 ②，2026-10-01）。
//
// 为什么不再"点一下换一档"：边距原档位 {0,2,4,6,8,10,14} **只前进不后退**——
// 从 2% 想回 0% 要连点 6 次（2→4→6→8→10→14→0），档位跨度还不均（…10→14）。
// 现在点开面板：− / + 步进 1%、范围 0~30%（与 Settings.clampPercent 的上限对齐）、
// 长按连发、一键归零、**每次改值都回调** → 设置页当场内缩/展开（实时预览）。
//
// 视觉沿用 DyDialog：深色圆角卡 + 胶囊按钮，**不用系统控件**（用户否定过两次的红线）。
// 只给"需要连续调节"的项用（本项目当前只有横/纵向边距）；档位少的项继续循环点按（避免过度执行）。

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import com.dywatch.app.R;

public final class DyAdjustDialog {

    /** 值变化回调：每次 − / + / 归零 / 长按连发都会调一次（调用方在这里落盘 + 实时预览） */
    public interface OnValueChange {
        void onChange(int value);
    }

    /** 长按连发的间隔（首步在长按判定时立刻执行，之后按这个节奏走） */
    private static final long REPEAT_INTERVAL_MS = 120L;

    private DyAdjustDialog() {}

    /**
     * 弹出调节面板。
     *
     * @param title  标题（如"横向边距"）
     * @param value  初值（越界会被夹到 [min,max]）
     * @param step   步进（边距用 1，即 1%）
     * @param min    下限（含）
     * @param max    上限（含）
     * @param suffix 值后缀（边距用 "%"）
     * @param onChange 值变化回调（可为 null，但那样就只是白调）
     */
    public static void show(Activity act, String title, int value, int step, int min, int max,
                            String suffix, OnValueChange onChange) {
        if (act == null || act.isFinishing()) return;
        new Panel(act, title, value, step, min, max, suffix, onChange).show();
    }

    /** 一次面板会话的状态（省得把七八个参数在方法之间传来传去） */
    private static final class Panel {
        private final Activity act;
        private final String title, suffix;
        private final int step, min, max;
        private final OnValueChange onChange;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final java.util.Map<View, Runnable> repeaters = new java.util.HashMap<View, Runnable>();

        private final View content;
        private final TextView tvValue, btnMinus, btnPlus, btnReset;
        private final Dialog dlg;
        private int value;

        Panel(Activity act, String title, int value, int step, int min, int max,
              String suffix, OnValueChange onChange) {
            this.act = act;
            this.title = title;
            this.step = Math.max(1, step);
            this.min = min;
            this.max = Math.max(min, max);
            this.suffix = suffix == null ? "" : suffix;
            this.onChange = onChange;
            this.value = clamp(value);

            content = act.getLayoutInflater().inflate(R.layout.dialog_adjust, null);
            tvValue = content.findViewById(R.id.adj_value);
            btnMinus = content.findViewById(R.id.adj_minus);
            btnPlus = content.findViewById(R.id.adj_plus);
            btnReset = content.findViewById(R.id.adj_reset);
            ((TextView) content.findViewById(R.id.adj_title)).setText(title);
            // 面板是 chrome（不是阅读面）→ 不跟随字体缩放，与 DyDialog 的标题/按钮同口径
            ((TextView) content.findViewById(R.id.adj_hint)).setText(
                    "范围 " + min + "~" + max + this.suffix + " · 步进 " + this.step + this.suffix
                            + " · 长按 −/+ 可连发");

            dlg = new Dialog(act);
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dlg.setContentView(content);
            dlg.setCancelable(true);                 // 点外部/返回键都能关（值已实时保存）
            dlg.setCanceledOnTouchOutside(true);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.addFlags(Window.FEATURE_NO_TITLE);
            }
        }

        void show() {
            bindStep(btnMinus, -1);
            bindStep(btnPlus, +1);
            btnReset.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { apply(0); }
            });
            content.findViewById(R.id.adj_done).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });
            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) {
                    stopAllRepeats();   // 别让连发在窗口没了以后还在跑
                }
            });

            render();
            dlg.show();
            Window w = dlg.getWindow();
            if (w != null) {
                int screenW = act.getResources().getDisplayMetrics().widthPixels;
                w.setLayout((int) (screenW * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        /** − / + 的共用接线：单击一步；长按先走一步，再按固定节奏连发（到边界自动停） */
        private void bindStep(final TextView btn, final int dir) {
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { apply(value + dir * step); }
            });
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    apply(value + dir * step);            // 立刻反馈一步
                    Runnable r = repeater(btn, dir);
                    handler.postDelayed(r, REPEAT_INTERVAL_MS);
                    return true;                          // 消费掉，避免抬手时又走一次单击
                }
            });
            // 抬手/取消 → 停连发（返回 false，不抢点击与长按）
            btn.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        stopRepeat(btn);
                    }
                    return false;
                }
            });
        }

        private Runnable repeater(final TextView btn, final int dir) {
            Runnable r = repeaters.get(btn);
            if (r == null) {
                r = new Runnable() {
                    @Override public void run() {
                        if (apply(value + dir * step)) {
                            handler.postDelayed(this, REPEAT_INTERVAL_MS);
                        } else {
                            repeaters.remove(btn);   // 到边界了 → 停
                        }
                    }
                };
                repeaters.put(btn, r);
            }
            return r;
        }

        private void stopRepeat(View btn) {
            Runnable r = repeaters.remove(btn);
            if (r != null) handler.removeCallbacks(r);
        }

        private void stopAllRepeats() {
            for (Runnable r : repeaters.values()) handler.removeCallbacks(r);
            repeaters.clear();
        }

        /** 改值（夹取后与旧值相同则返回 false，调用方据此停止连发） */
        private boolean apply(int nv) {
            int c = clamp(nv);
            if (c == value) return false;
            value = c;
            render();
            if (onChange != null) onChange.onChange(value);
            return true;
        }

        private int clamp(int v) {
            if (v < min) return min;
            return Math.min(v, max);
        }

        /** 值 + 两个步进键的可用态（到边界就压暗并禁用，避免"点了没反应"的迷惑） */
        private void render() {
            tvValue.setText(value + suffix);
            boolean canMinus = value > min;
            boolean canPlus = value < max;
            btnMinus.setEnabled(canMinus);
            btnMinus.setAlpha(canMinus ? 1f : 0.35f);
            btnPlus.setEnabled(canPlus);
            btnPlus.setAlpha(canPlus ? 1f : 0.35f);
            boolean canReset = value != 0;
            btnReset.setEnabled(canReset);
            btnReset.setAlpha(canReset ? 1f : 0.35f);
        }
    }
}
