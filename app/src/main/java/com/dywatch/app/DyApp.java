package com.dywatch.app;

import android.app.Application;

import com.dywatch.app.util.AppLog;
import com.dywatch.app.util.CrashShield;

/** 全局初始化：日志 + 崩溃护盾（无 adb 环境的观测底座） */
public class DyApp extends Application {

    /**
     * API 20 及以下没有系统级 multidex：必须在 super 之后、任何业务初始化之前
     * 把副 dex 装进来，否则 Dalvik 找不到副 dex 里的类（典型症状是启动就 NoClassDefFoundError）。
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        androidx.multidex.MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 全站配色按深色设计（bg_base #0E1418 等），主题是 Material3.DayNight——
        // 跟随系统会在浅色模式下把默认字色换成深色，配深色背景直接看不见。固定夜间。
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        AppLog.init(this);
        // 老系统的 TLS 兜底（API<21 才生效，新系统直接 return）——
        // 不装的话 4.4 上连抖音的 HTTPS 都握不上手，表现为"一条都拉不到"
        com.dywatch.app.util.TlsCompat.install(this);
        CrashShield.install(this);
        // 会话 cookie 回写 WebView CookieStore（进程重启丢 cookie 的修复，见 LoginManager v2 注释）
        com.dywatch.app.login.LoginManager.restoreToCookieManager(this);
        // debug 包开启 WebView 远程调试（CDP）：真机 DOM 校准聊天选择器用；release 包自动关闭
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
            AppLog.i("app", "WebView CDP 调试已开启（仅 debug 包）");
        }
        AppLog.i("app", "应用启动");
    }
}
