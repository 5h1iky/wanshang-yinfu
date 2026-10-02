package com.danikula.videocache;

import android.util.Log;

public final class Logger {

    private static final String TAG = "VideoCache";

    private static boolean IS_DEBUG = false;

    public static void setDebug(boolean isDebug) {
        IS_DEBUG = isDebug;
    }

    public static void debug(String msg) {
        if (IS_DEBUG) {
            Log.d(TAG, msg);
        }
    }

    public static void info(String msg) {
        if (IS_DEBUG) {
            Log.i(TAG, msg);
        }
    }

    // ⚠️ 本地修改（2026-10-02，代码审计 M1）：warn/error **不再受 IS_DEBUG 开关控制**。
    //
    // 原实现四个级别全部 `if (IS_DEBUG)`，而 IS_DEBUG 默认 false、全工程没有任何地方
    // 调 setDebug —— 于是这个库的失败信号（缓存写失败、LRU 淘汰删不掉、请求非法、
    // 源站连不上）**一条都不会输出**。出问题时用户看到的是"这个视频放不出来"，
    // 而日志里干干净净，这正是本项目最怕的黑盒。
    // 现在 warn/error 恒输出到 logcat（tag=VideoCache），debug/info 仍按开关走。
    // 查看： adb logcat -s VideoCache
    public static void warn(String msg) {
        try {
            Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String msg) {
        try {
            Log.e(TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
