package com.dywatch.app.ui;

// 公告弹窗（2026-09-30 用户拍板：banner 太不起眼 → 改弹窗）。
//
// 口径：
//   - 有"未读且未过期"的公告才弹（AnnounceStore.pending 判定），同一条读过就不再打扰
//   - 任何关闭方式（点按钮 / 返回键 / 点外部）都记 lastReadId，避免反复弹
//   - 有 link 时给一个"查看详情"按钮（手表上多半没浏览器，失败只提示不崩）
//   - 复用 Disclaimer 同款 Material3 AlertDialog，视觉与首启须知一致

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

import com.dywatch.app.net.AnnounceApi;
import com.dywatch.app.net.AnnounceStore;

public final class AnnounceDialog {

    private AnnounceDialog() {}

    /** 有未读公告就弹；没有则什么都不做（静默） */
    public static void showIfPending(final Activity act) {
        if (act == null || act.isFinishing()) return;
        final AnnounceApi.Announcement a = AnnounceStore.pending(act);
        if (a == null) return;

        String body = a.text == null ? "" : a.text;
        if (a.link != null && !a.link.isEmpty()) {
            body = body + (body.isEmpty() ? "" : "\n\n") + "详情：" + a.link;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(a.title)
                .setMessage(body)
                .setCancelable(true)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 关闭即已读（由 setOnDismissListener 统一记账）
                    }
                });

        if (a.link != null && !a.link.isEmpty()) {
            b.setNeutralButton("查看详情", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.link)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(act, "本机没有可打开链接的应用",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        final AlertDialog dlg = b.create();
        // 任何关闭方式都记已读——否则会反复弹（用户最烦这个）
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                AnnounceStore.markRead(act, a.id);
                com.dywatch.app.util.AppLog.i("announce", "公告弹窗已关闭并记已读 id=" + a.id);
            }
        });
        dlg.show();
        com.dywatch.app.util.AppLog.i("announce", "弹出公告 id=" + a.id + " title=" + a.title);
    }
}
