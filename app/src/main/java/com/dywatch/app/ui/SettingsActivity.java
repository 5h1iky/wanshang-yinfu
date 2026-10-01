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
    /**
     * 字号档（2026-09-27 新增）：只放大 sp 文字，不动控件尺寸。
     * 与「界面缩放」是两件事——缩放会把控件一起放大，字号只让字变大，
     * 所以"控件够大但字小"时应该调这个（手表上最常见的诉求）。
     */
    private static final float[] FONT_SCALES = {0.85f, 1.0f, 1.15f, 1.3f, 1.5f};
    private static final String[] FONT_NAMES = {"小", "标准", "大", "特大", "超大"};
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
        // 公告（Cloudflare 三件之①）：用户拍板的第二个拉取点——进设置页刷新一次，
        // 回到主屏即可看到新公告（主屏只负责显示，不重复打网络）。
        com.dywatch.app.net.AnnounceStore.refreshInBackground(this, true, null);
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
        // 字号：只放大文字（sp），控件尺寸不变 —— 与上面「界面缩放」互补
        row("字体大小", FONT_NAMES[index(FONT_SCALES, Settings.fontScale(this))], new Runnable() {
            @Override public void run() {
                int i = (index(FONT_SCALES, Settings.fontScale(SettingsActivity.this)) + 1) % FONT_SCALES.length;
                Settings.setFontScale(SettingsActivity.this, FONT_SCALES[i]);
                toast("字体大小：" + FONT_NAMES[i]);
                recreate();   // 字号改了同样要重建才生效
            }
        });
        // 快捷回复条：压成 28dp 细条之后仍给一个总开关（用户反馈"太占位置"）
        // ⚠️ 默认值已改「关」（软件内问题 ③a）；老用户存过键的仍按存的来，不会突然消失
        row("快捷回复条", onOff(Settings.quickReplyVisible(this)), new Runnable() {
            @Override public void run() {
                boolean v = !Settings.quickReplyVisible(SettingsActivity.this);
                Settings.setQuickReplyVisible(SettingsActivity.this, v);
                toast("快捷回复条：" + onOff(v));
                buildRows();
            }
        });
        // 内容可编辑（软件内问题 ③b）：聊天/评论共用这 4 个槽位
        row("快捷回复内容", QuickReplyTexts.summary(Settings.quickReplyTexts(this)), new Runnable() {
            @Override public void run() {
                QuickReplyEditDialog.show(SettingsActivity.this, new QuickReplyEditDialog.OnChanged() {
                    @Override public void onChanged() {
                        buildRows();   // 那一行的摘要跟着变
                    }
                });
            }
        });
        row("屏幕形状", SHAPE_NAMES[Settings.shapeMode(this)], new Runnable() {            @Override public void run() {
                int next = (Settings.shapeMode(SettingsActivity.this) + 1) % 3;
                Settings.setShapeMode(SettingsActivity.this, next);
                toast("屏幕形状：" + SHAPE_NAMES[next]
                        + (next == Settings.SHAPE_ROUND ? "（内容横向内缩，防四角裁切）" : ""));
                recreate();
            }
        });
        // 边距：点开调节面板（软件内问题 ②）——原来只能正向循环，2% 想回 0% 要点 6 次
        addPaddingRow(true);
        addPaddingRow(false);
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
        // 全屏手势（软件内问题 ⑤）：双指缩放/平移的开关，默认开。关掉后全屏里仍可单击显隐控件条。
        row("全屏手势", onOff(Settings.fullscreenGesture(this)) + "（全屏里双指缩放/拖动）",
                new Runnable() {
            @Override public void run() {
                boolean v = !Settings.fullscreenGesture(SettingsActivity.this);
                Settings.setFullscreenGesture(SettingsActivity.this, v);
                toast("全屏手势：" + onOff(v));
                buildRows();
            }
        });
        row("表冠滚动", onOff(Settings.rotaryEnabled(this))
                + (Settings.rotaryEnabled(this) ? "" : "（有的表本来就能滚，抢事件会双重滚）"), new Runnable() {
            @Override public void run() {
                boolean v = !Settings.rotaryEnabled(SettingsActivity.this);
                Settings.setRotaryEnabled(SettingsActivity.this, v);
                toast("表冠滚动：" + onOff(v));
                buildRows();
            }
        });
        if (Settings.rotaryEnabled(this)) {
            row("表冠灵敏度", sensitivityLabel(), new Runnable() {
                @Override public void run() {
                    float v = nextSensitivity(Settings.rotarySensitivity(SettingsActivity.this));
                    Settings.setRotarySensitivity(SettingsActivity.this, v);
                    toast("表冠灵敏度：" + v + "x");
                    buildRows();
                }
            });
        }
        actionRow("清除「看过」记录", "重置后推荐可能重复出现旧视频", new Runnable() {
            @Override public void run() {
                SeenStore.clear(SettingsActivity.this);
                toast("已清除看过记录");
            }
        });
        // 两个账本职责不同（SeenStore=去重、HistoryStore=给人看），清除入口也得各给一个；
        // 只有"清看过"的话，用户想删浏览隐私就无从下手，而误清去重账本又会集体回炉
        actionRow("清除浏览记录", "清掉「我的」页那份看过列表（不影响推荐去重）", new Runnable() {
            @Override public void run() {
                com.dywatch.app.feed.HistoryStore.clear(SettingsActivity.this);
                toast("已清除浏览记录");
            }
        });
        // 老内核上私信 JS 会被就地改写并缓存；规则升级或怀疑改坏了时，清一下就会重新改写。
        // 内核够新的设备这里清了也没副作用（本来就没缓存）。
        actionRow("重建私信补丁缓存", "内核过旧时会改写抖音私信脚本；改坏了点这里重来", new Runnable() {
            @Override public void run() {
                new com.dywatch.app.chat.JsPatchCache(
                        new java.io.File(getCacheDir(), "js_patch")).clear();
                toast("已清除，下次进聊天会重新改写");
            }
        });
        actionRow("诊断日志", "WebView 版本 / 上次崩溃 / 最近运行记录", new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("诊断")
                        .setMessage("【WebView】" + webViewInfo()
                                + "\n【登录】" + (LoginManager.hasSession(SettingsActivity.this) ? "有会话" : "无")
                                + "\n【屏幕】" + (Settings.isRound(SettingsActivity.this) ? "圆屏" : "方屏")
                                + "  缩放 " + Settings.scale(SettingsActivity.this)
                                + "  边距 " + Settings.paddingHPercent(SettingsActivity.this)
                                + "/" + Settings.paddingVPercent(SettingsActivity.this) + "%"
                                + "\n【看过记录】" + com.dywatch.app.feed.SeenStore.size(SettingsActivity.this) + " 条"
                                + "\n【上次崩溃】\n" + CrashShield.lastCrash(SettingsActivity.this)
                                + "\n【最近日志】\n" + AppLog.tail(25))
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("免责声明", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                com.dywatch.app.util.Disclaimer.show(SettingsActivity.this);
                            }
                        })
                        .show();
            }
        });
        actionRow("关于腕上音符", "版本 / GitHub / 作者主页 / 免责声明", new Runnable() {
            @Override
            public void run() {
                startActivity(new android.content.Intent(SettingsActivity.this,
                        AboutActivity.class));
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
                Settings.setFontScale(SettingsActivity.this, 1.0f);
                Settings.setShapeMode(SettingsActivity.this, Settings.SHAPE_AUTO);
                Settings.setPaddingPercent(SettingsActivity.this, 0, 0);
                Settings.setQualityMode(SettingsActivity.this, Settings.Q_SAVE);
                Settings.setCommentAutoLoad(SettingsActivity.this, true);
                Settings.setKeepScreenOn(SettingsActivity.this, true);
                Settings.setRotaryEnabled(SettingsActivity.this, false);
                Settings.setRotarySensitivity(SettingsActivity.this, 1.0f);
                // 快捷回复：默认关闭 + 文案回默认（软件内问题 ③a/③b 后"默认"就是这个意思）
                Settings.setQuickReplyVisible(SettingsActivity.this, false);
                Settings.resetQuickReplyTexts(SettingsActivity.this);
                DouyinApi.sQuality = Settings.Q_SAVE;
                toast("已恢复默认");
                recreate();
            }
        });
        // 版本号从包信息读 —— 原来这里硬编码 "v0.1.0"，而主屏那行早就改成读包版本了，
        // 于是设置页一直挂着 0.1.0（正是主屏注释里说要避免的"手写值随发版漂移"）。
        infoRow("腕上音符 v" + versionName()
                + "\n与抖音官方无关 · 仅供个人学习 · 风险自负");
    }

    /**
     * 边距行 → 点开调节面板（软件内问题 ②，2026-10-01）。
     *
     * 三条要点：
     *  ① 步进 1%、范围 0~30%——上限跟 `Settings.clampPercent` 对齐（原来 UI 只到 14，存储允许 30，
     *     两边不一致）；1% ≈ 3.7px（372px 宽），肉眼可见。
     *  ② 面板每次改值都回调到这里：先落盘 → 再 `applyPageInsets()` 重放内缩（当场内缩/展开），
     *     最后刷新本行文字（不再需要 recreate()，页面动画也不闪）。
     *  ③ 行文字格式与其它行一致（"标签\n值"），面板关掉后设置页显示的就是当前真值。
     */
    private void addPaddingRow(final boolean horizontal) {
        final String label = horizontal ? "横向边距" : "纵向边距";
        final TextView tv = row(label, percentLabel(paddingOf(horizontal)), null);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DyAdjustDialog.show(SettingsActivity.this, label, paddingOf(horizontal),
                        1, 0, 30, "%", new DyAdjustDialog.OnValueChange() {
                            @Override
                            public void onChange(int value) {
                                int h = horizontal ? value : Settings.paddingHPercent(SettingsActivity.this);
                                int vv = horizontal ? Settings.paddingVPercent(SettingsActivity.this) : value;
                                Settings.setPaddingPercent(SettingsActivity.this, h, vv);
                                applyPageInsets();                                  // 实时预览
                                tv.setText(label + "\n" + percentLabel(value));
                            }
                        });
            }
        });
    }

    private int paddingOf(boolean horizontal) {
        return horizontal ? Settings.paddingHPercent(this) : Settings.paddingVPercent(this);
    }

    /** 读本机包版本号（与 MainActivity.versionName 同一口径：都以包信息为准） */
    private String versionName() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** WebView 运行时版本：拉流/渲染类问题第一手要看的（原先只在主屏诊断里有） */
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

    private String qualityHint() {
        int q = Settings.qualityMode(this);
        return q == Settings.Q_SAVE ? "540p" : (q == Settings.Q_BALANCED ? "720p" : "1080p");
    }

    /** 边距档位提示：0 就是铺满（真正的档位/步进已移到调节面板，见 addPaddingRow） */
    private static String percentLabel(int v) {
        return v == 0 ? "0%（铺满）" : v + "%";
    }

    private static final float[] SENSITIVITY = {0.5f, 1.0f, 2.0f, 3.0f};

    private static float nextSensitivity(float cur) {
        for (int i = 0; i < SENSITIVITY.length; i++) {
            if (Math.abs(SENSITIVITY[i] - cur) < 0.01f) return SENSITIVITY[(i + 1) % SENSITIVITY.length];
        }
        return 1.0f;
    }

    private String sensitivityLabel() {
        return Settings.rotarySensitivity(this) + "x（转一格滚多少）";
    }

    private static String onOff(boolean b) { return b ? "开" : "关"; }

    private static int index(float[] arr, float v) {
        for (int i = 0; i < arr.length; i++) if (Math.abs(arr[i] - v) < 0.01f) return i;
        return 1;
    }

    /** 普通设置行；onClick 为 null 时只建行不接线（边距行要自己拿到 TextView 做实时刷新） */
    private TextView row(String label, String value, Runnable onClick) {
        return addBase(label + "\n" + value, onClick);
    }

    private void actionRow(String label, String sub, Runnable onClick) {
        addBase(label + "\n" + sub, onClick);
    }

    private void infoRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.t_caption));
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_faint));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(14), dp(12), dp(14));
        mRoot.addView(tv);
    }

    private TextView addBase(String text, final Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.t_body));
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setMinimumHeight(dp(48));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setBackgroundResource(R.drawable.bg_settings_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        tv.setLayoutParams(lp);
        if (onClick != null) {
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onClick.run(); }
            });
        }
        mRoot.addView(tv);
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
