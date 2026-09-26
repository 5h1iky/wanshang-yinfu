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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 首启强制免责声明（红线 #2：内嵌 + 确认）
        com.dywatch.app.util.Disclaimer.showIfNeeded(this, null);

        final TextView status = findViewById(R.id.tv_status);
        refreshStatus(status);
        // 版本行读真实包版本号，杜绝手写值随发版漂移（v0.1.0 曾一直挂在 0.2.0 的包上）
        TextView about = findViewById(R.id.tv_about);
        if (about != null) {
            about.setText("v" + versionName() + " · 与官方无关 · 风险自负");
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
