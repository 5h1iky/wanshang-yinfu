package com.dywatch.app.chat.api;

// IM 原生实现：读（会话/消息/历史）+ 发（文本）。
//
// 协议基线全部来自 2026-11-07 真机实测（`调研\19`、`调研\20`），要点：
//   · 会话列表 + 每个会话最近 20 条：POST /v1/message/get_message_by_init     (cmd=2043)
//   · 更早的历史消息：            POST /v1/message/get_message_by_index_v2_range (cmd=2041)
//   · 发送文本：                  POST /v1/message/send                        (cmd=100，见 ImSend)
//   · 读接口 URL **零参数**、请求头只要 4 个、鉴权纯靠 cookie —— 没有 a_bogus 障碍
//
// ⚠️ 三条纪律：
//   1. **节流**。读接口有限频（高频重放触发 "too many requests"），相邻请求 ≥3s。
//   2. **不打日志正文**。消息内容属隐私，日志只记条数与状态。
//   3. **发送必须凭证齐全**。残缺的票据链会被 KICK（见 ImSend 的硬闸门）。

import com.dywatch.app.util.AppLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class ImApi {

    private static final String TAG = "imapi";

    private static final String BASE = "https://imapi.douyin.com";
    private static final String PATH_INIT = "/v1/message/get_message_by_init";
    private static final String PATH_RANGE = "/v1/message/get_message_by_index_v2_range";

    /** 与页面一致的桌面 UA（页面自己的请求也用这个） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    private static final MediaType PROTO = MediaType.parse("application/x-protobuf");

    /** cmd 常量（实测值） */
    private static final int CMD_INIT = 2043;
    private static final int SUB_INIT = 10001;
    private static final int CMD_RANGE = 2041;

    private static final String SDK_VERSION = "0.1.8";
    private static final String FEAT = "0d50935:feat/pc-im-group";

    /** 节流：相邻两次请求的最小间隔（真机实测有限频，保守取 3s） */
    private static final long MIN_INTERVAL_MS = 3000L;

    /** MessageDirection.OLDER */
    private static final int DIR_OLDER = 1;

    private final OkHttpClient mClient;
    private String mCookie = "";
    private long mLastRequestAt;
    /** 渲染窗口尺寸（真实请求里是视口尺寸，不是显示器分辨率） */
    private int mScreenW = 393;
    private int mScreenH = 873;

    public ImApi() {
        mClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** 注入登录态 cookie（来自 LoginManager.getCookies） */
    public ImApi setCookie(String cookie) {
        mCookie = cookie == null ? "" : cookie;
        return this;
    }

    public boolean hasCookie() {
        return mCookie.contains("sessionid=");
    }

    /** 设置渲染窗口尺寸（服务端据此校验环境一致性；默认 393x873 与桌面页视口一致） */
    public ImApi setScreen(int w, int h) {
        if (w > 0) mScreenW = w;
        if (h > 0) mScreenH = h;
        return this;
    }

    // ---------- 对外：读 ----------

    /** 一次 init 的结果：会话列表 + 自己 uid + 下一页游标 */
    public static final class Init {
        public final List<ImModels.Conv> convs = new ArrayList<>();
        public long selfUid;
        public final ImModels.Cursor next = new ImModels.Cursor();
        public String error = "";

        public boolean ok() {
            return error.isEmpty();
        }

        public ImModels.Conv find(String convId) {
            for (ImModels.Conv c : convs) if (c.id.equals(convId)) return c;
            return null;
        }
    }

    /**
     * 拉会话列表（每个会话自带最近 20 条消息）。
     *
     * 这也是**唯一的轮询入口**：聊天页定时调它，按 convId 取该会话的最新消息，
     * 比"再开一个消息接口"少一半请求（读接口有限频，省着用）。
     */
    public Init init() throws IOException {
        byte[] body = buildEnvelope(CMD_INIT, SUB_INIT, 1,
                Pb.writer().varint(2, 0).toByteArray(), mScreenW, mScreenH);
        byte[] resp = post(PATH_INIT, body);
        return parseInit(resp);
    }

    /**
     * 拉某个会话更早的历史消息。
     *
     * @param beforeIndex 当前已知最小 index_in_conversation（取该值**之前**的消息）
     * @return 消息（旧→新）；空 = 没有更早的了
     */
    public List<ImModels.Msg> history(String convId, long convShortId, long beforeIndex) throws IOException {
        if (convId == null || convId.isEmpty() || beforeIndex <= 1) return new ArrayList<>();
        byte[] payload = Pb.writer()
                .string(1, convId)
                .varint(2, 1)                                  // conversation_type
                .varint(3, convShortId)
                .varint(4, 1)                                  // lo
                .varint(5, beforeIndex - 1)                    // hi（不含）
                .varint(6, DIR_OLDER)
                .toByteArray();
        byte[] resp = post(PATH_RANGE, buildEnvelope(CMD_RANGE, 0, 1, payload, mScreenW, mScreenH));
        return parseHistory(resp);
    }

    // ---------- 请求体构造（纯函数，可单测） ----------

    /**
     * 通用信封（字段与真机抓包逐一对齐）。
     *
     * ⚠️ 2026-11-07 实测踩坑：漏掉 f18/f21/f22 时服务端返回 **"empty token"**（误导性错误，
     * 与编码 bug 的表现一模一样）。这三个字段是平台标识，必须带：
     *   f18 = 1 / f21 = "douyin_web" / f22 = "web_sdk"
     */
    static byte[] buildEnvelope(int cmd, int sub, int seq, byte[] cmdPayload) {
        return buildEnvelope(cmd, sub, seq, cmdPayload, 393, 873);
    }

    /**
     * 带视口尺寸的版本（发送/读都能用；抽出来是为了让 buildEnvelope 保持纯静态、可单测）。
     */
    static byte[] buildEnvelope(int cmd, int sub, int seq, byte[] cmdPayload, int screenW, int screenH) {
        Pb.Writer env = Pb.writer()
                .varint(1, cmd)
                .varint(2, sub > 0 ? sub : seq)
                .string(3, SDK_VERSION)
                .bytes(4, new byte[0])
                .varint(5, 3)
                .varint(6, 1)
                .string(7, FEAT)
                .bytes(8, Pb.writer().message(cmd, cmdPayload).toByteArray())
                .string(9, "0")
                .string(11, "douyin_pc")
                .string(14, "360000");

        // f15：设备/环境 kv（实测 17 组；顺序与键名照抄真实请求）
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
        // ⚠️ 这两组实测不是桌面分辨率，而是**渲染窗口**尺寸；写 2560x1440 会被服务端判为不一致
        env.message(15, kv("screen_width", String.valueOf(screenW)));
        env.message(15, kv("screen_height", String.valueOf(screenH)));
        env.message(15, kv("referer", ""));
        env.message(15, kv("timezone_name", "Asia/Shanghai"));
        env.message(15, kv("deviceId", "0"));
        env.message(15, kv("is-retry", "0"));

        // ⚠️ 平台标识：漏掉这三个 => 服务端回 "empty token"
        env.varint(18, 1);
        env.string(21, "douyin_web");
        env.string(22, "web_sdk");
        return env.toByteArray();
    }

    private static byte[] kv(String key, String value) {
        return Pb.writer().string(1, key).string(2, value).toByteArray();
    }

    // ---------- 响应解析（纯函数，可单测） ----------

    /**
     * 解析 init 响应。
     * 结构：f4="OK"/错误串；f13=自己 uid；f6 { f2043 { f1×N(会话), f2..f9(游标) } }
     */
    static Init parseInit(byte[] resp) {
        Init out = new Init();
        List<Pb.Field> top;
        try {
            top = Pb.decode(resp);
        } catch (RuntimeException e) {
            out.error = "响应解析失败";
            return out;
        }
        String status = str(top, 4);
        if (!"OK".equalsIgnoreCase(status)) {
            out.error = status.isEmpty() ? "非 OK 响应" : status;
            return out;
        }
        out.selfUid = num(top, 13);

        List<Pb.Field> body = Pb.nested(Pb.nested(top, 6), CMD_INIT);
        if (body.isEmpty()) return out;

        for (Pb.Field f : body) {
            if (f.number == 1 && f.wire == Pb.W_LEN) {
                ImModels.Conv c = parseConv(f.asBytes(), out.selfUid);
                if (c != null && !c.id.isEmpty()) out.convs.add(c);
            }
        }
        out.next.version = num(body, 4);
        out.next.userCursor = num(body, 5);
        out.next.cmdIndex = num(body, 6);
        out.next.readVersion = num(body, 7);
        AppLog.i(TAG, "会话 n=" + out.convs.size() + " self=" + out.selfUid);
        return out;
    }

    /**
     * 解析单个会话。
     *
     * ⚠️ 实测结构有两层：
     *   f1 (约 1KB) = 会话容器 ← 里面才是真正的会话元信息（ConversationInfoV2）
     *   f2 × N      = 最近消息（MessageBody）
     * 早期版本把外层 f1 直接当 conversation_id，解析出的是带前缀字节的乱串。
     */
    private static ImModels.Conv parseConv(byte[] buf, long selfUid) {
        try {
            List<Pb.Field> outer = Pb.decode(buf);

            Pb.Field container = Pb.first(outer, 1);
            if (container == null || container.wire != Pb.W_LEN) return null;
            List<Pb.Field> meta = Pb.decode(container.asBytes());

            Pb.Field idf = Pb.first(meta, 1);
            if (idf == null || idf.wire != Pb.W_LEN) return null;
            String id = idf.asString();
            // conversation_id 形如 "0:1:<uid>:<uid>"
            if (id.indexOf(':') < 0) return null;

            ImModels.Conv c = new ImModels.Conv();
            c.id = id;
            c.shortId = num(meta, 2);
            c.ticket = str(meta, 4);
            c.selfUid = selfUid != 0 ? selfUid : guessSelfUid(id);

            // 参与者：f6 = ParticipantsPage { f1×N Participant { f1=uid, f5=sec_uid } }
            for (Pb.Field p : Pb.all(Pb.nested(meta, 6), 1)) {
                List<Pb.Field> pf = Pb.decode(p.asBytes());
                long uid = num(pf, 1);
                String sec = str(pf, 5);
                if (uid != 0 && uid != c.selfUid) {
                    c.peerUid = uid;
                    c.peerSecUid = sec;
                }
            }

            // 最近消息（旧→新按 index 排）
            for (Pb.Field m : Pb.all(outer, 2)) {
                if (m.wire != Pb.W_LEN) continue;
                ImModels.Msg msg = parseMsg(m.asBytes());
                if (msg != null) {
                    // 方向在**解析时**就定好（selfUid 来自响应顶层 f13）。
                    // 放在这里而不是 UI 层，是因为它纯属协议语义，能在 JVM 里被单测钉住；
                    // 2026-11-07 真机"消息全判成我"就是这条判据没被覆盖到。
                    if (c.selfUid != 0 && msg.senderUid != 0) {
                        msg.direction = msg.senderUid == c.selfUid ? ImModels.Msg.OUT : ImModels.Msg.IN;
                    }
                    c.recent.add(msg);
                }
            }
            c.sortRecent();
            return c;
        } catch (RuntimeException e) {
            // 单条会话解析失败不影响整批（抖音字段会变，宽容处理）
            return null;
        }
    }

    /** 从 conversation_id "0:1:&lt;self&gt;:&lt;peer&gt;" 里取自己 uid */
    private static long guessSelfUid(String id) {
        String[] parts = id.split(":");
        if (parts.length >= 3) {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    /** 解析 2041 历史响应：f4="OK"；f6 { f2041 { f1×N MessageInfo { f2 = MessageBody } } } */
    static List<ImModels.Msg> parseHistory(byte[] resp) {
        List<ImModels.Msg> out = new ArrayList<>();
        List<Pb.Field> top;
        try {
            top = Pb.decode(resp);
        } catch (RuntimeException e) {
            return out;
        }
        if (!"OK".equalsIgnoreCase(str(top, 4))) return out;
        long selfUid = num(top, 13);

        List<Pb.Field> body = Pb.nested(Pb.nested(top, 6), CMD_RANGE);
        for (Pb.Field info : Pb.all(body, 1)) {
            if (info.wire != Pb.W_LEN) continue;
            List<Pb.Field> fields;
            try {
                fields = Pb.decode(info.asBytes());
            } catch (RuntimeException e) {
                continue;
            }
            // MessageInfo { f1=status, f2=MessageBody }
            Pb.Field b = Pb.first(fields, 2);
            if (b == null || b.wire != Pb.W_LEN) continue;
            ImModels.Msg m = parseMsg(b.asBytes());
            if (m == null) continue;
            if (selfUid != 0) m.direction = m.senderUid == selfUid ? ImModels.Msg.OUT : ImModels.Msg.IN;
            out.add(m);
        }
        AppLog.i(TAG, "历史 n=" + out.size());
        return out;
    }

    /**
     * 解析单条消息。字段号以**官方 proto**（MessageBody）为准：
     *
     *   f1  = conversation_id (string)
     *   f2  = conversation_type
     *   f3  = server_message_id
     *   f4  = index_in_conversation      ← 序号，不是时间戳
     *   f5  = conversation_short_id
     *   f6  = message_type               （7=文本 5=表情 17=语音 27=图片 8=分享视频）
     *   f7  = sender (uid)
     *   f8  = content (JSON 字符串)
     *   f9  = ext (map，重复的 {f1,f2})
     *   f10 = create_time                ← 时间戳在这里（毫秒）
     *   f14 = sec_sender
     *
     * ⚠️ 2026-11-07 教训：我最初手工解析把 f4 当时间戳、f13 当序号，全部错位。
     *    上游 proto 就在 `tools/douyin-spider-ref/`，**应先读定义再解析**。
     */
    static ImModels.Msg parseMsg(byte[] buf) {
        try {
            List<Pb.Field> mf = Pb.decode(buf);
            ImModels.Msg m = new ImModels.Msg();
            m.conversationId = str(mf, 1);
            m.conversationShortId = num(mf, 5);
            m.type = (int) num(mf, 6);
            m.senderUid = num(mf, 7);
            m.timeMs = num(mf, 10);
            m.indexInConversation = num(mf, 4);
            m.secSender = str(mf, 14);

            String content = str(mf, 8);
            if (content.indexOf('{') >= 0) {
                m.text = extractText(content);
                if (m.text.isEmpty()) m.text = stripSystemText(content);
            } else if (!content.isEmpty()) {
                m.text = content;
            }
            // 非文本消息给个占位，UI 不至于显示空白
            if (m.text.isEmpty()) {
                String label = typeLabel(m.type);
                if (!label.isEmpty()) m.text = label;
            }
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 系统/业务提示消息（`{"tips":"对方回复或关注你之前，只能发送一条文字消息…"}`）：
     * 不是聊天正文，但在服务端的消息流里是一条真实消息。给它一个干净的单行文案，
     * 免得列表里出现一整段带头像模板的 JSON。
     */
    private static String stripSystemText(String content) {
        String tips = jsonField(content, "tips");
        return tips;
    }

    /** 非文本消息的占位文案（不解析媒体资源，只让列表项有内容可显示） */
    private static String typeLabel(int type) {
        switch (type) {
            case 5: return "[表情]";
            case 8: return "[分享视频]";
            case 17: return "[语音]";
            case 27: return "[图片]";
            case 50001: return "";
            default: return "";
        }
    }

    /** 从 content JSON 里取 text（不引 Gson 以免和主工程解析口径打架） */
    static String extractText(String json) {
        return jsonField(json, "text");
    }

    /** 按 key 取顶层字符串值（够用即可：这里只读 text / tips） */
    static String jsonField(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int colon = json.indexOf(':', i + key.length() + 2);
        if (colon < 0) return "";
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (int q = p + 1; q < json.length(); q++) {
            char ch = json.charAt(q);
            if (ch == '\\' && q + 1 < json.length()) {
                char nx = json.charAt(++q);
                switch (nx) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (q + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(q + 1, q + 5), 16));
                                q += 4;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    default: sb.append(nx);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ---------- HTTP ----------

    private byte[] post(String path, byte[] body) throws IOException {
        if (!hasCookie()) throw new IOException("未登录（缺少 sessionid）");
        throttle();

        Request req = new Request.Builder()
                .url(BASE + path)
                .header("accept", "application/x-protobuf")
                .header("referer", "https://www.douyin.com/chat")
                .header("user-agent", UA)
                .header("cookie", mCookie)
                .post(RequestBody.create(body, PROTO))
                .build();

        try (Response resp = mClient.newCall(req).execute()) {
            if (resp.body() == null) throw new IOException("IM 空响应");
            int code = resp.code();
            byte[] bytes = resp.body().bytes();
            if (!resp.isSuccessful()) throw new IOException("IM HTTP " + code);
            if (bytes.length == 0) throw new IOException("IM 空响应体");
            return bytes;
        }
    }

    /** 简单节流：保证相邻请求间隔 ≥ MIN_INTERVAL_MS（实测限频） */
    private void throttle() throws IOException {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - mLastRequestAt);
        if (mLastRequestAt > 0 && wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("IM 请求被中断");
            }
        }
        mLastRequestAt = System.currentTimeMillis();
    }

    // ---------- 小工具 ----------

    private static String str(List<Pb.Field> fields, int n) {
        Pb.Field f = Pb.first(fields, n);
        return f == null ? "" : f.asString();
    }

    private static long num(List<Pb.Field> fields, int n) {
        Pb.Field f = Pb.first(fields, n);
        return f == null || f.wire != Pb.W_VARINT ? 0L : f.asLong();
    }
}
