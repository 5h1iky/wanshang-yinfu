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
import okhttp3.Response;

public final class DouyinApi {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    /**
     * 拉流/播放必需的请求头。真机 34s ERROR 的根因就在这里：CDN 边缘对无 Referer 的请求一律 403。
     * 实测头矩阵（tools/play-header-matrix.js，2026-09-26）：带 Referer 即可 206 video/mp4，Cookie 反而不需要。
     */
    public static Map<String, String> playHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://www.douyin.com/");
        return h;
    }

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
                // Cookie 出站守卫（代码审计 M2）：注册成**网络拦截器**才能逐跳生效——
                // 手工塞的 Cookie 头会被 OkHttp 在 302 时原样复制到站外主机上。
                .addNetworkInterceptor(new CookieHostGuard())
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

    // ---- 只读扩展（2026-09-26 实测：只读接口风控宽松，可直连）----

    /**
     * 拉自己的「喜欢」列表（点赞历史）。
     *
     * 实测（tools/probe-readonly-apis.js，2026-09-26）：HTTP 200 + status_code=0 + 13 条真数据。
     * ⚠️ 与参考源码 cv-cat 的注释不符：它称此接口在 secsdk webSign 保护表内、
     *    必须追加 timestamp + x-secsdk-web-signature；实测只带通用参数 + a_bogus 即可通。
     *
     * @param secUid 自己的 sec_uid（由 fetchSelfSecUid 取）
     * @param cursor 翻页游标，首页传 0
     */
    public List<FeedVideo> fetchFavorite(String secUid, long cursor) throws IOException {
        ensureTtwid();
        String query = buildFavoriteQuery(secUid, cursor);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/favorite/?" + query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/"
                        + (secUid == null ? "" : secUid) + "?showTab=like")
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            List<FeedVideo> list = parseFeed(body);
            if (list.isEmpty()) {
                // 空也可能是真没喜欢过；用 status_code 区分"接口没通"和"确实没数据"
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(body);
                int code = el.isJsonObject() ? optInt(el.getAsJsonObject(), "status_code") : -1;
                if (code != 0) throw new IOException("喜欢列表 status=" + code);
            }
            return list;
        }
    }

    // ---- 通用 query 构建 ----
    //
    // 这里原先有 5 份几乎一字不差的 30 行"设备参数块"（favorite/post/profile/self/profile/other/feed），
    // 每份 ~150 行重复。2026-09-27 收拢成 baseDeviceParams + joinAndSign：
    // ⚠️ 键值**一字未改**（含 aweme/post 特有的 version_code=290100 这种坑），只是不再抄五遍——
    //    以后抖音改参数族，改一处五处全生效，不会再出现"改了 feed 忘了 favorite"。

    /** 各只读接口共用的设备/浏览器指纹参数（照实测探针抄，勿凭感觉改） */
    private static Map<String, String> baseDeviceParams(String versionCode, String versionName) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("device_platform", "webapp");
        p.put("aid", "6383");
        p.put("channel", "channel_pc_web");
        p.put("update_version_code", versionCode);
        p.put("pc_client_type", "1");
        p.put("pc_libra_divert", "Windows");
        p.put("support_h265", "1");
        p.put("support_dash", "1");
        p.put("version_code", versionCode);
        p.put("version_name", versionName);
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
        p.put("round_trip_time", "0");
        return p;
    }

    /** 通用版本参数族 */
    private static final String VER_COMMON_CODE = "170400", VER_COMMON_NAME = "17.4.0";
    /** aweme/post 特有版本号（写通用的 170400 会少字段，实测结论） */
    private static final String VER_POST_CODE = "290100", VER_POST_NAME = "29.1.0";

    /** 拼 query 并签名（LinkedHashMap 保序：a_bogus 必须对最终串算） */
    private String joinAndSign(Map<String, String> p) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(e.getValue());
        }
        return q + "&a_bogus=" + urlEncode(signer.makeABogus(q.toString()));
    }

    /** 构造 favorite query（参数族照实测探针）；纯函数（除签名外无副作用） */
    String buildFavoriteQuery(String secUid, long cursor) {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("sec_user_id", secUid == null ? "" : secUid);
        p.put("max_cursor", String.valueOf(cursor));
        p.put("min_cursor", "0");
        p.put("whale_cut_token", "");       // 空值字段，浏览器确实发
        p.put("cut_version", "1");
        p.put("count", "18");
        p.put("publish_video_strategy_type", "2");
        return joinAndSign(p);
    }

    /**
     * 拉某个作者的主页作品列表（只读，直连）。
     *
     * 实测（2026-09-26）：HTTP 200 + status_code=0 + 5 条，含 statistics。
     * ⚠️ 这个接口有自己特有的版本号 version_code=290100（不是主站通用的 170400），
     *    cv-cat 注释明确警告写错会少字段。
     */
    public List<FeedVideo> fetchUserPosts(String secUid, long cursor) throws IOException {
        ensureTtwid();
        String query = buildUserPostsQuery(secUid, cursor);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/post/?" + query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/" + (secUid == null ? "" : secUid))
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            List<FeedVideo> list = parseFeed(body);
            // 翻页游标（下一批从这继续；-1 = 没有更多）。响应里有 has_more/max_cursor，
            // 不记的话调用方只能反复拉第一页
            com.google.gson.JsonElement rootEl = com.google.gson.JsonParser.parseString(body);
            if (rootEl.isJsonObject()) {
                com.google.gson.JsonObject root = rootEl.getAsJsonObject();
                boolean hasMore = optInt(root, "has_more") == 1;
                long next = optLong(root, "max_cursor");
                lastPostsHasMore = hasMore;
                lastPostsCursor = hasMore ? next : -1;
            } else {
                lastPostsHasMore = false;
                lastPostsCursor = -1;
            }
            if (list.isEmpty()) {
                com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(body);
                int code = el.isJsonObject() ? optInt(el.getAsJsonObject(), "status_code") : -1;
                if (code != 0) throw new IOException("作品列表 status=" + code);
            }
            return list;
        }
    }

    /** fetchUserPosts 的翻页游标/是否还有更多（public 字段，与 lastCommentHasMore 同一口径） */
    public volatile boolean lastPostsHasMore;
    public volatile long lastPostsCursor = -1;

    /** 构造 aweme/post query；纯函数（除签名外无副作用） */
    String buildUserPostsQuery(String secUid, long cursor) {
        Map<String, String> p = baseDeviceParams(VER_POST_CODE, VER_POST_NAME);
        p.put("sec_user_id", secUid == null ? "" : secUid);
        p.put("max_cursor", String.valueOf(cursor));
        p.put("locate_query", "false");
        p.put("show_live_replay_strategy", "1");
        p.put("need_time_list", "1");
        p.put("time_list_query", "0");
        p.put("whale_cut_token", "");
        p.put("cut_version", "1");
        p.put("count", "18");
        p.put("publish_video_strategy_type", "2");
        p.put("from_user_page", "1");          // 看别人的主页
        return joinAndSign(p);
    }

    /**
     * 按 aweme_id 换取可播地址（只读，直连）。
     *
     * 用途：「我的」页看过记录只存了 id/标题/封面，没有播放地址——点进去要靠它换址。
     * ⚠️ 响应结构与 feed 不同：条目在 `aweme_detail`（不是 aweme_list）。
     *    2026-09-27 实测 tools/probe-aweme-detail.js：status_code=0，可解出 play_url。
     */
    public FeedVideo fetchDetail(String awemeId) throws IOException {
        ensureTtwid();
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("aweme_id", awemeId == null ? "" : awemeId);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/detail/?"
                        + joinAndSign(p))
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            com.google.gson.JsonElement rootEl = com.google.gson.JsonParser.parseString(body);
            if (!rootEl.isJsonObject()) throw new IOException("detail 非 JSON");
            com.google.gson.JsonObject root = rootEl.getAsJsonObject();
            int code = optInt(root, "status_code");
            if (code != 0) throw new IOException("detail status=" + code);
            com.google.gson.JsonObject item = objOf(root, "aweme_detail");
            if (item == null) throw new IOException("detail 无 aweme_detail");
            return itemToVideo(item);
        }
    }

    /** 作者信息（昵称/粉丝数/作品数等），给主页头部用 */
    public static class UserInfo {
        public String nickname = "";
        public String signature = "";
        public String avatarUrl = "";
        public long followerCount;
        public long totalFavorited;
        public long awemeCount;
    }

    public UserInfo fetchUserInfo(String secUid) throws IOException {
        ensureTtwid();
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("sec_user_id", secUid == null ? "" : secUid);
        p.put("publish_video_strategy_type", "2");
        p.put("source", "channel_pc_web");
        p.put("personal_center_strategy", "1");
        p.put("profile_other_record_enable", "1");
        p.put("land_to", "1");
        String q = joinAndSign(p);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/user/profile/other/?" + q)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/" + (secUid == null ? "" : secUid))
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            com.google.gson.JsonElement el =
                    com.google.gson.JsonParser.parseString(resp.body().string());
            if (!el.isJsonObject()) throw new IOException("profile/other 非 JSON");
            com.google.gson.JsonObject u = objOf(el.getAsJsonObject(), "user");
            if (u == null) throw new IOException("profile/other 无 user");
            UserInfo info = new UserInfo();
            info.nickname = optString(u, "nickname");
            info.signature = optString(u, "signature");
            info.followerCount = u.has("follower_count") ? u.get("follower_count").getAsLong() : 0L;
            info.totalFavorited = u.has("total_favorited") ? u.get("total_favorited").getAsLong() : 0L;
            info.awemeCount = u.has("aweme_count") ? u.get("aweme_count").getAsLong() : 0L;
            com.google.gson.JsonObject av = objOf(u, "avatar_thumb");
            if (av != null) {
                com.google.gson.JsonArray l = arrOf(av, "url_list");
                if (l != null && l.size() > 0) info.avatarUrl = l.get(0).getAsString();
            }
            return info;
        }
    }

    /**
     * 拉评论列表（只读，直连）。
     *
     * 实测（2026-09-26，tools/probe-readonly-apis2.js）：www-hj 域 + 仅 3 个参数
     * （aweme_id / cursor / count + a_bogus）即可，HTTP 200 + status_code=0 + 20 条。
     * 参考源码 dyapi-src 的 VideoCommentListHandler 也是这个极简形态。
     *
     * 为什么改直连：原实现是 WebView 里抓 DOM（comment-item-info-wrap 等 class），
     * 那些类名是构建期 hash 拼接，页面一改版就全断；直连拿 JSON 结构稳定，
     * 还带 cursor + has_more 真分页，不用靠滚动懒加载去"骗"出更多评论。
     *
     * @return 评论列表；附带把 has_more 记到 lastCommentHasMore
     */
    public List<com.dywatch.app.chat.model.Comment> fetchComments(
            String awemeId, long cursor, int count) throws IOException {
        ensureTtwid();
        String base = "aweme_id=" + awemeId + "&cursor=" + cursor + "&count=" + count;
        String url = "https://www-hj.douyin.com/aweme/v1/web/comment/list/?" + base
                + "&a_bogus=" + urlEncode(signer.makeABogus(base));
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Origin", "https://www.douyin.com")
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            String body = resp.body().string();
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(body);
            if (!el.isJsonObject()) throw new IOException("评论接口非 JSON");
            com.google.gson.JsonObject o = el.getAsJsonObject();
            int code = optInt(o, "status_code");
            if (code != 0) throw new IOException("评论列表 status=" + code);
            lastCommentHasMore = o.has("has_more") && o.get("has_more").getAsInt() == 1;
            lastCommentCursor = o.has("cursor") ? o.get("cursor").getAsLong() : 0L;
            List<com.dywatch.app.chat.model.Comment> out = new ArrayList<>();
            com.google.gson.JsonArray arr = arrOf(o, "comments");
            if (arr == null) return out;
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement ce = arr.get(i);
                if (!ce.isJsonObject()) continue;
                com.google.gson.JsonObject c = ce.getAsJsonObject();
                com.google.gson.JsonObject u = objOf(c, "user");
                String name = u == null ? "" : optString(u, "nickname");
                long digg = c.has("digg_count") ? c.get("digg_count").getAsLong() : 0L;
                long ts = c.has("create_time") ? c.get("create_time").getAsLong() : 0L;
                com.dywatch.app.chat.model.Comment cm =
                        new com.dywatch.app.chat.model.Comment(
                                name,
                                optString(c, "text"),
                                formatCommentTime(ts),
                                com.dywatch.app.feed.FeedVideo.formatCount(digg));
                out.add(cm);
            }
            return out;
        }
    }

    /** 上次拉评论的结果：还有没有下一页 */
    public boolean lastCommentHasMore;
    /** 上次拉评论返回的下一页游标 */
    public long lastCommentCursor;

    /** create_time（秒）→ 展示文案。相对时间比绝对时间省地方，手表屏要省着用。 */
    static String formatCommentTime(long epochSec) {
        if (epochSec <= 0) return "";
        long diff = System.currentTimeMillis() / 1000 - epochSec;
        if (diff < 60) return "刚刚";
        if (diff < 3600) return (diff / 60) + "分钟前";
        if (diff < 86400) return (diff / 3600) + "小时前";
        if (diff < 86400 * 30) return (diff / 86400) + "天前";
        if (diff < 86400 * 365) return (diff / 86400 / 30) + "个月前";
        return (diff / 86400 / 365) + "年前";
    }

    /**
     * 取自己的 sec_uid（拉喜欢列表要用）。
     * ⚠️ 主站 /user/self 页面已改客户端渲染、HTML 里没有 secUid（cv-cat 2026-08 复核）；
     *    但实测 profile/self 这个 JSON 接口的 user.sec_uid 仍然给，直接用它。
     */
    public String fetchSelfSecUid() throws IOException {
        String q = joinAndSign(baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME));
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/user/profile/self/?" + q)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", fullCookie())
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("空响应");
            com.google.gson.JsonElement el =
                    com.google.gson.JsonParser.parseString(resp.body().string());
            if (!el.isJsonObject()) throw new IOException("profile/self 非 JSON");
            com.google.gson.JsonObject user = objOf(el.getAsJsonObject(), "user");
            if (user == null) throw new IOException("profile/self 无 user（未登录？）");
            return optString(user, "sec_uid");
        }
    }

    /**
     * 拉推荐视频流（带登录态拉本人推荐）。每次调用自动递增 refresh_index 翻页。
     *
     * ⚠️ 必须用 fullCookie()（匿名 cookie + 登录态合并）。这里原本用 cookieHeader()，
     *    而 cookieHeader() 只遍历 cookies——那个 map 只有 ensureTtwid() 塞进去的 ttwid，
     *    登录态存在 mSessionCookie 里根本没发出去（fullCookie 全工程零调用者）。
     *    后果：服务端把我们当匿名设备，返回影视/剧情这类冷启动泛池，
     *    而不是本人画像的推荐（用户目击：App 里刷到的和浏览器里刷到的完全不一样）。
     *    日志里的"登录态=true"只是判断本机有没有存会话，跟请求带没带是两回事。
     */
    public List<FeedVideo> fetchFeed(int count) throws IOException {
        ensureTtwid();
        mRefreshIndex++;
        String query = buildFeedQuery(count, mRefreshIndex, signer);
        Request req = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/tab/feed/?" + query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", fullCookie())
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

    /** 构造 feed query（含 a_bogus）；纯函数 */
    public static String buildFeedQuery(int count, Signer signer) {
        return buildFeedQuery(count, 1, signer);
    }

    /** 构造 feed query（含 a_bogus 与翻页游标）；纯函数 */
    public static String buildFeedQuery(int count, int refreshIndex, Signer signer) {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("round_trip_time", "500");     // feed 这个接口实测探针发的是 500，保持原值
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
        JsonArray arr = arrOf(root, "aweme_list");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            FeedVideo v = itemToVideo(el.getAsJsonObject());
            if (v != null) out.add(v);
        }
        return out;
    }

    /** 单条 aweme 对象 → FeedVideo（feed 与 detail 两个接口共用同一字段抽取）；纯函数 */
    static FeedVideo itemToVideo(JsonObject item) {
        String awemeId = optString(item, "aweme_id");
        String title = optString(item, "desc");
        String author = "";
        String authorSecUid = "";
        String authorAvatar = "";
        JsonObject authorObj = objOf(item, "author");
        if (authorObj != null) {
            author = optString(authorObj, "nickname");
            // 进作者主页要用 sec_uid（profile/other 与 aweme/post 都靠它定位）
            authorSecUid = optString(authorObj, "sec_uid");
            // 头像（2026-09-27 补）：字段是 author.avatar_thumb（实测 probe-avatar-cover.js）
            authorAvatar = imageUrl(authorObj, "avatar_thumb");
        }
        long digg = 0, comment = 0, collect = 0;
        JsonObject stats = objOf(item, "statistics");
        if (stats != null) {
            digg = optLong(stats, "digg_count");
            comment = optLong(stats, "comment_count");
            collect = optLong(stats, "collect_count");
        }
        String playUrl = "";
        String coverUrl = "";
        JsonObject video = objOf(item, "video");
        if (video != null) {
            playUrl = pickGearUrl(video, awemeId);
            if (playUrl.isEmpty()) playUrl = firstUrl(video, "play_addr", awemeId);
            if (playUrl.isEmpty()) playUrl = firstUrl(video, "play_addr_lowbr", awemeId);
            // ⚠️ 封面必须走 imageUrl，**不能**走 firstUrl/urlFromAddr：
            //    后者是给播放地址用的，它见到 cover.uri 非空就会拼成
            //    https://www.douyin.com/aweme/v1/play/?video_id=<封面uri>（拿播放端点当图床），
            //    Glide 必然加载失败 → 封面永远是黑的（2026-09-27 实测确认）。
            coverUrl = imageUrl(video, "cover");
            if (coverUrl.isEmpty()) coverUrl = imageUrl(video, "origin_cover");
        }
        if (playUrl.isEmpty()) return null;
        FeedVideo v = new FeedVideo(title, playUrl, coverUrl, awemeId, author, digg, comment, collect);
        v.authorSecUid = authorSecUid;
        v.authorAvatar = authorAvatar;
        return v;
    }

    /**
     * 取「图片类」字段的直链：cover / origin_cover / avatar_thumb 这类对象的 url_list[0]。
     *
     * 实测（2026-09-27，tools/probe-avatar-cover.js，带登录态的真实 feed）：
     *   - 头像 author.avatar_thumb.url_list[0] → https://p3-pc.douyinpic.com/...  HTTP 200 image/jpeg
     *   - 封面 video.cover.url_list[0]        → https://p9-pc-sign.douyinpic.com/... HTTP 200 image/jpeg
     *   - 两者**带不带 Referer 都能取到**，所以 Glide 直接加载即可，不需要挂请求头。
     * 与 urlFromAddr 的区别见 itemToVideo 里的注释（那个是播放地址专用，会把图片 URL 拼坏）。
     */
    static String imageUrl(JsonObject holder, String key) {
        if (holder == null) return "";
        JsonObject addr = objOf(holder, key);
        if (addr == null) return "";
        JsonArray list = arrOf(addr, "url_list");
        if (list == null || list.size() == 0) return "";
        for (int i = 0; i < list.size(); i++) {
            JsonElement e = list.get(i);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) continue;
            String u = e.getAsString();
            if (u != null && u.startsWith("http")) return u;
        }
        return "";
    }

    /**
     * 安全取对象：字段缺失、为 null（JsonNull）或不是对象时一律返回 null。
     * ⚠️ 不能直接用 getAsJsonObject(key)：字段存在但值是 null 时它会抛
     *    ClassCastException（JsonNull cannot be cast to JsonObject）。真机踩过——
     *    带上登录态后会拉到图文贴，它们的 video 字段就是 null。
     */
    private static JsonObject objOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /** 安全取数组：同上，非数组/缺失/null 一律返回 null */
    private static JsonArray arrOf(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static long optLong(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static int optInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    private static String firstUrl(JsonObject video, String key, String awemeId) {
        JsonObject addr = objOf(video, key);
        if (addr == null) return "";
        return urlFromAddr(addr, awemeId);
    }

    /**
     * 画质偏好（设置页写入）：0=省流量(优先540p) 1=平衡(优先720p) 2=清晰(允许1080p)。
     * 默认 0——手表屏只有 1.4 寸，高分辨率看不出差别，却实打实吃解码功耗和流量。
     */
    public static volatile int sQuality = 0;

    /** 按画质偏好给档位打分，分越高越优先；H.264 / 非 DASH 的硬门槛在 pickGearUrl 里另判 */
    static int gearRank(String gearName) {
        boolean p540 = gearName.contains("_540_");
        boolean p720 = gearName.contains("_720_");
        boolean p1080 = gearName.contains("_1080_");
        if (sQuality >= 2) return p1080 ? 3 : p720 ? 2 : p540 ? 1 : 0;
        if (sQuality == 1) return p720 ? 3 : p540 ? 2 : 1;
        return p540 ? 3 : p720 ? 2 : 1;
    }

    /**
     * 手表优先挑解码友好的一档：H.264（HEVC 在低端手表 SoC 上常无硬解，软解更吃电）
     * + 按 sQuality 选分辨率 + 同档取最低码率。挑不到返回空，由调用方回退默认 play_addr。
     *
     * ⚠️ 必须排除 DASH 档（url 形如 /aweme/v1/play/dash/）：实测 25 档里 14 档给跳转式 mp4、
     * 11 档给 DASH **单轨**流（站点自己另发一条 media-audio-und-mp4a 请求配音频）。
     * MediaPlayer 单 URL 播它＝有画面没声音。
     */
    static String pickGearUrl(JsonObject video, String awemeId) {
        JsonArray gears = arrOf(video, "bit_rate");
        if (gears == null || gears.size() == 0) return "";
        String bestUrl = "";
        int bestRank = 0;
        long bestBr = 0;
        String bestGear = "";
        for (JsonElement el : gears) {
            if (!el.isJsonObject()) continue;
            if (!el.isJsonObject()) continue;
            JsonObject g = el.getAsJsonObject();
            if (optInt(g, "is_h265") != 0 || optInt(g, "is_bytevc1") != 0) continue;
            JsonObject addr = objOf(g, "play_addr");
            if (addr == null) continue;
            String url = urlFromAddr(addr, awemeId);
            if (url.isEmpty() || url.contains("/play/dash/")) continue;
            String gear = optString(g, "gear_name");
            int rank = gearRank(gear);
            long br = optLong(g, "bit_rate");
            if (bestUrl.isEmpty() || rank > bestRank || (rank == bestRank && br < bestBr)) {
                bestUrl = url;
                bestRank = rank;
                bestBr = br;
                bestGear = gear;
            }
        }
        if (bestUrl.isEmpty()) return "";
        // 选档结果落日志：URL 本身看不出档位，不记就没法验证手表上到底播的是哪一档
        com.dywatch.app.util.AppLog.i("feed", "选档 " + awemeId + " → " + bestGear
                + " " + (bestBr / 1000) + "kbps（共 " + gears.size() + " 档候选）");
        return bestUrl;
    }

    private static String urlFromAddr(JsonObject addr, String awemeId) {
        JsonArray list = arrOf(addr, "url_list");
        if (list == null || list.size() == 0) return "";
        // ⚠️ 真机取证（2026-09-25，url-test.js 实测）：url_list 里直连 CDN 候选（v*-web*.douyinvod.com）
        // 一律 403（带任何请求头/Cookie 均拒），只有 www.douyin.com 的跳转候选（302→douyinvod 206 video/mp4）
        // 可播 → 必须优先选跳转式 URL，否则"只显示封面"（播放器拉流失败被静默吞掉）。
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i).getAsString();
            if (u.startsWith("https://www.douyin.com/")) return u;
        }
        // 没给跳转候选时（实测登录态 feed 会只回 CDN 直连）：自己拼官方跳转式播放地址，
        // 用 play_addr.uri（形如 v0200fg...）；不能再回退 list.get(0)——那是必 403 的直连 CDN（实测
        // 2026-09-26 12:26 开始播放 v26-web-prime.douyinvod.com → 随即 IDLE，只显封面）。
        String uri = optString(addr, "uri");
        if (!uri.isEmpty()) {
            return "https://www.douyin.com/aweme/v1/play/?video_id=" + uri
                    + "&ratio=1080p&line=0" + (awemeId == null || awemeId.isEmpty() ? "" : "&item_id=" + awemeId);
        }
        return "";
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
