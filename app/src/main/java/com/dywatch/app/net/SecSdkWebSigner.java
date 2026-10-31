package com.dywatch.app.net;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 抖音 webSign 保护接口的本地签名。
 *
 * 算法来自参考实现 dtk.signing.native.websign（已用浏览器签名逐字节核对）：
 * md5(uifid + "_" + timestamp + "_" + SALT + "_" + query)。
 * 只从本机会话 Cookie 取签名所需标识，不写日志、不上传。
 */
public final class SecSdkWebSigner {
    private static final String SALT = "A96D855A08C0A9707F8BEF0D9A527E4E";
    private static final String SIGNATURE_PARAM = "x-secsdk-web-signature";
    private static final String UIFID_PARAM = "uifid";
    private static final String TIMESTAMP_PARAM = "timestamp";
    private static final String EXPIRE_HEADER = "x-secsdk-web-expire";

    public static final class Result {
        public final String query;
        public final Map<String, String> headers;
        Result(String query, Map<String, String> headers) {
            this.query = query;
            this.headers = headers;
        }
    }

    private SecSdkWebSigner() { }

    /** 没有 UIFID 时原样返回 query，让受保护接口明确报错而不是伪造身份。 */
    public static Result sign(String baseQuery, String cookieHeader) {
        return signAt(baseQuery, cookieHeader, System.currentTimeMillis() / 1000);
    }

    static Result signAt(String baseQuery, String cookieHeader, long epochSec) {
        Map<String, String> cookies = parseCookies(cookieHeader);
        String uifid = first(cookies, "uifid", "uifid_temp", "uifidtemp",
                "UIFID", "UIFID_TEMP", "UIFIDTEMP");
        Map<String, String> headers = new LinkedHashMap<>();
        if (uifid.isEmpty()) return new Result(baseQuery, headers);
        String stamp = String.valueOf(epochSec);
        StringBuilder covered = new StringBuilder(baseQuery);
        String fp = first(cookies, "s_v_web_id");
        if (!fp.isEmpty()) {
            covered.append("&verifyFp=").append(encode(fp));
            covered.append("&fp=").append(encode(fp));
        }
        covered.append('&').append(UIFID_PARAM).append('=').append(encode(uifid));
        covered.append('&').append(TIMESTAMP_PARAM).append('=').append(stamp);
        String query = covered.toString();
        String signature = md5(uifid + "_" + stamp + "_" + SALT + "_" + query);
        headers.put(UIFID_PARAM, uifid);
        headers.put(SIGNATURE_PARAM, signature);
        headers.put(EXPIRE_HEADER, stamp);
        return new Result(query + "&" + SIGNATURE_PARAM + "=" + signature, headers);
    }

    static Map<String, String> parseCookies(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return out;
    }

    private static String first(Map<String, String> values, String... names) {
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
