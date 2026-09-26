package com.dywatch.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.dywatch.app.feed.FeedActivity;
import com.dywatch.app.login.LoginActivity;
import com.dywatch.app.login.LoginManager;

/**
 * 腕上音符（dywatch）—— 主屏入口：登录状态 + 功能入口。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 首启强制免责声明（红线 #2：内嵌 + 确认）
        com.dywatch.app.util.Disclaimer.showIfNeeded(this, null);

        final TextView status = findViewById(R.id.tv_status);
        refreshStatus(status);

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

        findViewById(R.id.btn_diag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiagnostics();
            }
        });

        findViewById(R.id.btn_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 用户设计：会话列表 → 选人 → 进会话
                startActivity(new Intent(MainActivity.this, com.dywatch.app.chat.ConvListActivity.class));
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

    /** 静默检查更新（M4）：有新版只在状态栏提示，不打扰 */
    private void checkUpdateQuietly() {
        String ver;
        try {
            ver = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            ver = "0.0.0";
        }
        final String current = ver;
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
                                status.setText("发现新版本 " + info.version + "（打开诊断查看）");
                            }
                            com.dywatch.app.util.AppLog.i("update", "发现新版本 " + info.version);
                        }
                    }
                });
            }
        }, "update-check").start();
    }

    /** 诊断面板：无 adb 环境的观测窗口（用户拍照反馈的主要信息源） */
    private void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("【WebView】").append(webViewInfo()).append('\n');
        sb.append("【登录】").append(LoginManager.hasSession(this) ? "有会话" : "无").append('\n');
        sb.append("【上次崩溃】\n").append(com.dywatch.app.util.CrashShield.lastCrash(this)).append("\n\n");
        sb.append("【最近日志】\n").append(com.dywatch.app.util.AppLog.tail(12));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("诊断")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .setNeutralButton("免责声明", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        com.dywatch.app.util.Disclaimer.show(MainActivity.this);
                    }
                })
                .show();
    }

    private String webViewInfo() {
        try {
            android.content.pm.PackageInfo pi;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                pi = android.webkit.WebView.getCurrentWebViewPackage();
            } else {
                pi = getPackageManager().getPackageInfo("com.google.android.webview", 0);
            }
            return pi != null ? pi.packageName + " v" + pi.versionName : "未检测到";
        } catch (Throwable t) {
            return "检测失败(" + t.getClass().getSimpleName() + ")";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(findViewById(R.id.tv_status));
    }

    private void refreshStatus(TextView status) {
        if (status == null) return;
        if (LoginManager.hasSession(this) && LoginManager.isVerified(this)) {
            status.setText("已登录（会话存本机）");
        } else if (LoginManager.hasSession(this)) {
            status.setText("有会话但未验证/可能失效——建议重新扫码登录");
        } else {
            status.setText("未登录——点下方“登录”扫码");
        }
    }
}
