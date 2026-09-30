package com.dywatch.app.net;

// 公告拉取（Cloudflare 三件之①，2026-09-27 方案 / 2026-09-30 通道重构）：
// 主屏顶部细 banner 的数据来源。
//
// 通道策略（连通性 + 新鲜度双重实测驱动）：
//   1) api.github.com 的 contents API —— **每次都读仓库实时内容**（无 CDN 缓存），
//      实测国内可达（477ms）；公告要"改了立刻生效"，这一条最关键
//   2) cdn.jsdelivr.net（GitHub 文件走 CDN）—— 国内可用性好，但**分支→commit 映射有 12h 缓存**
//      （实测：purge 接口返回 finished 后仍吐旧内容，加查询串也无效）→ 只能当兜底
//   3) fastly.jsdelivr.net —— 同上，换节点
//   4) dy-announce.sharkyline.workers.dev（Worker + KV）—— 改 KV 即时生效，
//      但本网络 DNS 被污染不可达；对能连上的用户是最快通道，故保留但排在最后（避免拖慢）
// 全失败 → 返回 null，界面静默不显示（用户拍板："拉不到就不显示"）。
//
// 口径（用户拍板）：启动时拉一次 + 进设置页拉一次；主屏顶部细 banner；点击消失；
// 本地记 lastReadId；不弹窗不打断。
// 隐私：仅 GET 公开文件/接口，不带任何身份信息。

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class AnnounceApi {

    /** 响应形态 */
    private static final int KIND_PLAIN = 0;          // 直接就是公告 JSON
    private static final int KIND_GH_CONTENTS = 1;    // GitHub contents API：{content: base64}

    public static final class Endpoint {
        public final String name;
        public final String url;
        final int kind;
        /** 该端点要的 Accept：gh-api 必须用 application/json 才会拿到 {content:base64} 包装
         *  （实测：带 vnd.github.raw+json 时 GitHub 直接返回裸文件 → 解析器找不到 content 字段） */
        final String accept;

        Endpoint(String name, String url, int kind, String accept) {
            this.name = name;
            this.url = url;
            this.kind = kind;
            this.accept = accept;
        }
    }

    /** 候选端点（顺序即优先级：新鲜度优先，其次可达性；单测锁死顺序防手滑） */
    public static final Endpoint[] ENDPOINTS = {
            new Endpoint("gh-api",
                    "https://api.github.com/repos/5h1iky/wanshang-yinfu/contents/announce.json",
                    KIND_GH_CONTENTS, "application/json"),
            new Endpoint("jsdelivr",
                    "https://cdn.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json",
                    KIND_PLAIN, "application/json"),
            new Endpoint("jsdelivr-fastly",
                    "https://fastly.jsdelivr.net/gh/5h1iky/wanshang-yinfu@main/announce.json",
                    KIND_PLAIN, "application/json"),
            new Endpoint("worker",
                    "https://dy-announce.sharkyline.workers.dev/announce",
                    KIND_PLAIN, "application/json"),
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .build();

    /**
     * 解析公告 JSON（纯函数，可 JVM 单测）。
     * 视为"无公告"的情况一律返回 null：非法 JSON / 非对象 / enabled=false / 缺 id 或 title /
     * showUntil 已过期。
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
        return parseObject(o, now);
    }

    /** GitHub contents API 的响应 → 公告（纯函数）：{"content":"<base64>","encoding":"base64"} */
    public static Announcement parseGithubContents(String json, long now) {
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (Exception e) {
            return null;
        }
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String b64 = opt(o, "content");
        if (b64.isEmpty()) {
            // 兼容 GitHub 的内容协商：若响应直接就是公告 JSON（raw 变体），按公告解析
            return parseObject(o, now);
        }
        String decoded;
        try {
            decoded = new String(decodeBase64(b64), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
        return parse(decoded, now);
    }

    /**
     * 极简 base64 解码（纯 Java，标准字母表，忽略空白/换行/未知字符）。
     * 为什么不用 android.util.Base64：它会让本函数的 JVM 单测直接 "Stub!" 失败；
     * 而 java.util.Base64 要 API 26（本项目 minSdk 21）。
     */
    static byte[] decodeBase64(String s) {
        final String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int[] map = new int[128];
        java.util.Arrays.fill(map, -1);
        for (int i = 0; i < alpha.length(); i++) map[alpha.charAt(i)] = i;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (c >= 128) continue;
            int v = map[c];
            if (v < 0) continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static Announcement parseObject(JsonObject o, long now) {
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
        for (Endpoint ep : ENDPOINTS) {
            try {
                Request req = new Request.Builder()
                        .url(ep.url)
                        .header("User-Agent", "wanshang-yinfu")
                        .header("Accept", ep.accept)
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.code() == 204) {
                        // Worker 明确表示"当前无公告"——有效答案，不必再试其它端点
                        com.dywatch.app.util.AppLog.i("announce", "无公告（204）via " + ep.name);
                        return null;
                    }
                    if (resp.code() == 404 && ep.kind == KIND_GH_CONTENTS) {
                        // 仓库里确实没有 announce.json —— 也是明确答案
                        com.dywatch.app.util.AppLog.i("announce", "无公告（404）via " + ep.name);
                        return null;
                    }
                    if (resp.code() != 200 || resp.body() == null) {
                        com.dywatch.app.util.AppLog.i("announce", "端点 " + ep.name
                                + " 返回 " + resp.code() + "，试下一个");
                        continue;
                    }
                    String body = resp.body().string();
                    Announcement a = (ep.kind == KIND_GH_CONTENTS)
                            ? parseGithubContents(body, now)
                            : parse(body, now);
                    com.dywatch.app.util.AppLog.i("announce", (a == null ? "无有效公告" : "拉到公告 id=" + a.id)
                            + " via " + ep.name);
                    return a;
                }
            } catch (IOException e) {
                com.dywatch.app.util.AppLog.i("announce", "端点不可达 via " + ep.name
                        + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        com.dywatch.app.util.AppLog.i("announce", "全部端点失败，静默不显示");
        return null;
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
