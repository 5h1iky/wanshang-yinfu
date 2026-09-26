package com.dywatch.app.net;

// 更新检查（M4）：GitHub Releases API 拉 latest 版本与本机比对。
// 纯解析逻辑可 JVM 单测；网络失败静默（手表弱网易容忍）；endpoint 可换 Cloudflare Worker 兜底（M6）。
// 隐私：仅 GET 公开 API，不带任何身份信息。

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class UpdateChecker {

    /** 发布仓库（M5 分发时定稿；先占位） */
    public static final String RELEASES_API =
            "https://api.github.com/repos/5h1iky/wanshang-yinfu/releases/latest";

    public static final class UpdateInfo {
        public final String version;   // 如 "0.2.0"
        public final String notes;     // 更新说明（截断）
        public final String apkUrl;    // APK 下载地址（可空）
        public final String htmlUrl;   // 发布页（可空）

        public UpdateInfo(String version, String notes, String apkUrl, String htmlUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.htmlUrl = htmlUrl;
        }
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    /** 解析 GitHub Releases latest JSON；纯函数，非法输入返回 null */
    public static UpdateInfo parseRelease(String json) {
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (Exception e) {
            return null;
        }
        if (!el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String tag = opt(o, "tag_name");
        if (tag.isEmpty()) return null;
        String version = tag.replaceFirst("^[vV]", "");
        String notes = opt(o, "body");
        if (notes.length() > 200) notes = notes.substring(0, 200) + "…";
        String apkUrl = "";
        JsonArray assets = o.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement a : assets) {
                JsonObject ao = a.getAsJsonObject();
                String name = opt(ao, "name");
                if (name.endsWith(".apk")) {
                    apkUrl = opt(ao, "browser_download_url");
                    break;
                }
            }
        }
        return new UpdateInfo(version, notes, apkUrl, opt(o, "html_url"));
    }

    /** 版本比较：true = 有更新（远端 > 本机）。语义化 x.y.z */
    public static boolean isNewer(String remote, String local) {
        String[] r = remote.split("[.\\-]");
        String[] l = local.split("[.\\-]");
        for (int i = 0; i < 3; i++) {
            int rv = i < r.length ? parse(r[i]) : 0;
            int lv = i < l.length ? parse(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    /** 联网检查；失败返回 null（静默） */
    public UpdateInfo check(String currentVersion) {
        try {
            Request req = new Request.Builder()
                    .url(RELEASES_API)
                    .header("User-Agent", "wanshang-yinfu/" + currentVersion)
                    .header("Accept", "application/vnd.github+json")
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.body() == null || resp.code() != 200) return null;
                UpdateInfo info = parseRelease(resp.body().string());
                if (info == null) return null;
                return isNewer(info.version, currentVersion) ? info : null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String opt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
