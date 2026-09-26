package com.dywatch.app;

import android.app.Application;

import com.dywatch.app.util.AppLog;
import com.dywatch.app.util.CrashShield;

/** 全局初始化：日志 + 崩溃护盾（无 adb 环境的观测底座） */
public class DyApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
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
