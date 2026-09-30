package com.dywatch.app.net;

// 公告拉取（Cloudflare 三件之①，2026-09-27 方案）：主屏顶部细 banner 的数据来源。
//
// 通道策略（连通性实测驱动，详见 cloudflare/README.md）：
//   1) cdn.jsdelivr.net（GitHub 仓库文件走 CDN）—— 国内可用性最好（实测 615ms）
//   2) fastly.jsdelivr.net（同一份内容，备用节点）
//   3) dy-announce.sharkyline.workers.dev（Worker + KV，可达时改公告即时生效；本网络被污染不通）
// 全失败 → 返回 null，界面静默不显示（用户拍板："拉不到就不显示"）。
//
// 口径（用户拍板）：启动时拉一次 + 进设置页拉一次；主屏顶部细 banner；点击消失；
// 本地记 lastReadId；不弹窗不打断。
// 隐私：仅 GET 公开文件，不带任何身份信息。

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AnnounceApi {

    /** 候选端点（顺序即优先级；首个成功即返回） */
    public static final String[] ENDPOINTS = {
            "https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json",
            "https://fastly.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json",
            "https://dy-announce.sharkyline.workers.dev/announce",
    };

    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_DANGER = "danger";

    public static final class Announcement {
        public final String id;
        public final String level;   // info / warn / danger
        public final String title;
        public final String text;
        public final String link;
        public final long showUntil; // 0 = 永不过期

        public Announcement(String id, String level, String title, String text,
                            String link, long showUntil) {
            this.id = id;
            this.level = level;
            this.title = title;
            this.text = text;
            this.link = link;
            this.showUntil = showUntil;
        }
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    /**
     * 解析公告 JSON（纯函数，可 JVM 单测）。
     * 视为"无公告"的情况一律返回 null：非法 JSON / 非对象 / enabled=false / 缺 id 或 title /
     * showUntil 已过期 / 显式空 title。
     */
    public static Announcement parse(String json, long now) {
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (Exception e) {
            return null;
        }
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();

        // enabled 缺省视为 true（Worker 返回的对象不带这个字段）
        JsonElement enabled = o.get("enabled");
        if (enabled != null && !enabled.isJsonNull() && enabled.isJsonPrimitive()
                && enabled.getAsJsonPrimitive().isBoolean() && !enabled.getAsBoolean()) {
            return null;
        }

        String id = opt(o, "id");
        String title = opt(o, "title");
        if (id.isEmpty() || title.isEmpty()) return null;

        long showUntil = 0L;
        JsonElement su = o.get("showUntil");
        if (su != null && !su.isJsonNull() && su.isJsonPrimitive()) {
            try {
                showUntil = su.getAsLong();
            } catch (Exception ignored) {
            }
        }
        if (showUntil > 0 && now > showUntil) return null;

        String level = opt(o, "level");
        if (!LEVEL_WARN.equals(level) && !LEVEL_DANGER.equals(level)) level = LEVEL_INFO;

        String text = opt(o, "text");
        if (text.length() > 300) text = text.substring(0, 300) + "…";

        return new Announcement(id, level, title, text, opt(o, "link"), showUntil);
    }

    /** 是否该向用户展示（纯函数）：有内容 且 未被标记已读 且 未过期 */
    public static boolean shouldShow(Announcement a, String lastReadId, long now) {
        if (a == null) return false;
        if (a.id.equals(lastReadId)) return false;
        if (a.showUntil > 0 && now > a.showUntil) return false;
        return true;
    }

    /** 联网拉取：按端点顺序尝试；全部失败返回 null（静默） */
    public Announcement fetch() {
        long now = System.currentTimeMillis();
        for (String url : ENDPOINTS) {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "wanshang-yinfu")
                        .header("Accept", "application/json")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.code() == 204) {
                        // Worker 明确表示"当前无公告"——这是有效答案，不必再试其它端点
                        com.dywatch.app.util.AppLog.i("announce", "无公告（204）via " + host(url));
                        return null;
                    }
                    if (resp.code() != 200 || resp.body() == null) {
                        com.dywatch.app.util.AppLog.i("announce", "端点返回 " + resp.code()
                                + " via " + host(url));
                        continue;
                    }
                    Announcement a = parse(resp.body().string(), now);
                    com.dywatch.app.util.AppLog.i("announce", (a == null ? "无有效公告" : "拉到公告 id=" + a.id)
                            + " via " + host(url));
                    return a;
                }
            } catch (IOException e) {
                com.dywatch.app.util.AppLog.i("announce", "端点不可达 via " + host(url)
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        com.dywatch.app.util.AppLog.i("announce", "全部端点失败，静默不显示");
        return null;
    }

    private static String host(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static String opt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return "";
        try {
            return e.getAsString();
        } catch (Exception ex) {
            return "";
        }
    }
}
