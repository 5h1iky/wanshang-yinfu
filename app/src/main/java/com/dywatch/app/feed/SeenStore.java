package com.dywatch.app.feed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 看过历史（feed 去重的跨会话账本）。
 * 背景：服务端推荐池对新号会回炉旧内容（实测：刷 1~12 条全是历史看过的），
 * 客户端必须自己记账过滤才能保证"每次刷到新面孔"。
 */
public final class SeenStore {

    private static final String PREF = "feed_seen";
    private static final String KEY = "ids";
    /** 上限保护：超了整体清空重开（老账本价值低，不值得无限膨胀） */
    private static final int CAP = 1200;

    private SeenStore() {}

    public static boolean isSeen(Context ctx, String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) return false;
        return prefs(ctx).getStringSet(KEY, new HashSet<String>()).contains(awemeId);
    }

    /** 批量记账（超上限自动清空重开） */
    public static void markSeen(Context ctx, Iterable<String> ids) {
        SharedPreferences sp = prefs(ctx);
        Set<String> set = new HashSet<>(sp.getStringSet(KEY, new HashSet<String>()));
        for (String id : ids) {
            if (id != null && !id.isEmpty()) set.add(id);
        }
        if (set.size() > CAP) {
            set.clear();
        }
        sp.edit().putStringSet(KEY, set).apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
