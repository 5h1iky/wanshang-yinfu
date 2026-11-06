package com.dywatch.app.chat.api;

// 发送链路的凭证（2026-11-07 真机全链路验证）。
//
// 来源 **全部是抖音网页 SDK 自己存在浏览器里的东西**，我们只是"读出来存一份"：
//   IndexedDB `secure-store` / objectStore `cryptvalues`
//     · security-sdk/s_sdk_crypt_sdk        → {"ec_privateKey": PEM}     客户端 EC 私钥
//     · security-sdk/s_sdk_sign_data_key/web_protect → {"ticket","ts_sign","client_cert"}
//   localStorage
//     · security-sdk/s_sdk_server_cert_key  → {"cert": PEM, "sn": …}     服务端 ECIES 证书
//
// ⚠️ 这些是**账号级敏感凭证**，与 cookie 同级：
//   1. 只存本机 SharedPreferences（与 LoginManager 同一套口径，不加密但不出设备）；
//   2. 绝不写日志（连长度都只在调试时打）；
//   3. 退出登录必须一起清（见 LoginManager.clear）。
//
// ⚠️ ticket/ts_sign 会过期。过期后发送会失败，此时由 {@link CredExtractor} 重新从网页读一次。

import android.content.Context;
import android.content.SharedPreferences;

public final class ImCreds {

    private static final String PREF = "dywatch_im_creds";
    private static final String K_PRIV = "ec_private_key";
    private static final String K_TICKET = "ticket";
    private static final String K_TS_SIGN = "ts_sign";
    private static final String K_CLIENT_CERT = "client_cert";
    private static final String K_SERVER_CERT = "server_cert";
    /** 提取时间戳（用于判断是否需要刷新） */
    private static final String K_AT = "extracted_at";

    /** ticket 组的有效期：保守按 30 分钟（真机实测远超这个窗口，但宁可多刷一次） */
    private static final long TTL_MS = 30 * 60 * 1000L;

    public String ecPrivateKey = "";
    public String ticket = "";
    public String tsSign = "";
    public String clientCert = "";
    public String serverCert = "";
    public long extractedAt;

    public boolean complete() {
        return !ecPrivateKey.isEmpty() && !ticket.isEmpty()
                && !tsSign.isEmpty() && !serverCert.isEmpty();
    }

    public boolean fresh() {
        return complete() && (System.currentTimeMillis() - extractedAt) < TTL_MS;
    }

    // ---- 读写 ----

    public static ImCreds load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ImCreds c = new ImCreds();
        c.ecPrivateKey = sp.getString(K_PRIV, "");
        c.ticket = sp.getString(K_TICKET, "");
        c.tsSign = sp.getString(K_TS_SIGN, "");
        c.clientCert = sp.getString(K_CLIENT_CERT, "");
        c.serverCert = sp.getString(K_SERVER_CERT, "");
        c.extractedAt = sp.getLong(K_AT, 0L);
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_PRIV, ecPrivateKey)
                .putString(K_TICKET, ticket)
                .putString(K_TS_SIGN, tsSign)
                .putString(K_CLIENT_CERT, clientCert)
                .putString(K_SERVER_CERT, serverCert)
                .putLong(K_AT, extractedAt)
                .apply();
    }

    /** 退出登录时调用（与 cookie 一起清） */
    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** 日志安全的自述：只说"有没有"，不说内容 */
    public String describe() {
        return "cred(priv=" + (ecPrivateKey.isEmpty() ? 0 : 1)
                + " ticket=" + ticket.length()
                + " tssign=" + tsSign.length()
                + " cert=" + (serverCert.isEmpty() ? 0 : 1)
                + " age=" + ((System.currentTimeMillis() - extractedAt) / 1000) + "s)";
    }
}
