package com.dywatch.app.chat;

// 桥事件脱敏（2026-10-02，代码审计 H3）。
//
// 问题：ChatEngine 把整条桥事件 JSON 原样写进 filesDir/app.log 和 logcat（tag=dywatch）。
// 而桥事件里带着**私信正文、会话昵称、评论正文与昵称、登录 uid**——等于把用户的聊天内容
// 落了盘、还在 logcat 里对任何能连 adb 的人广播。AppLog 自己的类注释写着
// "不含敏感值（调用方自律：cookie/令牌一律不入参）"，这里就是那个没自律的调用方。
//
// 口径：**白名单**，不是黑名单。只打印明确安全的结构性字段（类型/条数/布尔/状态词），
// 正文类字段一律不打印——不依赖"我记得哪些字段是敏感的"，将来桥加了新字段也不会漏。
//
// 用 Gson 而不是 org.json：org.json 是 Android 框架类，JVM 单测里是空壳（会抛
// "not mocked"），换 Gson 才能把"到底会不会漏正文"写成一条真跑的测试。

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Pattern;

final class BridgeLog {

    /** 正文可能出现的引号包裹（`「这是评论正文」`）：整体抹掉内容、保留形状 */
    private static final Pattern QUOTED = Pattern.compile("「[^」]*」");
    /** `state=<编辑器里的正文>` / `injected=<评论正文>`：值一律抹掉 */
    private static final Pattern STATE = Pattern.compile("state=[^）)]*");
    private static final Pattern INJECTED = Pattern.compile("injected=[^,}\\s]*");
    /** 计数文本只可能是数字/小数/万/千/箭头（`4.0万→4.1万`）；含别的字符就不打印 */
    private static final Pattern COUNT_OK = Pattern.compile("[0-9.\\s+\\-→万千百]*");

    private static final int DETAIL_MAX = 80;

    private BridgeLog() {}

    /**
     * 事件里的 doc 字段是否等于本引擎本次注入的文档密钥（2026-10-02，审计 H4-c）。
     *
     * 为什么要这一层：addJavascriptInterface 挂的对象在**所有框架**里都可见，
     * 页面里任何第三方 iframe 都能调 onBridgeEvent 伪造"点赞成功/评论已发/会话列表"。
     * 桥在注入时被塞进一个只存在于**本文档**的随机密钥，事件必须带着它；
     * 跨域 iframe 读不到主文档的变量，于是伪造这条路被堵上。
     *
     * 抽成纯函数（而不是留在 ChatEngine 里用私有字段判断）是为了能在 JVM 里直接测 ——
     * 这条规则一旦错了，表现是"聊天彻底没反应"，绝不能只靠真机试。
     */
    static boolean hasValidKey(String json, String key) {
        if (key == null || key.isEmpty() || json == null) return false;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return false;
            JsonElement d = el.getAsJsonObject().get("doc");
            return d != null && d.isJsonPrimitive() && key.equals(d.getAsString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一条桥事件 → 一行可安全落盘的摘要 */
    static String summarize(String json) {
        if (json == null) return "(null)";
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return "(非对象 " + json.length() + " 字节)";
            o = el.getAsJsonObject();
        } catch (Throwable t) {
            // 解析都失败就更不能把原文写出去（谁知道里面是什么）
            return "(非 JSON " + json.length() + " 字节)";
        }
        String type = str(o, "type");
        StringBuilder sb = new StringBuilder("type=").append(type);
        if ("messages".equals(type)) {
            sb.append(" 条数=").append(count(o, "items"));
        } else if ("conversations".equals(type)) {
            sb.append(" 条数=").append(count(o, "items"))
                    .append(" rows=").append(num(o, "rows"))
                    .append(" tries=").append(num(o, "tries"));
        } else if ("comments".equals(type) || "commentsMore".equals(type)) {
            sb.append(" 新增=").append(count(o, "items"))
                    .append(" 累计=").append(num(o, "total"))
                    .append(" atEnd=").append(bool(o, "atEnd"))
                    .append(" end=").append(bool(o, "end"));
        } else if ("auth".equals(type)) {
            // user/uid 是账号身份，一律不落；只留"有没有过"和提示长度
            sb.append(" ok=").append(bool(o, "ok"))
                    .append(" 提示长度=").append(str(o, "message").length())
                    .append(" 有昵称=").append(!str(o, "user").isEmpty());
        } else if ("sent".equals(type)) {
            sb.append(" ok=").append(bool(o, "ok"))
                    .append(" note=").append(safeText(str(o, "note")));
        } else if ("action".equals(type)) {
            sb.append(" action=").append(str(o, "action"))
                    .append(" ok=").append(bool(o, "ok"))
                    .append(" phase=").append(str(o, "phase"))
                    .append(" changed=").append(bool(o, "changed"))
                    .append(" skipped=").append(bool(o, "skipped"))
                    .append(" attempts=").append(num(o, "attempts"))
                    .append(" count=").append(safeCount(str(o, "count")))
                    .append(" detail=").append(safeText(str(o, "detail")));
            // ⚠️ 刻意不打印 injected / cands：sendComment 的 injected 就是用户刚打的评论正文
        } else if ("opened".equals(type) || "back".equals(type)) {
            // 会话昵称不是内容但也是身份，只留长度
            sb.append(" ok=").append(bool(o, "ok"))
                    .append(" 名字长度=").append(str(o, "name").length());
        } else if ("home".equals(type)) {
            sb.append(" ok=").append(bool(o, "ok"));
        } else if ("error".equals(type) || "ready".equals(type)) {
            sb.append(" msg=").append(safeText(str(o, "message")));
        }
        return sb.toString();
    }

    /**
     * 状态词脱敏：桥回报的 detail 大部分是固定判词（"状态已翻转"/"未找到按钮 xxx"），
     * 少数几条会把**用户输入**拼进去（"提交未生效：编辑器仍有内容「正文」，已试 3 个"、
     * "文本未进入编辑器（state=正文）"）。这里把带正文的那几段抹成省略号，保留判词本身——
     * 判词才是排查要用的信息，正文不是。
     */
    static String safeText(String s) {
        if (s == null) return "";
        String out = QUOTED.matcher(s).replaceAll("「…」");
        out = STATE.matcher(out).replaceAll("state=…");
        out = INJECTED.matcher(out).replaceAll("injected=…");
        return out.length() > DETAIL_MAX ? out.substring(0, DETAIL_MAX) + "…" : out;
    }

    /** 计数文本（`4.0万→4.1万`）；出现非计数形状的字符就整体不打印 */
    static String safeCount(String s) {
        if (s == null || s.isEmpty()) return "";
        return COUNT_OK.matcher(s).matches() ? s : "…";
    }

    private static String str(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? "" : e.getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int count(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e == null || !e.isJsonArray()) ? 0 : e.getAsJsonArray().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String num(JsonObject o, String key) {
        String s = str(o, key);
        return COUNT_OK.matcher(s).matches() ? s : "";
    }

    private static String bool(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e == null || e.isJsonNull()) ? "?" : String.valueOf(e.getAsBoolean());
        } catch (Throwable t) {
            return "?";
        }
    }
}
