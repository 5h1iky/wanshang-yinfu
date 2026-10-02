package com.dywatch.app.feed;

// 看过历史（给「我的」页展示用）。
//
// 为什么不复用 SeenStore：两者职责不同——
//   SeenStore 是 feed 去重的**账本**，只存 aweme_id，上限 1200，为的是"别重复推"；
//   HistoryStore 是给**人看的**记录，要带标题/封面才能显示，条数也不用那么多。
// 混在一起会让"清去重账本"变成"清观看历史"，反之亦然（账本清了会集体回炉，很痛）。
//
// 存储：JSON 数组字符串（按时间倒序，最新在前），LRU 上限 CAP 条。
// 用 JSON 而不是自己拼分隔符——标题里什么字符都可能有（换行、竖线、emoji），
// 自己拼迟早被标题里的分隔符咬到。
//
// ⚠️ 2026-10-02（代码审计 M16）：加进程内镜像 + 落盘挪到后台线程。
// 老实现每次"播放即记账"都在主线程上做：读 100~180KB JSON 串 → 全量 parse 成对象数组
// → 重拼 JSON 串 → 写回。而它就在 startPlay() 的换条路径上（用户滑一屏就要付好几次）。
// 现在：内存里保序存一份 Entry 列表，记账只改内存；序列化与落盘交给 AsyncDisk 单线程。

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {

    private static final String PREF = "watch_history";
    private static final String KEY = "items_v1";
    /** 手表存储与内存都有限，看过留 300 条足够回看 */
    private static final int CAP = 300;

    /** 进程内镜像（最新在前）。null = 尚未从磁盘读过 */
    private static List<Entry> sCache;

    private HistoryStore() {}

    /** 一条看过记录 */
    public static class Entry {
        public final String awemeId;
        public final String title;
        public final String coverUrl;
        public final long timeMs;

        public Entry(String awemeId, String title, String coverUrl, long timeMs) {
            this.awemeId = awemeId == null ? "" : awemeId;
            this.title = title == null ? "" : title;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
            this.timeMs = timeMs;
        }
    }

    /** 播放即记账：同一条重复看会挪到最前（最新） */
    public static void mark(Context ctx, String awemeId, String title, String coverUrl) {
        if (awemeId == null || awemeId.isEmpty()) return;
        final Context app = app(ctx);
        synchronized (HistoryStore.class) {
            List<Entry> list = cacheList(app);
            // 已存在就先删（后面重新插到头部），避免重复
            for (int i = 0; i < list.size(); i++) {
                if (awemeId.equals(list.get(i).awemeId)) { list.remove(i); break; }
            }
            list.add(0, new Entry(awemeId, title, coverUrl, System.currentTimeMillis()));
            while (list.size() > CAP) list.remove(list.size() - 1);
        }
        persistAsync(app);
    }

    /** 返回快照副本：调用方（「我的」页）会拿去渲染，不能让它看见后续改动 */
    public static List<Entry> read(Context ctx) {
        synchronized (HistoryStore.class) {
            return new ArrayList<>(cacheList(ctx));
        }
    }

    public static void clear(Context ctx) {
        final Context app = app(ctx);
        synchronized (HistoryStore.class) {
            sCache = new ArrayList<>();
        }
        try {
            prefs(app).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    public static int size(Context ctx) {
        synchronized (HistoryStore.class) {
            return cacheList(ctx).size();
        }
    }

    private static List<Entry> cacheList(Context ctx) {
        if (sCache == null) sCache = load(ctx);
        return sCache;
    }

    private static List<Entry> load(Context ctx) {
        List<Entry> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            String raw = prefs(ctx).getString(KEY, "");
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optString("id"), o.optString("t"),
                        o.optString("c"), o.optLong("ts")));
            }
        } catch (Throwable ignored) {
            // 解析坏就当空，别让历史页崩掉
        }
        return out;
    }

    private static void persistAsync(final Context ctx) {
        com.dywatch.app.util.AsyncDisk.run(new Runnable() {
            @Override
            public void run() {
                JSONArray arr = new JSONArray();
                try {
                    synchronized (HistoryStore.class) {
                        for (Entry e : cacheList(ctx)) {
                            JSONObject o = new JSONObject();
                            o.put("id", e.awemeId);
                            o.put("t", e.title);
                            o.put("c", e.coverUrl);
                            o.put("ts", e.timeMs);
                            arr.put(o);
                        }
                    }
                } catch (Throwable ignored) {
                    return;
                }
                try {
                    prefs(ctx).edit().putString(KEY, arr.toString()).apply();
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
