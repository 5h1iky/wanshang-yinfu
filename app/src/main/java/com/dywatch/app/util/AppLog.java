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

    /**
     * 落盘日志的上限（2026-10-01，用户问"日志会不会一直存着、应用不会变大吗"——会，已修）。
     *
     * 老实现是 `openFileOutput(MODE_APPEND)` 一路追加，**永不清理**：内存环形缓冲有 200 条上限，
     * 文件却没有。引擎每次进私信页都会刷几十上百行（含长 URL），手表存储又紧张，于是只涨不降。
     * 现在：超过 {@link #MAX_FILE_BYTES} 就砍掉前半段、只留最后 {@link #KEEP_FILE_BYTES}
     * （一次砍一半，避免每写一行都裁一次文件）。诊断页读的是内存环形缓冲，不受影响。
     */
    private static final long MAX_FILE_BYTES = 256 * 1024L;
    private static final long KEEP_FILE_BYTES = 128 * 1024L;
    private static final String LOG_FILE = "app.log";

    private AppLog() {}

    public static synchronized void init(Context ctx) {
        sCtx = ctx.getApplicationContext();
        // 启动时就先看一次（上次运行可能已经涨过线）
        try {
            java.io.File f = new java.io.File(sCtx.getFilesDir(), LOG_FILE);
            if (f.length() > MAX_FILE_BYTES) trim(f, KEEP_FILE_BYTES);
        } catch (Throwable ignored) {
        }
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
            try {
                java.io.File f = new java.io.File(sCtx.getFilesDir(), LOG_FILE);
                if (f.length() > MAX_FILE_BYTES) trim(f, KEEP_FILE_BYTES);
            } catch (Throwable ignored) {
            }
            try (FileOutputStream fos = sCtx.openFileOutput(LOG_FILE, Context.MODE_APPEND)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 把日志文件砍到只剩最后 keepBytes 字节，**按行对齐**（丢掉第一行那个被切一半的残行，
     * 否则 UTF-8 多字节字符会被截断成乱码）。纯 java.io，不依赖 Android → 可 JVM 单测。
     *
     * @return 是否真的裁剪过
     */
    static boolean trim(java.io.File f, long keepBytes) {
        if (f == null || !f.isFile() || keepBytes <= 0) return false;
        long len = f.length();
        if (len <= keepBytes) return false;
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "rw");
            long start = len - keepBytes;
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String tail = new String(buf, "UTF-8");
            int nl = tail.indexOf('\n');
            if (nl >= 0) {
                tail = tail.substring(nl + 1);   // 丢掉被切一半的那行
            }
            byte[] out = tail.getBytes("UTF-8");
            raf.setLength(0);
            raf.seek(0);
            raf.write(out);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
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
