package com.dywatch.app.util;

// 极简日志：无 adb 环境下的替代观测手段（用户拍照反馈时可截图/翻查）。
// 落盘 filesDir/app.log + 内存环形缓冲；不含敏感值（调用方自律：cookie/令牌一律不入参）。
//
// 2026-09-30 增补：**同时写一份到系统日志（android.util.Log）**。
// 原因：手表上私信报"失败"，但 App 自己的日志只落在私有目录里——release 包没有 run-as
// 读不出来，手表小屏翻诊断页也痛苦。写一份进 logcat 后，只要手表能连 adb，
// 就能在电脑上实时看（`adb logcat -s dywatch`），排查不再靠猜。
// 注意：logcat 可见性与 debuggable 无关，release 包同样能看到。

import android.content.Context;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class AppLog {

    /** logcat 过滤用的 tag：adb logcat -s dywatch */
    private static final String LOGCAT_TAG = "dywatch";

    private static final ArrayDeque<String> RING = new ArrayDeque<>();
    private static final int MAX = 200;
    private static Context sCtx;

    private AppLog() {}

    public static synchronized void init(Context ctx) {
        sCtx = ctx.getApplicationContext();
    }

    public static synchronized void i(String tag, String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + " [" + tag + "] " + msg;
        RING.addLast(line);
        while (RING.size() > MAX) RING.removeFirst();
        // 系统日志（让 adb logcat 能看到；失败不影响落盘）
        try {
            android.util.Log.i(LOGCAT_TAG, line);
        } catch (Throwable ignored) {
        }
        if (sCtx != null) {
            try (FileOutputStream fos = sCtx.openFileOutput("app.log", Context.MODE_APPEND)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        }
    }

    /** 最近 n 条日志（倒序拼接，给诊断页/错误提示用） */
    public static synchronized String tail(int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : RING) {
            if (RING.size() - i <= n) sb.append(s).append('\n');
            i++;
        }
        return sb.toString();
    }
}
