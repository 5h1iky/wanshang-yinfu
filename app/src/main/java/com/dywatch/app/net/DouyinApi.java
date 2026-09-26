package com.dywatch.app.net;

// 抖音 Web 数据层（M2 Feed）：OkHttp + Rhino 签名（Signer）+ Gson 解析。
// 经验全部来自 M0.5① 实测：ttwid 从 live.douyin.com 获取；feed 对 msToken 校验宽松；
// a_bogus 必须对最终 query 串签名后追加。纯函数（buildFeedQuery/parseFeed）可 JVM 单测。

import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.sign.Signer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class DouyinApi {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private final OkHttpClient client;
    private final Signer signer;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private int mRefreshIndex = 0; // feed 翻页游标（抖音推荐流每次只回 2~5 条，需递增翻页）

    /** 游标存取（持久化用：每次进页面从上次进度继续，否则永远第一页=同样视频） */
    public void setRefreshIndex(int idx) {
        mRefreshIndex = idx;
    }

    public int getRefreshIndex() {
        return mRefreshIndex;
    }

    public DouyinApi(Signer signer) {
        this.signer = signer;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /** M0.5① 实测：live.douyin.com 一跳即发 ttwid（www 两跳老办法已失效） */
    public synchronized void ensureTtwid() throws IOException {
        if (cookies.containsKey("ttwid")) return;
        Request req = new Request.Builder()
                .url("https://live.douyin.com/646454278948")
                .header("User-Agent", UA)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            absorbCookies(resp);
        }
        if (!cookies.containsKey("ttwid")) {
            throw new IOException("ttwid 获取失败");
        }
    }

    /** 拉推荐视频流（无需登录）。每次调用自动递增 refresh_index 翻页 */
    public List<FeedVideo> fetchFeed(int count) throws IOException {
        ensureTtwid();
        mRefreshIndex++;
        String query = buildFeedQuery(count, mRefreshIndex, signer);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/tab/feed/?" + query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", cookieHeader())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            List<FeedVideo> list = parseFeed(body);
            if (list.isEmpty()) {
                throw new IOException("feed 为空，status=" + body.substring(0, Math.min(120, body.length())));
            }
            return list;
        }
    }

    // ---- 登录态（会话 cookie 注入，让 feed/互动走本人推荐与本人身份）----

    private String mSessionCookie = "";

    /** 注入登录会话 cookie（"a=b; c=d" 形式，LoginManager 备份即此格式） */
    public void setSessionCookie(String cookieHeader) {
        mSessionCookie = cookieHeader == null ? "" : cookieHeader;
    }

    private String fullCookie() {
        String base = cookieHeader();
        return mSessionCookie.isEmpty() ? base : mSessionCookie + "; " + base;
    }

    /** 点赞/取消点赞（cv-cat digg() 实录形态：POST /commit/item/digg/，query 签名 body 不签） */
    public String digg(String awemeId, boolean like) throws IOException {
        return postForm("/aweme/v1/web/commit/item/digg/",
                "https://www.douyin.com/discover?modal_id=" + awemeId,
                "aweme_id=" + awemeId + "&item_type=0&type=" + (like ? "1" : "0"),
                false);
    }

    /** 收藏/取消收藏（cv-cat collect_aweme() 实录形态：POST /aweme/collect/，无 dtrait） */
    public String collect(String awemeId, boolean collect) throws IOException {
        return postForm("/aweme/v1/web/aweme/collect/",
                "https://www.douyin.com/?recommend=1",
                "action=" + (collect ? "1" : "0") + "&aweme_id=" + awemeId + "&aweme_type=0",
                false);
    }

    /** 写操作公共形态：query=平台参数+verifyFp/uifid/msToken+a_bogus(+uid)，body 表单 */
    private String postForm(String api, String referer, String body, boolean withDtrait) throws IOException {
        if (mSessionCookie.isEmpty()) throw new IOException("未登录（无会话 cookie）");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("pc_client_type", "1");
        p.put("update_version_code", "170400");
        p.put("version_code", "170400");
        p.put("version_name", "17.4.0");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "1920");
        p.put("screen_height", "1080");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "135.0.0.0");
        p.put("browser_online", "true");
        p.put("os_name", "Windows");
        p.put("os_version", "10");
        p.put("platform", "PC");
        p.put("downlink", "10");
        p.put("effective_type", "4g");
        p.put("round_trip_time", "0");
        String fp = cookieValue("s_v_web_id");
        if (!fp.isEmpty()) {
            p.put("verifyFp", fp);
            p.put("fp", fp);
        }
        String uifid = cookieValue("UIFID");
        if (!uifid.isEmpty()) p.put("uifid", uifid);
        p.put("msToken", randomToken(107));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        String ab = signer.makeABogus(q.toString());
        String url = "https://www.douyin.com" + api + "?" + q + "&a_bogus=" + urlEncode(ab);
        String uid = cookieValue("sessionid_uid");
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Origin", "https://www.douyin.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", fullCookie())
                .post(RequestBody.create(okhttp3.MediaType.parse("application/x-www-form-urlencoded"), body));
        String csrf = cookieValue("passport_csrf_token");
        if (!csrf.isEmpty()) rb.header("x-tt-passport-csrf-token", csrf);
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            return "HTTP " + resp.code() + " " + text.substring(0, Math.min(200, text.length()));
        }
    }

    private String cookieValue(String name) {
        String src = mSessionCookie.isEmpty() ? cookieHeader() : fullCookie();
        for (String part : src.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).trim().equals(name)) return part.substring(i + 1).trim();
        }
        return "";
    }

    private static String randomToken(int len) {
        String abc = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnoprstwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    /** 构造 feed query（含 a_bogus）；纯函数 */
    public static String buildFeedQuery(int count, Signer signer) {
        return buildFeedQuery(count, 1, signer);
    }

    /** 构造 feed query（含 a_bogus 与翻页游标）；纯函数 */
    public static String buildFeedQuery(int count, int refreshIndex, Signer signer) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("update_version_code", "170400");
        p.put("pc_client_type", "1");
        p.put("pc_libra_divert", "Windows");
        p.put("support_h265", "1");
        p.put("support_dash", "1");
        p.put("version_code", "170400");
        p.put("version_name", "17.4.0");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "2560");
        p.put("screen_height", "1440");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "135.0.0.0");
        p.put("browser_online", "true");
        p.put("engine_name", "Blink");
        p.put("engine_version", "135.0.0.0");
        p.put("os_name", "Windows");
        p.put("os_version", "10");
        p.put("cpu_core_num", "20");
        p.put("device_memory", "8");
        p.put("platform", "PC");
        p.put("downlink", "0.55");
        p.put("effective_type", "3g");
        p.put("round_trip_time", "500");
        p.put("count", String.valueOf(count));
        p.put("refresh_index", String.valueOf(refreshIndex));
        p.put("tag_id", "");
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        String ab = signer.makeABogus(q.toString());
        return q + "&a_bogus=" + urlEncode(ab);
    }

    /** 解析 feed JSON → FeedVideo 列表；纯函数 */
    public static List<FeedVideo> parseFeed(String json) {
        List<FeedVideo> out = new ArrayList<>();
        JsonElement rootEl = JsonParser.parseString(json);
        if (!rootEl.isJsonObject()) return out;
        JsonObject root = rootEl.getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("aweme_list");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject item = el.getAsJsonObject();
            String title = optString(item, "desc");
            String awemeId = optString(item, "aweme_id");
            String author = "";
            JsonObject authorObj = item.getAsJsonObject("author");
            if (authorObj != null) author = optString(authorObj, "nickname");
            long digg = 0, comment = 0, collect = 0;
            JsonObject stats = item.getAsJsonObject("statistics");
            if (stats != null) {
                digg = optLong(stats, "digg_count");
                comment = optLong(stats, "comment_count");
                collect = optLong(stats, "collect_count");
            }
            String playUrl = "";
            String coverUrl = "";
            JsonObject video = item.getAsJsonObject("video");
            if (video != null) {
                playUrl = firstUrl(video, "play_addr");
                if (playUrl.isEmpty()) playUrl = firstUrl(video, "play_addr_lowbr");
                coverUrl = firstUrl(video, "cover");
                if (coverUrl.isEmpty()) coverUrl = firstUrl(video, "origin_cover");
            }
            if (!playUrl.isEmpty()) {
                out.add(new FeedVideo(title, playUrl, coverUrl, awemeId, author, digg, comment, collect));
            }
        }
        return out;
    }

    private static long optLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static String firstUrl(JsonObject video, String key) {
        JsonObject addr = video.getAsJsonObject(key);
        if (addr == null) return "";
        JsonArray list = addr.getAsJsonArray("url_list");
        if (list == null || list.size() == 0) return "";
        // ⚠️ 真机取证（2026-09-25，url-test.js 实测）：url_list 里直连 CDN 候选（v*-web*.douyinvod.com）
        // 一律 403（带任何请求头/Cookie 均拒），只有 www.douyin.com 的跳转候选（302→douyinvod 206 video/mp4）
        // 可播 → 必须优先选跳转式 URL，否则"只显示封面"（播放器拉流失败被静默吞掉）。
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i).getAsString();
            if (u.startsWith("https://www.douyin.com/")) return u;
        }
        return list.get(0).getAsString();
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private void absorbCookies(Response resp) {
        for (String c : resp.headers("Set-Cookie")) {
            String kv = c.split(";")[0];
            int i = kv.indexOf('=');
            if (i > 0) cookies.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
