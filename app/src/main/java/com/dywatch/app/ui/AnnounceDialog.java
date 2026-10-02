package com.dywatch.app.ui;

// 公告弹窗（2026-09-30 用户两次拍板后的形态）：
//   第一版 → 主屏顶部细 banner（用户："太不起眼/我根本没看到"）
//   第二版 → 系统 AlertDialog（用户："怎么又用系统自带的那个了"）
//   现在   → 自绘弹窗 DyDialog：深色圆角卡 + 胶囊按钮，与「设置行」同一套视觉
//
// 口径：
//   - 有"未读且未过期"的公告才弹（AnnounceStore.pending 判定），同一条读过就不再打扰
//   - 任何关闭方式（按钮 / 返回键 / 点外部）都记 lastReadId，避免反复弹
//   - **同一时刻只允许一个公告在显示**（sShowing 守卫，2026-10-01 软件内问题 ①）
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

    /**
     * "正在显示"守卫（软件内问题 ①，2026-10-01）。
     *
     * 为什么需要：老流程是「先用缓存弹一次 → 网络回来再弹一次」，而 `markRead` 只在 onDismiss 里写，
     * 第一个还开着时 `AnnounceStore.pending()` 仍返回同一条 → **又弹一个盖在上面**，
     * 用户点掉上面那个才发现下面还有一个，观感就是"公告要点两次才消失"。
     * 一个根因（重复弹）两个现象（叠窗、点两次）。
     *
     * 用 WeakReference 记持有者：万一 Activity 先没了（弹窗未正常回调），守卫不会永久卡死后续公告。
     */
    private static boolean sShowing;
    private static java.lang.ref.WeakReference<Activity> sShowingOwner;

    private AnnounceDialog() {}

    /** 有未读公告就弹；没有则什么都不做（静默）；已有公告在显示时**不弹第二个** */
    public static void showIfPending(final Activity act) {
        if (act == null || act.isFinishing()) return;
        if (!claimShowingSlot(act)) return;

        final AnnounceApi.Announcement a = AnnounceStore.pending(act);
        if (a == null) {
            releaseShowingSlot();
            return;
        }

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
                        releaseShowingSlot();   // 先放守卫，再记账
                        // 任何关闭方式都记已读——否则会反复弹（用户最烦这个）
                        AnnounceStore.markRead(act, a.id);
                        com.dywatch.app.util.AppLog.i("announce", "公告已关闭并记已读 id=" + a.id);
                    }
                });
        if (hasLink) {
            o.negative("查看详情", new Runnable() {
                @Override public void run() {
                    try {
                        // 双保险（审计 M4）：AnnounceApi 已按白名单过滤过 link，这里再确认一次
                        // scheme 是 https —— 无论如何不让 http/自定义 scheme 进 ACTION_VIEW。
                        android.net.Uri u = android.net.Uri.parse(a.link);
                        if (u == null || !"https".equalsIgnoreCase(u.getScheme())) {
                            android.widget.Toast.makeText(act, "链接不可用",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        act.startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(act, "本机没有可打开链接的应用",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        android.app.Dialog dlg = DyDialog.show(act, o);
        if (dlg == null) {
            // 没弹成（Activity 已结束等）→ 必须放守卫，否则后续公告全被挡掉
            releaseShowingSlot();
            return;
        }
        com.dywatch.app.util.AppLog.i("announce", "弹出公告 id=" + a.id + " title=" + a.title);
    }

    /**
     * 抢占"正在显示"名额：返回 true 表示可以弹。
     * 旧持有者还活着 → false（这就是"重复弹/点两次"的防线）；
     * 旧持有者已结束（页面销毁但回调没来）→ 视为不再占用名额，放行。
     */
    private static boolean claimShowingSlot(Activity act) {
        if (sShowing) {
            Activity owner = sShowingOwner == null ? null : sShowingOwner.get();
            if (owner != null && !owner.isFinishing() && !owner.isDestroyed()) {
                com.dywatch.app.util.AppLog.i("announce", "已有公告在显示 → 跳过重复弹出");
                return false;
            }
            sShowing = false;
            sShowingOwner = null;
        }
        sShowing = true;
        sShowingOwner = new java.lang.ref.WeakReference<Activity>(act);
        return true;
    }

    private static void releaseShowingSlot() {
        sShowing = false;
        sShowingOwner = null;
    }
}
