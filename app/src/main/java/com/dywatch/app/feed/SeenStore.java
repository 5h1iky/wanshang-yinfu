package com.dywatch.app.feed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 看过历史（feed 去重的跨会话账本）。
 *
 * 存成「按新旧排序、\n 分隔」的单个字符串而不是 StringSet：StringSet 无序，做不了
 * "淘汰最旧的"这件事。上限 CAP 条，超了从头（最旧）丢，而不是一键清空——
 * 清空等于账本作废，用户会立刻遭遇一次集体回炉。
 *
 * ⚠️ 2026-10-02（代码审计 M16）：加了**进程内镜像** + 落盘改到后台线程。
 * 老实现每次 isSeen() 都把整串（上限 1200 个 id ≈ 24KB）split 成 List，
 * 而刷视频页换一条要走「1 次 markSeen + applyNewItems 里逐条 isSeen」——
 * 一屏十几条就是十几次 24KB 的字符串切分，全在主线程。
 * 现在：内存里保序保存一份，isSeen 是 O(1) 查表；写盘交给 AsyncDisk 单线程。
 * 代价：进程被强杀时最后几次记账可能没落盘（与 SharedPreferences.apply() 的语义一致，
 * 没有变得更差）——最坏后果是那几条视频下次还会出现一次。
 */
public final class SeenStore {

    private static final String PREF = "feed_seen";
    /** 有序版键名。不复用旧的 "ids"：那是 StringSet，按 String 读会 ClassCastException。 */
    private static final String KEY = "ids_v2";
    private static final int CAP = 1200;

    /** 进程内镜像（最新在末尾）。null = 尚未从磁盘读过 */
    private static List<String> sCache;
    /** isSeen 用的查表集合，与 sCache 同步重建 */
    private static Set<String> sLookup;

    private SeenStore() {}

    public static boolean isSeen(Context ctx, String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return false;
        synchronized (SeenStore.class) {
            return lookup(ctx).contains(awemeId);
        }
    }

    /** 批量记账；重复记账会把该条挪到最新端（LRU 语义），超上限只淘汰最旧的 */
    public static void markSeen(Context ctx, Iterable<String> ids) {
        final Context app = app(ctx);
        synchronized (SeenStore.class) {
            applyMark(cacheList(app), ids, CAP);
            rebuildLookup();
        }
        persistAsync(app);
    }

    public static void clear(Context ctx) {
        final Context app = app(ctx);
        synchronized (SeenStore.class) {
            sCache = new ArrayList<>();
            rebuildLookup();
        }
        try {
            prefs(app).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    /** 当前记账条数（诊断/测试用） */
    public static int size(Context ctx) {
        synchronized (SeenStore.class) {
            return cacheList(ctx).size();
        }
    }

    /**
     * LRU 本体：就地改 list——已存在的 id 挪到末尾（算"最近又看过"），
     * 超过 cap 从头部（最旧）淘汰。抽成纯函数是为了能在 JVM 里直接测淘汰行为，
     * 不必造 Context。
     */
    static void applyMark(List<String> list, Iterable<String> ids, int cap) {
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            list.remove(id);
            list.add(id);
        }
        while (list.size() > cap) list.remove(0);
    }

    private static Set<String> lookup(Context ctx) {
        if (sLookup == null) rebuildLookup(ctx);
        return sLookup;
    }

    private static void rebuildLookup() {
        sLookup = new HashSet<>(sCache);
    }

    private static void rebuildLookup(Context ctx) {
        sLookup = new HashSet<>(cacheList(ctx));
    }

    private static List<String> cacheList(Context ctx) {
        if (sCache == null) sCache = load(ctx);
        return sCache;
    }

    private static List<String> load(Context ctx) {
        List<String> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            String raw = prefs(ctx).getString(KEY, "");
            if (raw == null || raw.isEmpty()) return out;
            for (String s : raw.split("\n")) {
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 整份覆盖写；单线程队列保证"后提交的更新快照"不会先落盘 */
    private static void persistAsync(final Context ctx) {
        com.dywatch.app.util.AsyncDisk.run(new Runnable() {
            @Override
            public void run() {
                final String snapshot;
                synchronized (SeenStore.class) {
                    StringBuilder sb = new StringBuilder(cacheList(ctx).size() * 21);
                    List<String> list = cacheList(ctx);
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(list.get(i));
                    }
                    snapshot = sb.toString();
                }
                try {
                    prefs(ctx).edit().remove("ids").putString(KEY, snapshot).apply();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static Context app(Context ctx) {
        if (ctx == null) return null;
        Context a = ctx.getApplicationContext();
        return a == null ? ctx : a;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
