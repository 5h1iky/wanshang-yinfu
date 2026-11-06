package com.dywatch.app.net;

// 抖音 Web 数据层（M2 Feed）：OkHttp + Rhino 签名（Signer）+ Gson 解析。
// 经验全部来自 M0.5① 实测：ttwid 从 live.douyin.com 获取；feed 对 msToken 校验宽松；
// a_bogus 必须对最终 query 串签名后追加。纯函数（buildFeedQuery/parseFeed）可 JVM 单测。

import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.sign.Signer;
import com.dywatch.app.util.AppLog;
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
    public static Map<String, String> livePlayHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", "https://live.douyin.com/");
        h.put("Accept", "*/*");
        return h;
    }

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
        String body;
        try {
            body = requestFavorite(query, secUid, false);
        } catch (IOException first) {
            body = "";
        }
        if (body.trim().isEmpty() || !body.trim().startsWith("{")) {
            // 2026-11-01：Argus 现在会拦 favorite（HTTP 403 / Uifid Not Found）。
            // 主页作品接口用本地 webSign 已实测可过同一层保护，喜欢列表同样重试一次。
            AppLog.i("api", "喜欢列表首次响应非 JSON，尝试 webSign 重试");
            body = requestFavorite(query, secUid, true);
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) throw new IOException("喜欢列表暂时没有返回内容，请稍后再试");
        if (!trimmed.startsWith("{")) throw new IOException("喜欢列表暂时被接口拦截，请稍后再试");
        List<FeedVideo> list = parseFeed(trimmed);
        if (list.isEmpty()) {
            // 空也可能是真没喜欢过；用 status_code 区分"接口没通"和"确实没数据"
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(trimmed);
            int code = el.isJsonObject() ? optInt(el.getAsJsonObject(), "status_code") : -1;
            if (code != 0) {
                AppLog.i("api", "favorite status=" + code);
                throw new IOException("喜欢列表暂时不可用（status=" + code + "），稍后再试");
            }
        }
        return list;
    }

    /** 喜欢列表单次请求；webSign=true 时追加本地签名参数与请求头。 */
    private String requestFavorite(String query, String secUid, boolean webSign) throws IOException {
        String finalQuery = query;
        Map<String, String> signedHeaders = java.util.Collections.emptyMap();
        if (webSign) {
            SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(query, fullCookie());
            finalQuery = signed.query;
            signedHeaders = signed.headers;
        }
        Request.Builder builder = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/favorite/?" + finalQuery)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/"
                        + (secUid == null ? "" : secUid) + "?showTab=like")
                .header("Accept", "application/json")
                .header("Cookie", fullCookie());
        for (Map.Entry<String, String> header : signedHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        try (Response resp = client.newCall(builder.build()).execute()) {
            if (resp.body() == null) throw new IOException("喜欢列表暂时不可用（空响应），稍后再试");
            int httpCode = resp.code();
            String body = resp.body().string();
            // 只落状态与长度，不落正文（正文可能带账号信息）。
            AppLog.i("api", "favorite http=" + httpCode + " len=" + body.length()
                    + " webSign=" + webSign);
            if (!resp.isSuccessful()) throw new IOException("喜欢列表 HTTP " + httpCode);
            return body;
        }
    }

    /** 本人身份：只把接口调用所需的标识交给关注/收藏功能，不记录到日志。 */
    public static class SelfIdentity {
        public String uid = "";
        public String secUid = "";
    }

    public SelfIdentity fetchSelfIdentity() throws IOException {
        String q = joinAndSign(baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME));
        JsonObject root = getJson("/aweme/v1/web/user/profile/self/", q,
                "https://www.douyin.com/", "本人信息");
        JsonObject user = objOf(root, "user");
        if (user == null) throw new IOException("登录状态已失效，请重新扫码登录");
        SelfIdentity identity = new SelfIdentity();
        identity.uid = optString(user, "uid");
        identity.secUid = optString(user, "sec_uid");
        if (identity.secUid.isEmpty()) throw new IOException("无法读取当前账号，请重新登录");
        return identity;
    }

    /** 关注列表的用户模型；这里只读，不提供关注/取关按钮。 */
    public static class UserSummary {
        public String uid = "", secUid = "", nickname = "", signature = "", avatarUrl = "";
        public long followerCount, awemeCount;
    }

    public volatile boolean lastFollowingHasMore;
    public volatile long lastFollowingCursor;

    /**
     * 自己的关注列表。max_time=0 会走另一分支；首次必须使用当前秒级时间。
     * 参数与本项目已有 probe-readonly-apis2.js 一致，响应正文绝不写入日志。
     */
    public List<UserSummary> fetchFollowing(long maxTime) throws IOException {
        SelfIdentity self = fetchSelfIdentity();
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("user_id", self.uid);
        p.put("sec_user_id", self.secUid);
        p.put("offset", "0");
        p.put("min_time", "0");
        p.put("max_time", String.valueOf(maxTime > 0 ? maxTime : System.currentTimeMillis() / 1000));
        p.put("count", "20");
        p.put("source_type", maxTime > 0 ? "1" : "2");
        p.put("gps_access", "0");
        p.put("address_book_access", "0");
        p.put("is_top", "1");
        p.put("webcast_sdk_version", VER_COMMON_CODE);
        p.put("webcast_version_code", VER_COMMON_CODE);
        JsonObject root = getJson("/aweme/v1/web/user/following/list/", joinAndSign(p),
                "https://www.douyin.com/user/" + self.secUid, "关注列表");
        lastFollowingHasMore = optInt(root, "has_more") == 1;
        long next = optLong(root, "min_time");
        lastFollowingCursor = lastFollowingHasMore && next > 0 ? next : -1;
        return parseFollowing(root);
    }

    static List<UserSummary> parseFollowing(JsonObject root) {
        List<UserSummary> out = new ArrayList<>();
        if (root == null) return out;
        JsonArray arr = arrOf(root, "followings");
        if (arr == null) arr = arrOf(root, "following_list");
        if (arr == null) arr = arrOf(root, "user_following_list");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            UserSummary u = parseUserSummary(el.getAsJsonObject());
            if (!u.secUid.isEmpty()) out.add(u);
        }
        return out;
    }

    static UserSummary parseUserSummary(JsonObject item) {
        UserSummary u = new UserSummary();
        u.uid = optString(item, "uid");
        u.secUid = optString(item, "sec_uid");
        u.nickname = optString(item, "nickname");
        u.signature = optString(item, "signature");
        u.followerCount = optLong(item, "follower_count");
        u.awemeCount = optLong(item, "aweme_count");
        u.avatarUrl = imageUrl(item, "avatar_thumb");
        if (u.avatarUrl.isEmpty()) u.avatarUrl = imageUrl(item, "avatar_medium");
        return u;
    }

    /** 统一处理只读响应：敏感正文不落日志，403/风控文案按当前接口单独报告。 */
    private JsonObject getJson(String path, String query, String referer, String label)
            throws IOException {
        return getJson(path, query, referer, label, java.util.Collections.<String, String>emptyMap());
    }

    private JsonObject getJson(String path, String query, String referer, String label,
                               Map<String, String> extraHeaders) throws IOException {
        ensureTtwid();
        Request req = new Request.Builder()
                .url((path.startsWith("/webcast/") ? "https://live.douyin.com"
                        : "https://www.douyin.com") + path + "?" + query)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .header("Accept", "application/json")
                .header("Cookie", fullCookie())
                .build();
        if (!extraHeaders.isEmpty()) {
            Request.Builder b = req.newBuilder();
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
            req = b.build();
        }
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException(label + "暂时不可用，请稍后重试");
            int http = resp.code();
            String body = resp.body().string();
            String trimmed = body.trim();
            AppLog.i("api", label + " HTTP " + http + " 响应长度=" + body.length());
            if (trimmed.isEmpty()) {
                throw new IOException(label + "暂时没有返回内容，请稍后再试");
            }
            if (!trimmed.startsWith("{")) {
                if (http == 403 || trimmed.contains("ArgusSecurityPlugin")) {
                    throw new IOException(label + "暂时被接口拦截，请稍后再试");
                }
                throw new IOException(label + "返回异常（HTTP " + http + "），请稍后重试");
            }
            JsonElement el = JsonParser.parseString(trimmed);
            if (!el.isJsonObject()) throw new IOException(label + "返回格式异常");
            JsonObject root = el.getAsJsonObject();
            int status = optInt(root, "status_code");
            if (status != 0) {
                AppLog.i("api", label + " status=" + status);
                throw new IOException(status == 8 || status == 2154
                        ? "登录状态已失效，请重新扫码登录" : label + "暂时不可用，请稍后重试");
            }
            return root;
        }
    }

    public static class CollectionInfo implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public String id = "", name = "";
        public int count;
        public boolean isPrivate;
    }

    public volatile long lastCollectionCursor = -1;
    public volatile boolean lastCollectionHasMore;

    /** 全部收藏及单个收藏夹目录；读取目录不等于获取全部收藏。 */
    public List<CollectionInfo> fetchCollections(long cursor) throws IOException {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("cursor", String.valueOf(cursor));
        p.put("count", "20");
        SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(joinAndSign(p), fullCookie());
        JsonObject root = getJson("/aweme/v1/web/collects/list/", signed.query,
                "https://www.douyin.com/?recommend=1", "收藏夹", signed.headers);
        JsonArray arr = arrOf(root, "collects_list");
        if (arr == null) arr = arrOf(root, "collects");
        if (arr == null) arr = arrOf(root, "collection_list");
        List<CollectionInfo> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                CollectionInfo info = new CollectionInfo();
                info.id = firstString(o, "collects_id", "collection_id", "id");
                info.name = firstString(o, "collects_name", "collection_name", "name");
                info.count = (int) firstLong(o, "total", "aweme_count", "count");
                info.isPrivate = optInt(o, "status") == 1 || optInt(o, "is_private") == 1;
                if (!info.id.isEmpty()) out.add(info);
            }
        }
        lastCollectionHasMore = optInt(root, "has_more") == 1;
        lastCollectionCursor = lastCollectionHasMore ? firstLong(root, "cursor", "max_cursor") : -1;
        return out;
    }

    /** 收藏夹内作品。集合 id 必须用 collects_id，不能传用户 id。 */
    public List<FeedVideo> fetchCollectionVideos(String collectionId, long cursor) throws IOException {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("collects_id", collectionId == null ? "" : collectionId);
        p.put("cursor", String.valueOf(cursor));
        p.put("count", "18");
        SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(joinAndSign(p), fullCookie());
        JsonObject root = getJson("/aweme/v1/web/collects/video/list/", signed.query,
                "https://www.douyin.com/", "收藏作品", signed.headers);
        lastCollectionHasMore = optInt(root, "has_more") == 1;
        lastCollectionCursor = lastCollectionHasMore ? firstLong(root, "cursor", "max_cursor") : -1;
        return parseFeed(root.toString());
    }

    /** 全部收藏是 POST 查询（非写入）；参数放 query，空 body，不能改当 GET。 */
    public List<FeedVideo> fetchAllCollectionVideos(long cursor) throws IOException {
        ensureTtwid();
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("publish_video_strategy_type", "2");
        p.put("count", "18");
        p.put("cursor", String.valueOf(cursor));
        SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(joinAndSign(p), fullCookie());
        Request.Builder builder = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/listcollection/?" + signed.query)
                .header("User-Agent", UA).header("Referer", "https://www.douyin.com/")
                .header("Cookie", fullCookie())
                .post(okhttp3.RequestBody.create(new byte[0], null));
        for (Map.Entry<String, String> header : signed.headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        Request req = builder.build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("全部收藏暂时不可用，请稍后重试");
            String body = resp.body().string().trim();
            if (!body.startsWith("{")) throw new IOException("全部收藏暂时被接口拦截，请稍后再试");
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (optInt(root, "status_code") != 0) throw new IOException("全部收藏暂时不可用，请稍后重试");
            lastCollectionHasMore = optInt(root, "has_more") == 1;
            lastCollectionCursor = lastCollectionHasMore ? firstLong(root, "cursor", "max_cursor") : -1;
            return parseFeed(body);
        }
    }

    public volatile boolean lastSearchHasMore;
    public volatile int lastSearchOffset;
    public volatile String lastSearchId = "";

    /** 综合搜索只保留真正可播的视频条目，混合结果不产生空卡片。 */
    public List<FeedVideo> searchVideos(String keyword, int offset, String searchId) throws IOException {
        Map<String, String> p = baseDeviceParams("190600", "19.6.0");
        p.put("search_channel", "aweme_general");
        p.put("enable_history", "1");
        p.put("filter_selected", "{\"sort_type\":\"0\",\"publish_time\":\"0\",\"filter_duration\":\"\",\"search_range\":\"\",\"content_type\":\"\"}");
        p.put("keyword", keyword == null ? "" : keyword);
        p.put("search_source", "tab_search");
        p.put("query_correct_type", "1");
        p.put("is_filter_search", "0");
        p.put("from_group_id", "");
        p.put("disable_rs", "0");
        p.put("offset", String.valueOf(Math.max(0, offset)));
        p.put("count", "15");
        p.put("need_filter_settings", offset > 0 ? "0" : "1");
        p.put("list_type", "single");
        p.put("pc_search_top_1_params", "{\"enable_ai_search_top_1\":1}");
        p.put("search_id", searchId == null ? "" : searchId);
        JsonObject root = getJson("/aweme/v1/web/general/search/single/", joinAndSign(p),
                "https://www.douyin.com/search/" + urlEncode(keyword) + "?type=general", "视频搜索");
        lastSearchHasMore = optInt(root, "has_more") == 1;
        lastSearchOffset = offset + 15;
        lastSearchId = optString(root, "search_id");
        JsonObject log = objOf(root, "log_pb");
        if (lastSearchId.isEmpty() && log != null) lastSearchId = optString(log, "impr_id");
        List<FeedVideo> out = new ArrayList<>();
        JsonArray data = arrOf(root, "data");
        if (data == null) return parseFeed(root.toString());
        for (JsonElement el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject wrapper = el.getAsJsonObject();
            JsonObject item = objOf(wrapper, "aweme_info");
            if (item == null && wrapper.has("aweme_id")) item = wrapper;
            if (item != null) {
                FeedVideo v = itemToVideo(item);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    public List<UserSummary> searchUsers(String keyword, int offset) throws IOException {
        Map<String, String> p = searchParams(keyword, "aweme_user_web", offset, 15, VER_COMMON_CODE, VER_COMMON_NAME);
        JsonObject root = getJson("/aweme/v1/web/discover/search/", joinAndSign(p),
                "https://www.douyin.com/search/" + urlEncode(keyword) + "?type=user", "用户搜索");
        lastSearchHasMore = optInt(root, "has_more") == 1;
        lastSearchOffset = offset + 15;
        lastSearchId = optString(root, "search_id");
        List<UserSummary> out = new ArrayList<>();
        JsonArray arr = arrOf(root, "user_list");
        if (arr == null) arr = arrOf(root, "data");
        if (arr != null) for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonObject u = objOf(o, "user_info");
            if (u == null) u = objOf(o, "user");
            if (u == null) u = o;
            UserSummary user = parseUserSummary(u);
            if (!user.secUid.isEmpty()) out.add(user);
        }
        return out;
    }

    public static class LiveRoom implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public String webRid = "", title = "", ownerName = "", coverUrl = "", viewerText = "";
    }

    public List<LiveRoom> searchLive(String keyword, int offset) throws IOException {
        Map<String, String> p = searchParams(keyword, "aweme_live", offset, 15, VER_COMMON_CODE, VER_COMMON_NAME);
        JsonObject root = getJson("/aweme/v1/web/live/search/", joinAndSign(p),
                "https://www.douyin.com/search/" + urlEncode(keyword) + "?type=live", "直播搜索");
        lastSearchHasMore = optInt(root, "has_more") == 1;
        lastSearchOffset = offset + 15;
        lastSearchId = optString(root, "search_id");
        if (lastSearchId.isEmpty()) lastSearchId = firstString(root, "log_id", "rid");
        return parseLiveRooms(root);
    }

    private Map<String, String> searchParams(String keyword, String channel, int offset,
                                              int count, String verCode, String verName) {
        Map<String, String> p = baseDeviceParams(verCode, verName);
        p.put("search_channel", channel);
        p.put("keyword", keyword == null ? "" : keyword);
        p.put("search_source", "normal_search");
        p.put("query_correct_type", "1");
        p.put("is_filter_search", "0");
        p.put("from_group_id", "");
        p.put("disable_rs", "0");
        p.put("offset", String.valueOf(offset));
        p.put("count", String.valueOf(count));
        p.put("need_filter_settings", offset > 0 ? "0" : "1");
        p.put("list_type", "single");
        p.put("pc_search_top_1_params", "{\"enable_ai_search_top_1\":1}");
        return p;
    }

    static List<LiveRoom> parseLiveRooms(JsonObject root) {
        List<LiveRoom> out = new ArrayList<>();
        if (root == null) return out;
        JsonArray arr = arrOf(root, "data");
        if (root.has("data") && root.get("data").isJsonObject()) {
            JsonObject container = root.getAsJsonObject("data");
            JsonArray nested = arrOf(container, "data");
            if (nested != null) arr = nested;
        }
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject wrapper = el.getAsJsonObject();
                JsonArray nested = arrOf(wrapper, "data");
                if (nested == null) continue;
                for (JsonElement child : nested) {
                    if (child.isJsonObject()) addLiveRoom(out, child.getAsJsonObject(), wrapper);
                }
            }
            if (!out.isEmpty()) return out;
        }
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject wrapper = el.getAsJsonObject();
            JsonElement lives = wrapper.get("lives");
            if (lives != null && lives.isJsonArray()) {
                for (JsonElement le : lives.getAsJsonArray()) {
                    if (le.isJsonObject()) addLiveRoom(out, le.getAsJsonObject(), wrapper);
                }
            } else if (lives != null && lives.isJsonObject()) {
                addLiveRoom(out, lives.getAsJsonObject(), wrapper);
            } else {
                addLiveRoom(out, wrapper, wrapper);
            }
        }
        return out;
    }

    /** 兜底入口：实际搜索结果的 web_rid 位于字符串化 rawdata 内。 */
    private static void addLiveRoom(List<LiveRoom> out, JsonObject item, JsonObject wrapper) {
        String webRid = findStringRecursive(item, "web_rid", 0);
        if (webRid.isEmpty()) webRid = findStringRecursive(item, "webRid", 0);
        if (webRid.isEmpty()) webRid = firstString(wrapper, "web_rid");
        if (webRid.isEmpty()) return;
        JsonObject room = findObjectRecursive(item, "web_rid", 0);
        if (room == null) room = item;
        LiveRoom live = new LiveRoom();
        live.webRid = webRid;
        live.title = findStringRecursive(item, "title", 0);
        if (live.title.isEmpty()) live.title = firstString(item, "desc");
        JsonObject owner = objOf(item, "owner");
        if (owner == null) owner = objOf(item, "author");
        if (owner == null) owner = findObjectRecursive(item, "nickname", 0);
        if (owner != null) live.ownerName = optString(owner, "nickname");
        live.coverUrl = imageUrl(room, "cover");
        if (live.coverUrl.isEmpty()) live.coverUrl = imageUrl(item, "cover");
        JsonObject stats = objOf(room, "stats");
        if (stats != null) live.viewerText = firstString(stats, "user_count_str", "total_user_str");
        out.add(live);
    }

    /** 只在 JSON 对象/数组及可解析的 rawdata 字符串里找键，限制深度避免异常嵌套。 */
    private static JsonElement rawOrJson(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return value;
        }
        String s = value.getAsString();
        if (!(s.startsWith("{") || s.startsWith("["))) return null;
        try { return JsonParser.parseString(s); } catch (Exception ignored) { return null; }
    }

    private static String findStringRecursive(JsonElement element, String key, int depth) {
        if (element == null || depth > 6) return "";
        JsonElement parsed = rawOrJson(element);
        if (parsed != null && parsed != element) {
            element = parsed;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement direct = object.get(key);
            if (direct != null && direct.isJsonPrimitive()) return direct.getAsString();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String found = findStringRecursive(entry.getValue(), key, depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String found = findStringRecursive(child, key, depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static JsonObject findObjectRecursive(JsonElement element, String key, int depth) {
        if (element == null || depth > 6) return null;
        JsonElement parsed = rawOrJson(element);
        if (parsed != null && parsed != element) element = parsed;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has(key)) return object;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonObject found = findObjectRecursive(entry.getValue(), key, depth + 1);
                if (found != null) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                JsonObject found = findObjectRecursive(child, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static class NoticeItem {
        public String id = "", nickname = "", text = "", time = "", awemeId = "", secUid = "";
        public int group;
        public String imageUrl = "";
    }

    /** 只读通知：特意不传 is_mark_read，不做已读副作用，也不自动后台刷新。 */
    public List<NoticeItem> fetchNotices(int group, long maxTime) throws IOException {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("is_new_notice", "1");
        p.put("notice_group", String.valueOf(group));
        p.put("count", "20");
        p.put("min_time", "0");
        p.put("max_time", String.valueOf(maxTime));
        JsonObject root = getJson("/aweme/v1/web/notice/", joinAndSign(p),
                "https://www.douyin.com/?recommend=1", "互动通知");
        lastNoticeHasMore = optInt(root, "has_more") == 1;
        long next = firstLong(root, "max_time", "cursor");
        lastNoticeCursor = next > 0 ? next : -1;
        return parseNoticesForTest(root, group);
    }

    /** 解析与网络解耦，便于以脱敏样本锁定真实结构。 */
    static List<NoticeItem> parseNoticesForTest(JsonObject root, int group) {
        List<NoticeItem> out = new ArrayList<>();
        JsonArray arr = arrOf(root, "notice_list_v2");
        if (arr == null) arr = arrOf(root, "notice_list");
        if (arr == null) return out;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            NoticeItem item = new NoticeItem();
            item.group = optInt(o, "notice_group");
            if (item.group == 0) item.group = group;
            item.id = firstString(o, "nid_str", "notice_id", "id", "cid");
            if (item.id.isEmpty() && firstLong(o, "nid") > 0) item.id = String.valueOf(firstLong(o, "nid"));
            item.time = formatCommentTime(firstLong(o, "create_time", "time"));
            item.awemeId = firstString(o, "aweme_id", "item_id");
            JsonObject general = objOf(o, "general_notice");
            if (general != null) {
                item.nickname = firstString(general, "sender_name");
                item.text = firstString(general, "content", "title");
            }
            JsonObject follow = objOf(o, "follow");
            JsonObject user = objOf(o, "user");
            if (user == null) user = objOf(o, "from_user");
            if (user == null && follow != null) user = objOf(follow, "from_user");
            if (user != null) {
                if (item.nickname.isEmpty()) item.nickname = optString(user, "nickname");
                item.secUid = optString(user, "sec_uid");
                item.imageUrl = imageUrl(user, "avatar_thumb");
            }
            JsonObject commentWrap = objOf(o, "comment");
            JsonObject comment = commentWrap == null ? null : objOf(commentWrap, "comment");
            if (comment != null) {
                JsonObject cu = objOf(comment, "user");
                if (cu != null) {
                    if (item.nickname.isEmpty()) item.nickname = optString(cu, "nickname");
                    if (item.secUid.isEmpty()) item.secUid = optString(cu, "sec_uid");
                    if (item.imageUrl.isEmpty()) item.imageUrl = imageUrl(cu, "avatar_thumb");
                }
                if (item.text.isEmpty()) item.text = optString(comment, "text");
            }
            JsonObject aweme = objOf(o, "aweme");
            if (aweme == null && commentWrap != null) aweme = objOf(commentWrap, "aweme");
            if (aweme == null) aweme = objOf(o, "aweme_info");
            if (aweme != null) {
                if (item.awemeId.isEmpty()) item.awemeId = optString(aweme, "aweme_id");
                if (item.imageUrl.isEmpty()) item.imageUrl = imageUrl(aweme, "cover");
                if (item.text.isEmpty()) item.text = optString(aweme, "desc");
            }
            if (item.text.isEmpty()) item.text = follow != null ? "关注了你" : noticeText(item.group);
            out.add(item);
        }
        return out;
    }
    public volatile boolean lastNoticeHasMore;
    public volatile long lastNoticeCursor = -1;

    static String noticeText(int group) {
        switch (group) {
            case 401: return "关注了你";
            case 601: return "提到了你";
            case 2: return "评论了你";
            case 3: return "赞了你";
            default: return "新互动";
        }
    }

    public static class LiveStream {
        public String name = "", url = "", codec = "";
        public boolean hls = true;
    }

    /** 公开 web_rid 换取播放地址；不会输出带签名的完整 URL。 */
    public List<LiveStream> enterLive(String webRid) throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("aid", "6383");
        p.put("app_name", "douyin_web");
        p.put("live_id", "1");
        p.put("device_platform", "web");
        p.put("language", "zh-CN");
        p.put("enter_from", "link_share");
        p.put("cookie_enabled", "true");
        p.put("screen_width", "2560");
        p.put("screen_height", "1440");
        p.put("browser_language", "zh-CN");
        p.put("browser_platform", "Win32");
        p.put("browser_name", "Chrome");
        p.put("browser_version", "135.0.0.0");
        p.put("os_name", "Windows");
        p.put("os_version", "10");
        p.put("web_rid", webRid == null ? "" : webRid);
        JsonObject root = getJson("/webcast/room/web/enter/", joinAndSign(p),
                "https://live.douyin.com/" + webRid, "直播间");
        JsonObject data = objOf(root, "data");
        if (data == null) throw new IOException("直播间暂时无法进入，请稍后重试");
        JsonArray rooms = arrOf(data, "data");
        if (rooms == null || rooms.size() == 0 || !rooms.get(0).isJsonObject()) {
            throw new IOException("直播已结束或房间不存在");
        }
        JsonObject room = rooms.get(0).getAsJsonObject();
        JsonObject stream = objOf(room, "stream_url");
        if (stream == null) stream = objOf(objOf(room, "room"), "stream_url");
        List<LiveStream> out = parseLiveStreams(stream);
        if (out.isEmpty()) throw new IOException("直播间没有可播放的 HLS/FLV 地址");
        return out;
    }

    /** 优先 HLS；map 缺失时防御性解析内嵌 stream_data，不假定内层只有一种形式。 */
    static List<LiveStream> parseLiveStreams(JsonObject stream) {
        List<LiveStream> out = new ArrayList<>();
        if (stream == null) return out;
        addUrlMap(out, objOf(stream, "hls_pull_url_map"), true);
        addUrlMap(out, objOf(stream, "flv_pull_url"), false);
        JsonObject sdk = objOf(stream, "live_core_sdk_data");
        if (sdk != null) {
            JsonObject pull = objOf(sdk, "pull_data");
            if (pull != null) {
                String nested = optString(pull, "stream_data");
                if (!nested.isEmpty()) {
                    try {
                        JsonElement el = JsonParser.parseString(nested);
                        collectNestedStreams(el, out, 0);
                    } catch (Exception ignored) { }
                }
            }
        }
        return out;
    }

    private static void addUrlMap(List<LiveStream> out, JsonObject map, boolean hls) {
        if (map == null) return;
        for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String url = entry.getValue().getAsString();
            if (url.startsWith("http://")) url = "https://" + url.substring(7);
            if (!url.startsWith("https://")) continue;
            LiveStream stream = new LiveStream();
            stream.name = entry.getKey();
            stream.url = url;
            stream.hls = hls;
            out.add(stream);
        }
    }

    private static void collectNestedStreams(JsonElement el, List<LiveStream> out, int depth) {
        if (el == null || depth > 7) return;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("url") && o.get("url").isJsonPrimitive()) {
                String url = optString(o, "url");
                if (url.startsWith("http://")) url = "https://" + url.substring(7);
                if (url.startsWith("https://") && (url.contains("m3u8") || url.contains("flv"))) {
                    LiveStream stream = new LiveStream();
                    stream.url = url;
                    stream.hls = url.contains("m3u8");
                    stream.name = firstString(o, "quality_name", "name", "sdk_key");
                    stream.codec = firstString(o, "v_codec", "codec");
                    out.add(stream);
                }
            }
            for (Map.Entry<String, JsonElement> entry : o.entrySet()) collectNestedStreams(entry.getValue(), out, depth + 1);
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) collectNestedStreams(child, out, depth + 1);
        }
    }

    /** 解析评论所需的 cid/回复数/用户身份；DOM 兜底模型没有这些字段时为空。 */
    static com.dywatch.app.chat.model.Comment parseComment(JsonObject c) {
        if (c == null) return null;
        JsonObject u = objOf(c, "user");
        String name = u == null ? "" : optString(u, "nickname");
        com.dywatch.app.chat.model.Comment cm = new com.dywatch.app.chat.model.Comment(
                name, optString(c, "text"), formatCommentTime(optLong(c, "create_time")),
                FeedVideo.formatCount(optLong(c, "digg_count")));
        cm.cid = optString(c, "cid");
        cm.replyCount = optLong(c, "reply_comment_total");
        if (cm.replyCount == 0) cm.replyCount = optLong(c, "reply_count");
        JsonArray preview = arrOf(c, "reply_comment");
        if (preview != null) {
            for (JsonElement reply : preview) {
                if (reply.isJsonObject()) {
                    com.dywatch.app.chat.model.Comment item = parseComment(reply.getAsJsonObject());
                    if (item != null && !item.cid.isEmpty()) cm.previewReplies.add(item);
                }
            }
        }
        if (u != null) {
            cm.secUid = optString(u, "sec_uid");
            cm.avatarUrl = imageUrl(u, "avatar_thumb");
        }
        JsonObject replyTo = objOf(c, "reply_to_user");
        if (replyTo != null) cm.replyToName = optString(replyTo, "nickname");
        JsonObject reply = objOf(c, "reply_to_reply");
        if (reply != null) cm.replyToCid = optString(reply, "cid");
        if (cm.replyToCid.isEmpty()) cm.replyToCid = optString(c, "reply_to_reply_id");
        return cm;
    }

    /** 取前 n 个字符（日志用；换行压成空格，避免 logcat 一行被截断后看不出结构） */
    static String headOf(String s, int n) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    /**
     * 「喜欢」失败原因翻译（纯函数，可 JVM 单测）。
     *
     * 实测（2026-10-25）：`/aweme/v1/web/aweme/favorite/` 回 403 + 纯文本
     * `Blocked by ArgusSecurityPlugin Uifid Not Found`；2026-09-26 时还是 200 正常，
     * 属上游风控收紧，纯 a_bogus 链不够 → 本版只做诚实降级，票据链（UIFID/webSign）列 0.8.5。
     */
    static String favoriteFailureMessage(int httpCode, String body) {
        String b = body == null ? "" : body;
        if (b.contains("ArgusSecurityPlugin") || b.contains("Uifid") || httpCode == 403) {
            return "喜欢列表暂不可用（接口被风控拦截），稍后再试";
        }
        if (!b.trim().startsWith("{") && !b.trim().startsWith("[")) {
            return "喜欢列表暂不可用（接口返回异常），稍后再试";
        }
        if (httpCode != 200) {
            return "喜欢列表暂不可用（HTTP " + httpCode + "），稍后再试";
        }
        return "喜欢列表暂不可用，稍后再试";
    }

    /**
     * 把网络/接口异常翻成人话（纯函数，可 JVM 单测）。
     * 语音：已经是我们自己抛的中文就直接用；否则按关键字归类，**绝不把英文类名甩给用户**。
     */
    public static String friendlyMessage(Throwable t) {
        if (t == null) return "加载失败，请稍后重试";
        String m = t.getMessage();
        if (m != null && !m.isEmpty() && hasChinese(m)) return m;
        String all = (m == null ? "" : m) + " " + t.getClass().getSimpleName();
        if (all.contains("timeout") || all.contains("Timeout") || all.contains("timed out")) {
            return "网络超时，请稍后重试";
        }
        if (all.contains("UnknownHost") || all.contains("Unable to resolve host")) {
            return "网络不可用，请检查连接后重试";
        }
        if (all.contains("MalformedJson") || all.contains("JsonSyntax") || all.contains("JSON")) {
            return "接口返回格式异常，请稍后重试";
        }
        if (all.contains("Socket") || all.contains("Connect") || all.contains("IOException")) {
            return "网络异常，请稍后重试";
        }
        return "加载失败，请稍后重试";
    }

    /** 是否含中日韩统一表意文字（用来判断这条 message 是不是我们自己的中文文案） */
    static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
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


    /** 严格接口用：先完整编码业务参数，再由调用方对结果做 a_bogus。 */
    private static String joinQuery(Map<String, String> p) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return q.toString();
    }

    /**
     * 拼 query 并签名（LinkedHashMap 保序：a_bogus 必须对最终串算）。
     *
     * ⚠️ 2026-11-07 审计 M10：原来 key/value **原样拼**，搜索词里的 `&` `#` `+` `%` 空格
     * 会变成额外参数、被截断、或被服务端按 `+`=空格 解释 —— 搜 `a&b` 实际搜的是 `a`。
     *
     * 修法刻意做得**很窄**：只对会破坏 query 结构的 5 个字符做百分号编码
     * （`& # + % 空格`），其余（含 `{}"` `:` `,` —— `filter_selected` 这类 JSON 参数里有）
     * 一律原样保留，因此对现有全部接口**逐字节等价**，a_bogus 的输入不变、签名不受影响。
     * 完整编码路径（joinQuery）留给需要严格编码的接口，不动。
     */
    private String joinAndSign(Map<String, String> p) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(e.getKey()).append('=').append(escapeQueryValue(e.getValue()));
        }
        return q + "&a_bogus=" + urlEncode(signer.makeABogus(q.toString()));
    }

    /**
     * 只转义会破坏 query 结构的字符（审计 M10）。纯函数，可单测。
     *
     * 为什么不用 URLEncoder：它会把空格编成 `+`（表单语义，query 里再被解回空格）、
     * 并把 `{}" :,` 等一律编码，导致 `filter_selected` 的 JSON 参数变形。
     */
    static String escapeQueryValue(String v) {
        if (v == null || v.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&': sb.append("%26"); break;
                case '#': sb.append("%23"); break;
                case '+': sb.append("%2B"); break;
                case '%': sb.append("%25"); break;
                case ' ': sb.append("%20"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
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
        String body;
        try {
            body = requestUserPosts(query, secUid, false);
        } catch (IOException first) {
            body = "";
        }
        if (body.trim().isEmpty() || !body.trim().startsWith("{")) {
            // 2026-11-01：主页作品接口也会被 Argus 挡成 HTML/空响应。收藏夹和详情
            // 已证明本地 webSign 能过同一层保护，这里做一次带签重试；仍失败再报人话。
            AppLog.i("api", "作品列表首次响应非 JSON，尝试 webSign 重试");
            body = requestUserPosts(query, secUid, true);
        }
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) throw new IOException("作品列表暂时没有返回内容，请稍后再试");
        if (!trimmed.startsWith("{")) {
            throw new IOException("作品列表暂时被接口拦截，请稍后再试");
        }
        List<FeedVideo> list = parseFeed(trimmed);
        // 翻页游标（下一批从这继续；-1 = 没有更多）。响应里有 has_more/max_cursor，
        // 不记的话调用方只能反复拉第一页
        com.google.gson.JsonElement rootEl = com.google.gson.JsonParser.parseString(trimmed);
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
            int code = optInt(rootEl.getAsJsonObject(), "status_code");
            if (code != 0) throw new IOException("作品列表 status=" + code);
        }
        return list;
    }

    /** 作品列表单次请求；webSign=true 时追加本地签名参数与请求头。 */
    private String requestUserPosts(String query, String secUid, boolean webSign)
            throws IOException {
        String finalQuery = query;
        Map<String, String> signedHeaders = java.util.Collections.emptyMap();
        if (webSign) {
            SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(query, fullCookie());
            finalQuery = signed.query;
            signedHeaders = signed.headers;
        }
        Request.Builder builder = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/post/?" + finalQuery)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/user/" + (secUid == null ? "" : secUid))
                .header("Accept", "application/json")
                .header("Cookie", fullCookie());
        for (Map.Entry<String, String> header : signedHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        try (Response resp = client.newCall(builder.build()).execute()) {
            if (resp.body() == null) throw new IOException("作品列表暂时不可用，请稍后再试");
            int http = resp.code();
            String body = resp.body().string();
            AppLog.i("api", "作品列表 HTTP " + http + " 响应长度=" + body.length()
                    + " webSign=" + webSign);
            if (!resp.isSuccessful()) {
                throw new IOException("作品列表 HTTP " + http);
            }
            return body;
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
        SecSdkWebSigner.Result signed = SecSdkWebSigner.sign(joinAndSign(p), fullCookie());
        Request.Builder builder = new Request.Builder()
                .url("https://www.douyin.com/aweme/v1/web/aweme/detail/?" + signed.query)
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .header("Cookie", fullCookie());
        for (Map.Entry<String, String> header : signed.headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        Request req = builder.build();
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
                com.dywatch.app.chat.model.Comment cm = parseComment(c);
                out.add(cm);
            }
            return out;
        }
    }

    /** 一级评论的回复；独立页面分页，不嵌套进评论卡片。 */
    public List<com.dywatch.app.chat.model.Comment> fetchCommentReplies(
            String awemeId, String commentId, long cursor, int count) throws IOException {
        Map<String, String> p = baseDeviceParams(VER_COMMON_CODE, VER_COMMON_NAME);
        p.put("item_id", awemeId == null ? "" : awemeId);
        p.put("comment_id", commentId == null ? "" : commentId);
        p.put("cut_version", "1");
        p.put("cursor", String.valueOf(cursor));
        p.put("count", String.valueOf(count));
        p.put("item_type", "0");
        String verifyFp = cookieValue("s_v_web_id");
        String webid = cookieValue("webid");
        String uifid = cookieValueAny("uifid", "uifid_temp", "uifidtemp", "UIFID", "UIFID_TEMP");
        if (!webid.isEmpty()) p.put("webid", webid);
        if (!uifid.isEmpty()) p.put("uifid", uifid);
        if (!verifyFp.isEmpty()) {
            p.put("verifyFp", verifyFp);
            p.put("fp", verifyFp);
        }
        String msToken = cookieValue("msToken");
        if (!msToken.isEmpty()) p.put("msToken", msToken);
        String baseQuery = joinQuery(p);
        String query = baseQuery + "&a_bogus="
                + urlEncode(signer.makeABogusWithMagic(baseQuery, 8));
        Map<String, String> extra = new LinkedHashMap<>();
        if (!uifid.isEmpty()) extra.put("uifid", uifid);
        JsonObject root = getJson("/aweme/v1/web/comment/list/reply/", query,
                "https://www.douyin.com/video/" + awemeId, "评论回复", extra);
        lastCommentHasMore = optInt(root, "has_more") == 1;
        lastCommentCursor = optLong(root, "cursor");
        List<com.dywatch.app.chat.model.Comment> out = new ArrayList<>();
        JsonArray arr = arrOf(root, "comments");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) out.add(parseComment(el.getAsJsonObject()));
            }
        }
        return out;
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

    private String cookieValue(String name) {
        for (String part : fullCookie().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private String cookieValueAny(String... names) {
        for (String name : names) {
            String value = cookieValue(name);
            if (!value.isEmpty()) return value;
        }
        return "";
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
        // ⚠️ 2026-11-07 审计 M11：把"我是否已赞/已收藏"读出来。
        //    原来一律默认 false，于是从"喜欢"/收藏夹进播放页时，用户已赞的内容显示未赞，
        //    首次点击会把"取消点赞"当成"点赞"发出去（计数与状态都会错）。
        //    字段来源：aweme 顶层 user_digged（0/1）、collect_stat（0/1，与页面一致）。
        v.liked = optLong(item, "user_digged") == 1;
        v.collected = optLong(item, "collect_stat") == 1;
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
    static String firstString(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            String value = optString(o, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    static long firstLong(JsonObject o, String... keys) {
        if (o == null) return 0;
        for (String key : keys) {
            JsonElement e = o.get(key);
            if (e != null && !e.isJsonNull()) {
                try { return e.getAsLong(); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

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
        if (o == null) return 0;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            if (e.getAsJsonPrimitive().isString()) {
                String value = e.getAsString().trim();
                if (value.isEmpty()) return 0;
                return (long) Double.parseDouble(value);
            }
            return e.getAsLong();
        } catch (Exception ignored) { return 0; }
    }

    private static int optInt(JsonObject o, String key) {
        long value = optLong(o, key);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
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
