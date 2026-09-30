package com.dywatch.app.net;

// 公告本地缓存与已读记账（Cloudflare 三件之① 的存储侧）。
//
// 为什么要有缓存：用户拍板的频率是"启动时拉一次 + 进设置页拉一次"，但主屏 onResume 会多次触发——
// 缓存让"设置页拉到的新公告"回到主屏立刻可见，也避免弱网下反复打网络。
//
// 存两样：
//   last_read_id —— 点击 banner 关闭后记 id，同一条不再提醒（用户拍板口径）
//   cached_json  —— 最近一次成功拉到的公告原文（无公告时清空）

import android.content.Context;
import android.content.SharedPreferences;

public final class AnnounceStore {

    private static final String PREF = "announce";
    private static final String KEY_LAST_READ = "last_read_id";
    private static final String KEY_CACHED = "cached_json";
    private static final String KEY_CACHED_AT = "cached_at";

    /** 进程内是否已自动拉过（主屏多次 onResume 不重复打网络；设置页显式刷新不受此限） */
    private static volatile boolean sFetchedThisProcess;

    private AnnounceStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String lastReadId(Context c) {
        return sp(c).getString(KEY_LAST_READ, "");
    }

    /** 用户关闭某条公告 → 记已读 */
    public static void markRead(Context c, String id) {
        if (id == null || id.isEmpty()) return;
        sp(c).edit().putString(KEY_LAST_READ, id).apply();
    }

    /** 主屏要展示的公告（来自缓存 + 已读/过期判定）；没有则 null */
    public static AnnounceApi.Announcement pending(Context c) {
        String json = sp(c).getString(KEY_CACHED, "");
        if (json == null || json.isEmpty()) return null;
        AnnounceApi.Announcement a = AnnounceApi.parse(json, System.currentTimeMillis());
        if (!AnnounceApi.shouldShow(a, lastReadId(c), System.currentTimeMillis())) return null;
        return a;
    }

    private static void save(Context c, AnnounceApi.Announcement a) {
        SharedPreferences.Editor e = sp(c).edit().putLong(KEY_CACHED_AT, System.currentTimeMillis());
        if (a == null) {
            e.remove(KEY_CACHED).apply();   // 无公告 → 清缓存（别把旧公告一直挂着）
        } else {
            e.putString(KEY_CACHED, toJson(a)).apply();
        }
    }

    private static String toJson(AnnounceApi.Announcement a) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("id", a.id);
        o.addProperty("level", a.level);
        o.addProperty("title", a.title);
        o.addProperty("text", a.text);
        o.addProperty("link", a.link);
        o.addProperty("showUntil", a.showUntil);
        o.addProperty("enabled", true);
        return o.toString();
    }

    /**
     * 后台拉一次公告并落缓存；完成后在主线程回调 done（可为 null）。
     * @param force true=忽略"进程内已拉过"标记（设置页用）；false=进程内只自动拉一次（主屏用）
     */
    public static void refreshInBackground(final Context ctx, final boolean force, final Runnable done) {
        if (!force && sFetchedThisProcess) {
            if (done != null) done.run();
            return;
        }
        sFetchedThisProcess = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AnnounceApi.Announcement a = new AnnounceApi().fetch();
                    save(app, a);
                } catch (Throwable t) {
                    com.dywatch.app.util.AppLog.i("announce", "拉取异常：" + t);
                }
                if (done != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
                }
            }
        }, "announce-fetch").start();
    }

    /** 仅供测试/调试：重置进程内标记 */
    static void resetFetchedFlagForTest() {
        sFetchedThisProcess = false;
    }
}
