package com.dywatch.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.feed.FeedActivity;
import com.dywatch.app.login.LoginActivity;
import com.dywatch.app.login.LoginManager;

/**
 * 腕上音符（dywatch）—— 主屏入口：登录状态 + 功能入口。
 */
public class MainActivity extends UiActivity {

    /**
     * 公告闸门（软件内问题 ①）：免责声明通过前一律为 false。
     * 说明：声明是红线且不可取消，公告是可延后的信息 → 只能 声明 → 公告 串行。
     */
    private boolean announceGateOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 一次性设置迁移（必须早于任何设置读取；0.8.0 起：快捷回复条默认值变更的兜底，见 Settings）
        com.dywatch.app.ui.Settings.migrateQuickReplyDefaultOnce(this);

        // 首启强制免责声明（红线 #2：内嵌 + 确认）
        // ⚠️ 弹窗串行化（软件内问题 ①）：**声明通过后才弹公告**。
        // 老写法是 showIfNeeded(this, null) + 紧接着 setupAnnounce()——两个弹窗同时发起，
        // 首启时公告盖在声明上（用户看到"两个弹窗叠一起"）。声明是红线且不可取消，
        // 公告是可延后的信息 → 顺序只能是 声明 → 公告。
        com.dywatch.app.util.Disclaimer.showIfNeeded(this, new Runnable() {
            @Override public void run() {
                announceGateOpen = true;   // 声明已通过（或本来已同意过）→ 放行公告
                setupAnnounce();
            }
        });

        final TextView status = findViewById(R.id.tv_status);
        refreshStatus(status);
        // 版本行读真实包版本号，杜绝手写值随发版漂移（v0.1.0 曾一直挂在 0.2.0 的包上）
        TextView about = findViewById(R.id.tv_about);
        if (about != null) {
            about.setText("v" + versionName() + " · 与官方无关 · 风险自负");
            // 方案 F：版本行兼做关于页入口（主屏一级位已满 2×2，用最轻的方式加入口）
            about.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this,
                            com.dywatch.app.ui.AboutActivity.class));
                }
            });
        }

        findViewById(R.id.btn_login).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
            }
        });

        findViewById(R.id.btn_feed).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FeedActivity.class));
            }
        });

        findViewById(R.id.btn_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 用户设计：会话列表 → 选人 → 进会话
                startActivity(new Intent(MainActivity.this, com.dywatch.app.chat.ConvListActivity.class));
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, com.dywatch.app.ui.SettingsActivity.class));
            }
        });

        findViewById(R.id.btn_mine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, com.dywatch.app.ui.MineActivity.class));
            }
        });

        // 手表硬件适配 §10：显式退出（无手势/无按键设备不靠返回键循环退出）
        findViewById(R.id.btn_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAffinity();
            }
        });

        checkUpdateQuietly();
    }

    /** 读本机包版本号（更新检查与版本行共用同一个来源） */
    private String versionName() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** 静默检查更新（M4）：有新版只在状态栏提示，不打扰 */
    private void checkUpdateQuietly() {
        final String current = versionName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.dywatch.app.net.UpdateChecker.UpdateInfo info =
                        new com.dywatch.app.net.UpdateChecker().check(current);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (info != null) {
                            android.widget.TextView status = findViewById(R.id.tv_status);
                            if (status != null) {
                                status.setText("发现新版本 " + info.version + "（设置→诊断日志 查看）");
                            }
                            com.dywatch.app.util.AppLog.i("update", "发现新版本 " + info.version);
                        }
                    }
                });
            }
        }, "update-check").start();
    }

    /** 诊断面板已收进设置页（并补了 WebView 版本/屏形/缩放/看过条数），主屏不再留重复入口 */

    /**
     * 公告（Cloudflare 三件之①）：**弹窗**形态（2026-09-30 用户拍板，banner 太不起眼）。
     * 口径：启动时拉一次 + 进设置页拉一次；有未读公告才弹；任何关闭都记 lastReadId（不反复打扰）；
     * 拉不到就静默。缓存里的公告回到主屏也能立刻弹（设置页拉到的新公告不用等下次启动）。
     *
     * ⚠️ 串行化（软件内问题 ①）：`announceGateOpen` 由免责声明的 onAccepted 置位。
     * 下面两处都要判——**异步回调（网络回来）也要挡**，否则声明还开着的时候公告就冒出来了。
     */
    private void setupAnnounce() {
        if (!announceGateOpen) {
            com.dywatch.app.util.AppLog.i("announce", "声明未通过 → 本次不弹公告");
            return;
        }
        // 先用缓存判定（可能是设置页刚拉的），再后台刷新一次
        com.dywatch.app.ui.AnnounceDialog.showIfPending(this);
        com.dywatch.app.net.AnnounceStore.refreshInBackground(this, false, new Runnable() {
            @Override
            public void run() {
                if (!announceGateOpen) return;   // 期间声明若被放弃（Activity 已结束）也不再弹
                com.dywatch.app.ui.AnnounceDialog.showIfPending(MainActivity.this);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(findViewById(R.id.tv_status));
        // 引擎作本页隐藏子视图获得真视口（页面不透明背景盖住它）
        com.dywatch.app.chat.ChatEngine.attachTo(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.dywatch.app.chat.ChatEngine.detachFrom(this);
    }

    private void refreshStatus(TextView status) {
        if (status == null) return;
        // 登录入口要反映状态：原先无论登录与否都写"登录"，点了才知道已经登过，
        // 主屏那行小字承担了本该由入口自己说明的信息。
        TextView login = findViewById(R.id.lbl_login);
        TextView loginState = findViewById(R.id.lbl_login_state);
        String state;
        if (LoginManager.hasSession(this) && LoginManager.isVerified(this)) {
            status.setText("已登录（会话存本机）");
            state = "已登录";
        } else if (LoginManager.hasSession(this)) {
            status.setText("有会话但未验证/可能失效——建议重新扫码登录");
            state = "需重登";
        } else {
            status.setText("未登录——点上方“登录”扫码");
            state = "未登录";
        }
        if (login != null) login.setText("登录");
        if (loginState != null) loginState.setText(state);
    }
}
