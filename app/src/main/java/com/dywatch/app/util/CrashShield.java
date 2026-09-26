package com.dywatch.app.util;

// 崩溃护盾：无 adb 环境下把未捕获异常写盘 + 留痕，重启后可在诊断页看到"上次崩溃"。
// 不吞异常（仍交给系统默认处理器，保证系统行为一致）。

import android.content.Context;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CrashShield implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler mNext;
    private final Context mCtx;

    private CrashShield(Context ctx, Thread.UncaughtExceptionHandler next) {
        mCtx = ctx;
        mNext = next;
    }

    public static void install(Context ctx) {
        Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        if (def instanceof CrashShield) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashShield(ctx.getApplicationContext(), def));
        AppLog.i("crash", "CrashShield 已安装");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String head = "==== 崩溃 " + stamp + " [" + t.getName() + "] ====\n";
            try (FileOutputStream fos = mCtx.openFileOutput("crash.log", Context.MODE_APPEND)) {
                fos.write((head + sw + "\n").getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
            AppLog.i("crash", "未捕获异常: " + e);
        } catch (Throwable ignored) {
        }
        if (mNext != null) mNext.uncaughtException(t, e);
    }

    /** 读取上次崩溃记录（诊断页展示）。注意 minSdk 21：不能用 java.nio.file（API26+） */
    public static String lastCrash(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "crash.log");
            if (!f.exists()) return "(无崩溃记录)";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            fis.close();
            String all = new String(bos.toByteArray(), "UTF-8");
            int idx = all.lastIndexOf("==== 崩溃");
            if (idx < 0) idx = 0;
            String tail = all.substring(idx);
            return tail.length() > 1500 ? tail.substring(0, 1500) : tail;
        } catch (Exception e) {
            return "(读取失败: " + e + ")";
        }
    }
}
