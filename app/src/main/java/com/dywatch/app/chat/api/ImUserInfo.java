package com.dywatch.app.chat.api;

// 昵称/头像查询（www-hj.douyin.com/aweme/v1/web/im/user/info/）。
//
// 为什么需要它：get_message_by_init 的会话里**只有 uid / sec_uid，没有昵称**
// （2026-11-07 实测），而 UI 的列表行要显示昵称。页面自己也是靠这个接口批量查的。
//
// ⚠️ 这个接口和 imapi 的 protobuf 接口不同：
//   · 它是普通 JSON 表单接口，在 www-hj 域；
//   · **要带签名参数**（a_bogus/msToken/uifid 等）—— 但实测只带 uifid 也能通过，
//     说明它对签名是"弱校验"。这里保守起见带上 uifid（从 cookie 的 UIFID_TEMP 取）。
//   · 一次可传多个 sec_uid（实测 2 个 → 返回 3KB），适合批量补昵称。

import com.dywatch.app.util.AppLog;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ImUserInfo {

    private static final String TAG = "imuser";
    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    private static final String URL = "https://www-hj.douyin.com/aweme/v1/web/im/user/info/";

    /** sec_uid → (昵称, 头像) */
    public static final class Profile {
        public String nickname = "";
        public String avatar = "";
    }

    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 批量查昵称。
     *
     * @param secUids 对方的 sec_uid 列表（不要带自己的）
     * @return sec_uid → Profile；失败返回空 map（不抛，昵称缺失不该阻断列表）
     */
    public Map<String, Profile> fetch(String cookie, String uifid, List<String> secUids) {
        Map<String, Profile> out = new HashMap<>();
        if (secUids == null || secUids.isEmpty()) return out;

        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String s : secUids) if (s != null && !s.isEmpty()) arr.put(s);

            FormBody body = new FormBody.Builder()
                    .add("sec_user_ids", arr.toString())
                    .build();

            Request req = new Request.Builder()
                    .url(URL + "?" + commonQuery(uifid))
                    .header("accept", "application/json")
                    .header("user-agent", UA)
                    .header("referer", "https://www.douyin.com/chat")
                    .header("cookie", cookie == null ? "" : cookie)
                    .header("uifid", uifid == null ? "" : uifid)
                    .post(body)
                    .build();

            try (Response resp = mClient.newCall(req).execute()) {
                if (resp.body() == null) {
                    AppLog.i(TAG, "昵称查询无响应体");
                    return out;
                }
                String text = resp.body().string();
                JSONObject j;
                try {
                    j = new JSONObject(text);
                } catch (org.json.JSONException e) {
                    AppLog.i(TAG, "昵称查询非 JSON http=" + resp.code() + " len=" + text.length());
                    return out;
                }
                int code = j.optInt("status_code", -1);
                if (code != 0) {
                    AppLog.i(TAG, "昵称查询 status=" + code + " msg=" + j.optString("status_msg", ""));
                    return out;
                }
                // ⚠️ data 可能是对象（实测 {"0":{...}}）也可能是数组（客户端/版本差异），两种都吃
                JSONObject data = j.optJSONObject("data");
                org.json.JSONArray dataArr = data == null ? j.optJSONArray("data") : null;
                if (data == null && dataArr == null) {
                    AppLog.i(TAG, "昵称查询 data 为空 http=" + resp.code() + " len=" + text.length());
                    return out;
                }
                List<JSONObject> users = new ArrayList<>();
                if (data != null) {
                    java.util.Iterator<String> keys = data.keys();
                    while (keys.hasNext()) {
                        JSONObject u = data.optJSONObject(keys.next());
                        if (u != null) users.add(u);
                    }
                } else {
                    for (int i = 0; i < dataArr.length(); i++) {
                        JSONObject u = dataArr.optJSONObject(i);
                        if (u != null) users.add(u);
                    }
                }
                for (JSONObject u : users) {
                    String sec = u.optString("sec_uid", "");
                    if (sec.isEmpty()) continue;
                    Profile p = new Profile();
                    p.nickname = u.optString("nickname", "");
                    JSONObject av = u.optJSONObject("avatar_thumb");
                    if (av != null) {
                        org.json.JSONArray urls = av.optJSONArray("url_list");
                        if (urls != null && urls.length() > 0) p.avatar = urls.optString(0, "");
                    }
                    out.put(sec, p);
                }
                AppLog.i(TAG, "昵称查询 ok n=" + out.size() + " 请求 " + secUids.size());
            }
        } catch (Throwable e) {
            AppLog.i(TAG, "昵称查询失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        return out;
    }

    private static String commonQuery(String uifid) {
        return "device_platform=webapp&aid=6383&channel=channel_pc_web"
                + "&pc_client_type=1&version_code=170400&version_name=17.4.0"
                + "&cookie_enabled=true&platform=PC"
                + "&screen_width=393&screen_height=873"
                + "&browser_language=zh-CN&browser_platform=Win32"
                + "&browser_name=Chrome&browser_version=135.0.0.0&browser_online=true"
                + "&engine_name=Blink&engine_version=135.0.0.0"
                + "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8"
                + (uifid == null || uifid.isEmpty() ? "" : "&uifid=" + uifid);
    }

    /** 从整串 cookie 里取 UIFID_TEMP（就是页面用的 uifid） */
    public static String uifidFromCookie(String cookie) {
        if (cookie == null) return "";
        for (String p : cookie.split(";")) {
            String s = p.trim();
            if (s.startsWith("UIFID_TEMP=")) return s.substring("UIFID_TEMP=".length());
        }
        return "";
    }
}
