package com.dywatch.app.util;

// 单线程落盘队列（2026-10-02，代码审计 M16）。
//
// 背景：刷视频时"换一条"要付的磁盘动作全在主线程上——AppLog 每条日志一次
// 文件长度查询 + open/write/close，SeenStore/HistoryStore 每次记账把整份
// JSON/账本（几十到上百 KB）重新拼接后写回 SharedPreferences。
// 手表 CPU 弱、存储慢，这些开销直接落在滑动与首帧上（用户基线是"连刷 20 条不卡"）。
//
// 这里只做一件事：给"读-改-写整份状态"这类**结果幂等、晚几百毫秒无感**的落盘
// 提供一个后台单线程。为什么必须是单线程：这些 store 的语义是"整份覆盖写"，
// 并发写会让后写的旧快照盖掉先写的新快照。单线程 + FIFO 天然有序。
//
// ⚠️ 它不保证"崩溃前一定落盘"——和 SharedPreferences.apply() 的语义一致
// （apply 本来就是异步磁盘写）。真要保命的写入（崩溃记录）走同步路径。

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AsyncDisk {

    private static volatile ExecutorService sExec;

    private AsyncDisk() {}

    /** 提交一次落盘任务；线程池懒建（守护线程，不拖住进程退出） */
    public static void run(Runnable task) {
        if (task == null) return;
        try {
            ExecutorService e = sExec;
            if (e == null) {
                synchronized (AsyncDisk.class) {
                    if (sExec == null) {
                        sExec = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "dywatch-disk");
                                t.setDaemon(true);
                                return t;
                            }
                        });
                    }
                    e = sExec;
                }
            }
            e.execute(task);
        } catch (Throwable ignored) {
            // 线程池都起不来（极端内存压力）也不能让调用方崩：这次落盘丢了就丢了，
            // 下一次记账会写下更新更全的快照。
        }
    }
}
