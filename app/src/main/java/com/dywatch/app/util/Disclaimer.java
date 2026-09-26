package com.dywatch.app.util;

// 免责声明（红线 #2：应用内必须内嵌）。首次启动强制确认，之后可在"诊断"里重看。
// 内容要点：与官方无关 / 非官方应用 / 风险自负 / 不收费不广告 / 登录态只存本机 / 仅供学习交流。

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;

import androidx.appcompat.app.AlertDialog;

public final class Disclaimer {

    private static final String PREF = "dywatch_disclaimer";
    private static final String KEY_ACCEPTED = "accepted";

    public static final String TEXT =
            "【免责声明】\n\n"
                    + "1. 本应用「腕上音符」为个人开发的兴趣项目，与抖音及其关联公司"
                    + "（北京微播视界科技有限公司等）**无任何官方关系**，非官方应用。\n\n"
                    + "2. 本应用**不收费、不接广告、不设任何付费功能**，仅供学习与技术交流，"
                    + "免费分享给朋友使用。\n\n"
                    + "3. 使用本应用产生的一切后果（包括但不限于账号限制、数据损失等）"
                    + "由使用者自行承担，开发者不承担任何责任。\n\n"
                    + "4. 登录态仅保存在您本人的设备上，开发者**不收集、不存储、不上传**"
                    + "任何账号信息或聊天内容。\n\n"
                    + "5. 请遵守相关平台的服务条款，合理使用；禁止用于任何骚扰、营销、"
                    + "自动化等不当用途。\n\n"
                    + "6. 本应用不提供任何技术服务承诺，随时可能停止更新或维护。\n\n"
                    + "点击「同意并继续」即表示您已阅读并理解上述条款。";

    private Disclaimer() {}

    public static boolean isAccepted(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ACCEPTED, false);
    }

    /** 首启强制确认；同意后回调 onAccepted（可为 null） */
    public static void showIfNeeded(final Context ctx, final Runnable onAccepted) {
        if (isAccepted(ctx)) {
            if (onAccepted != null) onAccepted.run();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle("使用前须知")
                .setMessage(TEXT)
                .setCancelable(false)
                .setPositiveButton("同意并继续", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                        sp.edit().putBoolean(KEY_ACCEPTED, true).apply();
                        if (onAccepted != null) onAccepted.run();
                    }
                })
                .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (ctx instanceof android.app.Activity) {
                            ((android.app.Activity) ctx).finish();
                        }
                    }
                })
                .show();
    }

    /** 供"诊断/关于"重看全文 */
    public static void show(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setTitle("免责声明")
                .setMessage(TEXT)
                .setPositiveButton("知道了", null)
                .show();
    }
}
