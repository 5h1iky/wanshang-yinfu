package com.dywatch.app.chat.api;

// IM 发送链路（2026-11-07 真机验证成功）。
//
// 完整链条（每一步都是实测踩出来的，改一行都要重新验）：
//   ① GET  /passport/safe/get_identity_security_token/   ← 纯 cookie，**不需要 a_bogus**
//   ② ECDH(客户端私钥, 服务端证书公钥) → HKDF-SHA256 → 32B HMAC key
//   ③ req_sign = HMAC(key, "ticket=<t>&path=/v1/message/send&timestamp=<ts>")
//   ④ 5 个 bd-ticket-guard-* 请求头
//   ⑤ POST /v1/message/send  protobuf，identity token 放 f15（**JSON 包装**）
//
// ⚠️ 失败代价极高：漏了 identity_security_token 或 ticket-guard 头，服务端会回
//    `decision: KICK` 并**把用户踢下线**（历史两次 KICK 的根因）。
//    所以本类里任何"拿不到凭证"的情况都必须**提前放弃**，绝不带残缺的链去请求。
//
// ⚠️ 报错信息会骗人：发错会话会收到"你已不在群聊内"/"仅支持与互关朋友私信"这类
//    业务错误，与协议无关（调研\20 §3.1）。判断是不是协议问题要看 `decision_type`。

