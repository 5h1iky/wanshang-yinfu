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
 */
public final class SeenStore {

    private static final String PREF = "feed_seen";
    /** 有序版键名。不复用旧的 "ids"：那是 StringSet，按 String 读会 ClassCastException。 */
    private static final String KEY = "ids_v2";
    private static final int CAP = 1200;

    private SeenStore() {}

    public static boolean isSeen(Context ctx, String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return false;
        return read(ctx).contains(awemeId);
    }

    /** 批量记账；重复记账会把该条挪到最新端（LRU 语义），超上限只淘汰最旧的 */
    public static synchronized void markSeen(Context ctx, Iterable<String> ids) {
        List<String> list = readList(ctx);
        applyMark(list, ids, CAP);
        StringBuilder sb = new StringBuilder(list.size() * 21);
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(list.get(i));
        }
        prefs(ctx).edit().remove("ids").putString(KEY, sb.toString()).apply();
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

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    /** 当前记账条数（诊断/测试用） */
    public static int size(Context ctx) {
        return readList(ctx).size();
    }

    private static Set<String> read(Context ctx) {
        return new HashSet<>(readList(ctx));
    }

    private static List<String> readList(Context ctx) {
        String raw = prefs(ctx).getString(KEY, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split("\n")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
