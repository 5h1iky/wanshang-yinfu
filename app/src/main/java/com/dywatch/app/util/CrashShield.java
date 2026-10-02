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

    /**
     * 崩溃记录的上限（2026-10-02，代码审计 M6）。
     *
     * 老实现 openFileOutput("crash.log", MODE_APPEND) 一路追加、**永不清理**：
     * 而崩溃恰恰是最容易反复发生的（比如启动即崩，用户会连点十几次图标），
     * 每次一份完整堆栈 —— 手表存储本来就紧张，崩溃循环还会让它越滚越大。
     * 现在到线上限就砍掉前半段（复用 AppLog.trim，按行对齐、不会切出乱码）。
     */
    private static final long MAX_CRASH_BYTES = 128 * 1024L;
    private static final long KEEP_CRASH_BYTES = 64 * 1024L;
    private static final String CRASH_FILE = "crash.log";

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
            // 先把待落盘的日志尾巴同步写完（2026-10-02：日志改成后台批量写之后，
            // 崩溃前最后几行可能还在队列里 —— 而那几行恰恰是最值钱的）。
            AppLog.flush();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String head = "==== 崩溃 " + stamp + " [" + t.getName() + "] ====\n";
            try (FileOutputStream fos = mCtx.openFileOutput(CRASH_FILE, Context.MODE_APPEND)) {
                fos.write((head + sw + "\n").getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
            AppLog.i("crash", "未捕获异常: " + e);
            try {
                java.io.File f = new java.io.File(mCtx.getFilesDir(), CRASH_FILE);
                if (f.length() > MAX_CRASH_BYTES) AppLog.trim(f, KEEP_CRASH_BYTES);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        if (mNext != null) mNext.uncaughtException(t, e);
    }

    /**
     * 读取上次崩溃记录（诊断页展示）。注意 minSdk 21：不能用 java.nio.file（API26+）。
     *
     * 2026-10-02：改成只读文件**尾部 4KB**。老实现把整个 crash.log 读进内存再取最后一段，
     * 而这个方法是诊断页在主线程调的 —— 文件涨到几百 KB 就是白白的卡顿。
     * 展示上限本来就是 1500 字符，读尾部足够。
     */
    public static String lastCrash(Context ctx) {
        java.io.RandomAccessFile raf = null;
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), CRASH_FILE);
            if (!f.exists()) return "(无崩溃记录)";
            long len = f.length();
            long from = Math.max(0, len - 4096);
            raf = new java.io.RandomAccessFile(f, "r");
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String all = new String(buf, "UTF-8");
            int idx = all.lastIndexOf("==== 崩溃");
            if (idx < 0) idx = 0;
            String tail = all.substring(idx);
            return tail.length() > 1500 ? tail.substring(0, 1500) : tail;
        } catch (Exception e) {
            return "(读取失败: " + e + ")";
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