import com.dywatch.app.util.AppLog;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class ImSend {

    private static final String TAG = "imsend";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String IM_BASE = "https://imapi.douyin.com";
    private static final String PATH_SEND = "/v1/message/send";
    private static final String TOKEN_URL =
            "https://www.douyin.com/passport/safe/get_identity_security_token/";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private static final MediaType PROTO = MediaType.parse("application/x-protobuf");
    private static final int CMD_SEND = 100;
    private static final int MSG_TYPE_TEXT = 7;

    /** identity token 有效期约 240s，本地提前 60s 作废，避免"差一秒过期"的边界失败 */
    private static final long TOKEN_TTL_MS = 180_000L;

    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    private String mCookie = "";

    /** 领到的 identity token 与其绑定信息（token 与会话绑定，不能跨会话复用） */
    private String mToken = "";
    private String mDeviceId = "";
    private long mTokenAt;

    public ImSend setCookie(String cookie) {
        mCookie = cookie == null ? "" : cookie;
        return this;
    }

    /** 发送结果 */
    public static final class Result {
        public boolean ok;
        /** 服务端 status_code（0 = 成功） */
        public int code;
        public String message = "";
        /** 风控判定：空 = 无风控；KICK = 会话被踢 */
        public String decision = "";
        public long serverMessageId;
        /**
         * 解析出的新 ticket（响应 f6.f100.f9）。
         *
         * ⚠️ 该字段在项目使用的 SendMessageResponse proto 里**没有定义**（MessageInfo 只有
         * f1/f3/f4/f5/f6），语义未确认。不能直接写 ImCreds.ticket（ticket-guard 的 HMAC
         * 材料，写错会让后续发送全部签名失败甚至 KICK）；只能经
         * {@link #nextConvTicket(String, String)} 形态校验后，当作会话级 ticket 在本进程短暂使用。
         */
        public String newTicket = "";

        @Override
        public String toString() {
            return "ok=" + ok + " code=" + code + " decision=" + (decision.isEmpty() ? "''" : decision)
                    + " msg=" + message;
        }
    }

    /**
     * 发一条文本消息。
     *
     * @param convId    会话 id（"0:1:&lt;uid&gt;:&lt;uid&gt;"）
     * @param shortId   会话 short id
     * @param ticket    会话级 ticket（init 响应里带的 121 字符）
     * @param creds     发送凭证（EC 私钥 / ticket / ts_sign / 服务端证书）
     * @param text      正文
     */
    public Result sendText(String convId, long shortId, String convTicket,
                           ImCreds creds, String text) throws IOException {
        if (mCookie == null || !mCookie.contains("sessionid=")) {
            throw new IOException("未登录（缺少 sessionid）");
        }
        // ⚠️ 硬闸门：凭证不全就不发。带残缺链去请求 = 可能被 KICK。
        if (creds == null || !creds.complete()) {
            throw new IOException("发送凭证不完整（需先在私信页提取一次）");
        }
        if (convId == null || convId.isEmpty() || convTicket == null || convTicket.isEmpty()) {
            throw new IOException("会话信息不完整");
        }

        // ① identity token（按会话缓存，token 与会话绑定）
        String token = ensureToken(convId);
        if (token.isEmpty()) throw new IOException("未取到 identity_security_token（发送已放弃，避免被踢）");

        // ②③④ ticket-guard 头
        Tickets t = buildTickets(creds, PATH_SEND);

        // ⑤ protobuf
        String clientMsgId = java.util.UUID.randomUUID().toString();
        byte[] payload = buildSendPayload(convId, shortId, convTicket, text, clientMsgId);
        byte[] body = buildEnvelope(CMD_SEND, randomSeq(), payload, token, mDeviceId);

        Request req = new Request.Builder()
                .url(IM_BASE + PATH_SEND)
                .header("accept", "application/x-protobuf")
                .header("content-type", "application/x-protobuf")
                .header("referer", "https://www.douyin.com/")
                .header("user-agent", UA)
                .header("cookie", mCookie)
                .header("bd-ticket-guard-client-data", t.clientData)
                .header("bd-ticket-guard-ree-public-key", t.reeKey)
                .header("bd-ticket-guard-version", "2")
                .header("bd-ticket-guard-web-version", t.webVersion)
                .header("bd-ticket-guard-web-sign-type", "1")
                .post(RequestBody.create(body, PROTO))
                .build();

        try (Response resp = mClient.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("发送空响应");
            byte[] bytes = resp.body().bytes();
            if (!resp.isSuccessful()) throw new IOException("发送 HTTP " + resp.code());
            return parseSendResponse(bytes);
        }
    }

    // ---------- identity token ----------

    /**
     * 领 identity_security_token。
     *
     * ⚠️ 2026-11-07 实测：**不带 a_bogus / msToken / verifyFp 也能拿到**（纯 cookie）。
     *    这直接决定了全链路能否纯 Java——之前担心的"必须在有 SDK 的页面里才领得到"是错的。
     */
    private String ensureToken(String convId) throws IOException {
        if (!mToken.isEmpty() && (System.currentTimeMillis() - mTokenAt) < TOKEN_TTL_MS) {
            return mToken;
        }
        String qs = "passport_jssdk_version=4.2.3&passport_jssdk_type=lite"
                + "&is_from_ttaccountsdk=1&aid=6383&language=zh&scene=web_im"
                + "&auto_retry_req=0&skip_verify=false&identity_token_force_get_tag=0"
                + "&id_token_version=1.2.10";
        Request req = new Request.Builder()
                .url(TOKEN_URL + "?" + qs)
                .header("accept", "application/json, text/javascript")
                .header("referer", "https://www.douyin.com/chat")
                .header("user-agent", UA)
                .header("cookie", mCookie)
                .get()
                .build();
        try (Response resp = mClient.newCall(req).execute()) {
            if (resp.body() == null) return "";
            String text = resp.body().string();
            String token = jsonString(text, "identity_security_token");
            String decision = jsonString(text, "decision");
            String deviceId = jsonString(text, "device_id");
            if (!decision.isEmpty()) {
                // 有风控判定：不发，避免把账号推向 KICK
                AppLog.i(TAG, "领 token 被风控拦: decision=" + decision);
                return "";
            }
            if (token.isEmpty()) {
                AppLog.i(TAG, "领 token 失败（响应 " + text.length() + "B）");
                return "";
            }
            mToken = token;
            mDeviceId = deviceId;
            mTokenAt = System.currentTimeMillis();
            AppLog.i(TAG, "领到 identity token（len=" + token.length() + " device=" + deviceId.length() + "）");
            return token;
        }
    }

    // ---------- ticket-guard ----------

    static final class Tickets {
        String clientData;
        String reeKey;
        /** ts_sign 以 "ts.1" 开头时服务端要求 web-version=1，否则 2 */
        String webVersion;
    }

    /**
     * 构造 bd-ticket-guard 三件套（纯函数，可单测）。
     *
     * req_sign 的签名串格式：`ticket=<ticket>&path=<path>&timestamp=<ts>`
     * （path 是**接口路径**，不含域名与参数）
     */
    static Tickets buildTickets(ImCreds creds, String path) throws IOException {
        try {
            Object[] parsed = Ec.parsePkcs8Ec(Ec.pemToDer(creds.ecPrivateKey));
            BigInteger priv = (BigInteger) parsed[0];
            Ec.Pt selfPub = (Ec.Pt) parsed[1];

            Ec.Pt serverPub = Ec.parseSpkiPoint(Ec.pemToDer(creds.serverCert));
            byte[] shared = Ec.ecdh(priv, serverPub);
            byte[] key = Ec.hkdfSha256(shared);

            long ts = System.currentTimeMillis() / 1000L;
            String signBase = "ticket=" + creds.ticket + "&path=" + path + "&timestamp=" + ts;
            String reqSign = Ec.B64.encode(Ec.hmacSha256(key, Ec.utf8(signBase)));

            String tsSignJson = jsonQuote(creds.tsSign);
            String reqContentJson = jsonQuote("ticket,path,timestamp");
            String signJson = jsonQuote(reqSign);
            String clientDataJson = "{\"ts_sign\":" + tsSignJson
                    + ",\"req_content\":" + reqContentJson
                    + ",\"req_sign\":" + signJson
                    + ",\"timestamp\":" + ts
                    + ",\"t_trust\":1}";

            byte[] myPub = new byte[65];
            myPub[0] = 0x04;
            System.arraycopy(Ec.fixed32(selfPub.x), 0, myPub, 1, 32);
            System.arraycopy(Ec.fixed32(selfPub.y), 0, myPub, 33, 32);

            Tickets t = new Tickets();
            t.clientData = Ec.B64.encode(Ec.utf8(clientDataJson));
            t.reeKey = Ec.B64.encode(myPub);
            t.webVersion = creds.tsSign != null && creds.tsSign.startsWith("ts.1") ? "1" : "2";
            return t;
        } catch (RuntimeException e) {
            throw new IOException("ticket-guard 构造失败: " + e.getMessage(), e);
        }
    }

    // ---------- protobuf ----------

    static byte[] buildSendPayload(String convId, long shortId, String convTicket,
                                   String text, String clientMsgId) {
        String content = "{\"aweType\":700,\"type\":0,\"richTextInfos\":[],\"text\":" + jsonQuote(text) + "}";
        String stime = System.currentTimeMillis() + "." + String.format(java.util.Locale.US, "%05d",
                (int) (Math.random() * 100000));
        return Pb.writer()
                .string(1, convId)
                .varint(2, 1)                                  // conversation_type
                .varint(3, shortId)
                .string(4, content)
                .message(5, ext("s:mentioned_users", ""))
                .message(5, ext("s:client_message_id", clientMsgId))
                .message(5, ext("s:stime", stime))
                .varint(6, MSG_TYPE_TEXT)
                .string(7, convTicket)
                .string(8, clientMsgId)
                .toByteArray();
    }

    private static byte[] ext(String k, String v) {
        return Pb.writer().string(1, k).string(2, v).toByteArray();
    }

    /**
     * 发送信封。与读接口的信封有两处不同（实测）：
     *   f2 = 随机 sequence_id（10000~11000）
     *   f6 = **0**（不是读接口的 1）
     *   f15 多 3 组 identity_security_*（token 要 **JSON 包装** {"token":"…"}）
     */
    static byte[] buildEnvelope(int cmd, int seq, byte[] payload, String identityToken, String deviceId) {
        Pb.Writer env = Pb.writer()
                .varint(1, cmd)
                .varint(2, seq)
                .string(3, "0.1.8")
                .string(4, "")
                .varint(5, 3)
                .varint(6, 0)                                  // ⚠️ inbox_type = 0
                .string(7, "0d50935:feat/pc-im-group")
                .bytes(8, Pb.writer().message(cmd, payload).toByteArray())
                .string(9, "0")
                .string(11, "douyin_pc")
                .string(14, "360000");

        if (identityToken != null && !identityToken.isEmpty()) {
            // ⚠️ 是 JSON，不是裸串（裸串 → empty token / KICK）
            env.message(15, kv("identity_security_token",
                    "{\"token\":" + jsonQuote(identityToken) + "}"));
            env.message(15, kv("identity_security_device_id", deviceId == null ? "" : deviceId));
            env.message(15, kv("identity_security_aid", ""));
        }
        env.message(15, kv("session_aid", "6383"));
        env.message(15, kv("session_did", "0"));
        env.message(15, kv("app_name", "douyin_pc"));
        env.message(15, kv("priority_region", "cn"));
        env.message(15, kv("user_agent", UA));
        env.message(15, kv("cookie_enabled", "true"));
        env.message(15, kv("browser_language", "zh-CN"));
        env.message(15, kv("browser_platform", "Win32"));
        env.message(15, kv("browser_name", "Chrome"));
        env.message(15, kv("browser_version", "135.0.0.0"));
        env.message(15, kv("browser_online", "true"));
        env.message(15, kv("screen_width", "393"));
        env.message(15, kv("screen_height", "873"));
        env.message(15, kv("referer", ""));
        env.message(15, kv("timezone_name", "Asia/Shanghai"));
        env.message(15, kv("deviceId", "0"));
        env.message(15, kv("is-retry", "0"));
        env.varint(18, 1);
        env.string(21, "douyin_web");
        env.string(22, "web_sdk");
        return env.toByteArray();
    }

    private static byte[] kv(String k, String v) {
        return Pb.writer().string(1, k).string(2, v).toByteArray();
    }

    private static int randomSeq() {
        return 10000 + (int) (Math.random() * 1000);
    }

    /** 解析发送响应（SendMessageResponseBody 在 f6.f100，风控判定在顶层 f14） */
    static Result parseSendResponse(byte[] resp) {
        Result r = new Result();
        try {
            java.util.List<Pb.Field> top = Pb.decode(resp);
            Pb.Field st = Pb.first(top, 4);
            r.message = st == null ? "" : st.asString();
            r.code = (int) num(top, 3);
            r.decision = str(top, 14);

            java.util.List<Pb.Field> body = Pb.nested(Pb.nested(top, 6), CMD_SEND);
            if (!body.isEmpty()) {
                r.serverMessageId = num(body, 1);
                r.newTicket = str(body, 9);
                int innerStatus = (int) num(body, 3);
                String checkMsg = str(body, 6);
                if (innerStatus != 0 || !checkMsg.isEmpty()) {
                    r.message = checkMsg.isEmpty() ? r.message : checkMsg;
                }
            }
            r.ok = r.code == 0 && r.decision.isEmpty() && "OK".equalsIgnoreCase(r.message);
            if (r.decision.isEmpty() && !r.ok) {
                // 业务错误（发错会话/仅互关可发…），不是协议错误——先怀疑目标对象
                AppLog.i(TAG, "发送未成功（无风控）: code=" + r.code + " msg=" + r.message);
            }
        } catch (RuntimeException e) {
            r.ok = false;
            r.message = "响应解析失败";
        }
        return r;
    }

    /**
     * 谨慎采用发送响应里的轮换会话 ticket。
     *
     * 为什么只做“形态校验后短期采用”，不落 ImCreds：
     *   · 会话 ticket（发送体 f7）实测约 121 字符、可见 ASCII；
     *   · 响应 f9 不在公开 proto 的 MessageInfo 里，无法确认它一定是会话 ticket；
     *   · ImCreds.ticket 是 ticket-guard 的账号级 HMAC 材料，写错的风险是发送全失败/KICK。
     * 因此只在候选值看起来确实像会话 ticket 时替换**内存里的 mConvTicket**；
     * 每轮 init 仍会用服务端最新值覆盖，进程重启不依赖这个字段。
     */
    public static String nextConvTicket(String current, String candidate) {
        String cur = current == null ? "" : current;
        if (candidate == null) return cur;
        String c = candidate.trim();
        if (c.length() < 100 || c.length() > 200) return cur;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch < 0x21 || ch > 0x7e) return cur;
        }
        return c;
    }

    // ---------- JSON 小工具（不引 Gson：这里只要读几个字符串字段） ----------

    /** 读 JSON 里某个 key 的字符串值（够用即可：抖音这几个字段都是顶层字符串） */
    static String jsonString(String json, String key) {
        if (json == null) return "";
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return "";
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (int q = p + 1; q < json.length(); q++) {
            char c = json.charAt(q);
            if (c == '\\' && q + 1 < json.length()) {
                char n = json.charAt(++q);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        if (q + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(q + 1, q + 5), 16));
                                q += 4;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    default: sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** JSON 字符串转义（含引号） */
    static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String str(java.util.List<Pb.Field> f, int n) {
        Pb.Field x = Pb.first(f, n);
        return x == null ? "" : x.asString();
    }

    private static long num(java.util.List<Pb.Field> f, int n) {
        Pb.Field x = Pb.first(f, n);
        return x == null || x.wire != Pb.W_VARINT ? 0L : x.asLong();
    }
}
