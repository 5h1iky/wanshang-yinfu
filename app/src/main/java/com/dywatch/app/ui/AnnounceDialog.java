package com.dywatch.app.ui;

// 公告弹窗（2026-09-30 用户两次拍板后的形态）：
//   第一版 → 主屏顶部细 banner（用户："太不起眼/我根本没看到"）
//   第二版 → 系统 AlertDialog（用户："怎么又用系统自带的那个了"）
//   现在   → 自绘弹窗 DyDialog：深色圆角卡 + 胶囊按钮，与「设置行」同一套视觉
//
// 口径：
//   - 有"未读且未过期"的公告才弹（AnnounceStore.pending 判定），同一条读过就不再打扰
//   - 任何关闭方式（按钮 / 返回键 / 点外部）都记 lastReadId，避免反复弹
//   - 有 link 时给次按钮「查看详情」（手表上多半没浏览器，失败只提示不崩）
//   - level 只影响**标题颜色**（info 常规白 / warn 品牌粉 / danger 浅红）——
//     不铺装饰色：色板纪律规定品牌粉只允许出现在状态栏/进度条/点赞激活三处

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.dywatch.app.R;
import com.dywatch.app.net.AnnounceApi;
import com.dywatch.app.net.AnnounceStore;

public final class AnnounceDialog {

    private AnnounceDialog() {}

    /** 有未读公告就弹；没有则什么都不做（静默） */
    public static void showIfPending(final Activity act) {
        if (act == null || act.isFinishing()) return;
        final AnnounceApi.Announcement a = AnnounceStore.pending(act);
        if (a == null) return;

        String bodyText = a.text == null ? "" : a.text;
        final boolean hasLink = a.link != null && !a.link.isEmpty();
        if (hasLink) {
            bodyText = bodyText + (bodyText.isEmpty() ? "" : "\n\n") + a.link;
        }

        int titleColor = R.color.text_primary;
        if (AnnounceApi.LEVEL_DANGER.equals(a.level)) {
            titleColor = R.color.danger;
        } else if (AnnounceApi.LEVEL_WARN.equals(a.level)) {
            titleColor = R.color.accent;
        }

        DyDialog.Opt o = new DyDialog.Opt()
                .title(a.title)
                .body(bodyText)
                .titleColor(titleColor)
                .positive("知道了", null)
                .onDismiss(new Runnable() {
                    @Override public void run() {
                        // 任何关闭方式都记已读——否则会反复弹（用户最烦这个）
                        AnnounceStore.markRead(act, a.id);
                        com.dywatch.app.util.AppLog.i("announce", "公告已关闭并记已读 id=" + a.id);
                    }
                });
        if (hasLink) {
            o.negative("查看详情", new Runnable() {
                @Override public void run() {
                    try {
                        act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.link)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(act, "本机没有可打开链接的应用",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        DyDialog.show(act, o);
        com.dywatch.app.util.AppLog.i("announce", "弹出公告 id=" + a.id + " title=" + a.title);
    }
}
