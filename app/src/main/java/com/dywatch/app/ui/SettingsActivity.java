package com.dywatch.app.ui;

// 设置页：手表适配的开关集中在这。行由代码生成，值就地循环切换——
// 手表屏小手指粗，弹列表/拖滑条都难点准，"点一下换一档"最省事。

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dywatch.app.R;
import com.dywatch.app.feed.SeenStore;
import com.dywatch.app.login.LoginManager;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.util.AppLog;
import com.dywatch.app.util.CrashShield;

public class SettingsActivity extends UiActivity {

    private LinearLayout mRoot;

    private static final float[] SCALES = {0.85f, 1.0f, 1.15f, 1.3f};
    private static final String[] SCALE_NAMES = {"小", "标准", "大", "特大"};
    private static final String[] SHAPE_NAMES = {"自动", "圆屏", "方屏"};
    private static final String[] QUALITY_NAMES = {"省流量", "平衡", "清晰"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setPageTitle("设置");
        mRoot = findViewById(R.id.ll_settings);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        buildRows();
    }

    private void buildRows() {
        mRoot.removeAllViews();
        row("界面缩放", SCALE_NAMES[index(SCALES, Settings.scale(this))], new Runnable() {
            @Override public void run() {
                int i = (index(SCALES, Settings.scale(SettingsActivity.this)) + 1) % SCALES.length;
                Settings.setScale(SettingsActivity.this, SCALES[i]);
                DouyinApi.sQuality = Settings.qualityMode(SettingsActivity.this);
                toast("界面缩放：" + SCALE_NAMES[i]);
                recreate();   // 密度改了要重建才生效
            }
        });
        row("屏幕形状", SHAPE_NAMES[Settings.shapeMode(this)], new Runnable() {
            @Override public void run() {
                int next = (Settings.shapeMode(SettingsActivity.this) + 1) % 3;
                Settings.setShapeMode(SettingsActivity.this, next);
                toast("屏幕形状：" + SHAPE_NAMES[next]
                        + (next == Settings.SHAPE_ROUND ? "（内容横向内缩，防四角裁切）" : ""));
                recreate();
            }
        });
        row("横向边距", percentLabel(Settings.paddingHPercent(this)), new Runnable() {
            @Override public void run() {
                int v = nextPercent(Settings.paddingHPercent(SettingsActivity.this));
                Settings.setPaddingPercent(SettingsActivity.this, v, Settings.paddingVPercent(SettingsActivity.this));
                toast("横向边距：" + v + "%");
                recreate();
            }
        });
        row("纵向边距", percentLabel(Settings.paddingVPercent(this)), new Runnable() {
            @Override public void run() {
                int v = nextPercent(Settings.paddingVPercent(SettingsActivity.this));
                Settings.setPaddingPercent(SettingsActivity.this, Settings.paddingHPercent(SettingsActivity.this), v);
                toast("纵向边距：" + v + "%");
                recreate();
            }
        });
        row("视频画质", QUALITY_NAMES[Settings.qualityMode(this)] + " · " + qualityHint(), new Runnable() {
            @Override public void run() {
                int next = (Settings.qualityMode(SettingsActivity.this) + 1) % 3;
                Settings.setQualityMode(SettingsActivity.this, next);
                DouyinApi.sQuality = next;
                toast("视频画质：" + QUALITY_NAMES[next]);
                buildRows();
            }
        });
        row("评论自动续拉", onOff(Settings.commentAutoLoad(this)), new Runnable() {
            @Override public void run() {
                boolean v = !Settings.commentAutoLoad(SettingsActivity.this);
                Settings.setCommentAutoLoad(SettingsActivity.this, v);
                toast("评论自动续拉：" + onOff(v));
                buildRows();
            }
        });
        row("刷视频常亮", onOff(Settings.keepScreenOn(this)), new Runnable() {
            @Override public void run() {
                boolean v = !Settings.keepScreenOn(SettingsActivity.this);
                Settings.setKeepScreenOn(SettingsActivity.this, v);
                toast("刷视频常亮：" + onOff(v));
                buildRows();
            }
        });
        actionRow("清除「看过」记录", "重置后推荐可能重复出现旧视频", new Runnable() {
            @Override public void run() {
                SeenStore.clear(SettingsActivity.this);
                toast("已清除看过记录");
            }
        });
        actionRow("诊断日志", "看最近运行记录与上次崩溃", new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("诊断")
                        .setMessage("【登录】" + (LoginManager.hasSession(SettingsActivity.this) ? "有会话" : "无")
                                + "\n【上次崩溃】\n" + CrashShield.lastCrash(SettingsActivity.this)
                                + "\n【最近日志】\n" + AppLog.tail(25))
                        .setPositiveButton("关闭", null)
                        .show();
            }
        });
        actionRow("退出登录", "清除本机保存的会话", new Runnable() {
            @Override public void run() {
                LoginManager.clear(SettingsActivity.this);
                toast("已退出登录");
            }
        });
        actionRow("恢复默认", "缩放/屏形/边距/画质全部回到初始值", new Runnable() {
            @Override public void run() {
                Settings.setScale(SettingsActivity.this, 1.0f);
                Settings.setShapeMode(SettingsActivity.this, Settings.SHAPE_AUTO);
                Settings.setPaddingPercent(SettingsActivity.this, 0, 0);
                Settings.setQualityMode(SettingsActivity.this, Settings.Q_SAVE);
                Settings.setCommentAutoLoad(SettingsActivity.this, true);
                Settings.setKeepScreenOn(SettingsActivity.this, true);
                DouyinApi.sQuality = Settings.Q_SAVE;
                toast("已恢复默认");
                recreate();
            }
        });
        infoRow("腕上音符 v0.1.0\n与抖音官方无关 · 仅供个人学习 · 风险自负");
    }

    private String qualityHint() {
        int q = Settings.qualityMode(this);
        return q == Settings.Q_SAVE ? "540p" : (q == Settings.Q_BALANCED ? "720p" : "1080p");
    }

    /** 边距档位：0 就是铺满，圆屏一般 4~8% 收得住；上限留给 14 免得调到没法用 */
    private static final int[] PERCENTS = {0, 2, 4, 6, 8, 10, 14};

    private static int nextPercent(int cur) {
        for (int i = 0; i < PERCENTS.length; i++) {
            if (PERCENTS[i] == cur) return PERCENTS[(i + 1) % PERCENTS.length];
        }
        return 0;
    }

    private static String percentLabel(int v) {
        return v == 0 ? "0%（铺满）" : v + "%";
    }

    private static String onOff(boolean b) { return b ? "开" : "关"; }

    private static int index(float[] arr, float v) {
        for (int i = 0; i < arr.length; i++) if (Math.abs(arr[i] - v) < 0.01f) return i;
        return 1;
    }

    private void row(String label, String value, Runnable onClick) {
        addBase(label + "\n" + value, onClick);
    }

    private void actionRow(String label, String sub, Runnable onClick) {
        addBase(label + "\n" + sub, onClick);
    }

    private void infoRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_faint));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(14), dp(12), dp(14));
        mRoot.addView(tv);
    }

    private void addBase(String text, final Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setMinimumHeight(dp(48));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setBackgroundResource(R.drawable.bg_settings_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        mRoot.addView(tv);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
