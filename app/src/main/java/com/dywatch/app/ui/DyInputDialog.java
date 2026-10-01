package com.dywatch.app.ui;

// 单行输入弹窗（软件内问题 ③b，2026-10-01）。
//
// 为什么不用系统 AlertDialog + EditText：用户否定过两次——系统弹窗的灰底/按钮字体跟
// App 的深色卡片 + 胶囊按钮不是一套语言。这里自绘（DyDialog 同款卡片语言 + bg_pill 输入框）。
//
// 用途：快捷回复槽位改文案；以后"给某个设置项输文本"也能复用。
// 行为：确定 → 回调清洗后的文本；取消/返回/点外部 → 什么都不做（不回调）。

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import com.dywatch.app.R;

public final class DyInputDialog {

    /** 结果回调（只在点「保存」时触发；空串是合法结果 = 该槽位留空） */
    public interface OnText {
        void onText(String text);
    }

    private DyInputDialog() {}

    /**
     * @param title    标题（如"第 1 个快捷回复"）
     * @param initial  初始文本
     * @param maxLen   最大长度（超出直接截断，避免手表上撑破细条）
     * @param hint     输入框 hint / 说明（可为空）
     */
    public static void show(final Activity act, String title, String initial, final int maxLen,
                            String hint, final OnText cb) {
        if (act == null || act.isFinishing()) return;

        final View content = act.getLayoutInflater().inflate(R.layout.dialog_input, null);
        final TextView tvTitle = content.findViewById(R.id.input_title);
        final EditText et = content.findViewById(R.id.input_et);
        final TextView tvHint = content.findViewById(R.id.input_hint);
        final TextView btnOk = content.findViewById(R.id.input_ok);
        final TextView btnCancel = content.findViewById(R.id.input_cancel);

        tvTitle.setText(title);
        if (hint == null || hint.isEmpty()) {
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.setText(hint);
        }
        et.setHint("留空 = 不显示这个按钮");
        if (initial != null) {
            et.setText(initial);
            et.setSelection(et.getText().length());
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
            // ⚠️ 只用 ADJUST_RESIZE，**不要**带 SOFT_INPUT_STATE_ALWAYS_VISIBLE：
            //    实测那样写，用户按返回收起键盘后系统会立刻把全屏键盘又弹回来，
            //    「保存/取消」永远点不到（真机踩过）。这里改成 show 之后手动显示一次。
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = et.getText() == null ? "" : et.getText().toString();
                dlg.dismiss();
                if (cb != null) cb.onText(QuickReplyTexts.sanitize(text));
            }
        });
        // ⚠️ 真机查到的事实（2026-10-01，手表自带搜狗输入法是**全屏 T9**，把弹窗整个盖住）：
        //    · 全屏键盘的「→ / 收起」键会让 IME 报一次 **IME_ACTION_DONE**，于是带 actionDone 时
        //      "点动作键"和"收起键盘"都走保存分支 → **不会丢字**（用户打完字怎么离开都存上）。
        //    · 试过改成 actionNone 想让"收键盘/保存"分开：结果「→」成了死键，用户以为存上了其实没存
        //      ——比"不能取消"更糟，已放弃。
        //    · 也试过在返回键里"只收键盘"：实测按键根本到不了弹窗（被 IME 先消费），日志可证，已删。
        //    结论：提交点交给 IME 的动作键；弹窗上的「保存 / 取消」是键盘收起后的备用路径。
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                // 只认真正表示"完成"的动作（别的回调——例如 IME 自己的杂项事件——一律忽略）
                boolean done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        || (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            && e.getAction() == android.view.KeyEvent.ACTION_UP);
                if (done) {
                    btnOk.performClick();   // 手表上 IME 的动作键比点按钮好按
                    return true;
                }
                return false;
            }
        });

        dlg.show();
        Window win = dlg.getWindow();
        if (win != null) {
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            win.setLayout((int) (screenW * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        et.requestFocus();
        // 打开就弹键盘（要改文案的用户不用再点一下输入框）。
        // ⚠️ 必须延迟：show() 刚返回时窗口还没有输入焦点，立刻 showSoftInput 会被丢掉（实测收不到）。
        // 用户按返回收掉键盘后不会被强制弹回（没带 ALWAYS_VISIBLE），此时弹窗上的「保存」可点。
        et.postDelayed(new Runnable() {
            @Override public void run() {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) act.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, 0);
            }
        }, 300);
    }
}
