package com.dywatch.app.chat;

// 老内核语法补丁（方案 A）：把抖音私信 JS 里 Chromium 83 解析不了的语法**就地改写**，
// 让老内核也能加载。零新增依赖、零 APK 体积增加。
//
// 为什么要这个（真机坐实的根因，2026-10-01）：
//   手表 WebView = Chromium 83；抖音私信微前端（pcim）用了更新的语法，解析直接失败：
//     Uncaught SyntaxError: Unexpected token '='  → ??=          （需 Chrome 85）
//     Uncaught SyntaxError: Unexpected token '('  → 类私有方法   （需 Chrome 84）
//     （还有类字段无初始化 value;                 需 Chrome 93）
//   解析失败 → 整个私信模块不执行 → 桥抓到 conversations: items[] → 界面永远"等待中"。
//
// 三类改造：
//   ① 私有标识符 #x     → __dy_x         （声明处与使用处用同一规则，故自动同步）
//   ② 逻辑赋值 a ??= b  → a = a ?? b
//   ③ 类字段无初始化 x; → x = undefined; （有初始化的不动，Chrome 72+ 已支持）
//
// ⚠️ 设计取舍：真正的语义降级（Babel）要用 WeakMap/WeakSet 模拟私有性，产物 +22KB 且需
//    整套运行时辅助，跑不进 Android。本项目只是加载第三方页面做 DOM 抓取，不依赖私有语义，
//    所以**重命名即可**（只要不撞名就行为等价）。已用 __dy_ 前缀降低撞名概率。
//
// ⚠️ 扫描器设计（本类最易错处，真机踩了三次坑才定稿）：
//   这是个**字符状态机**，不是真正的 JS 解析器。压缩后的 bundle 是超长单行，
//   一旦某处误判（正则里的 /\sedg\//i、转义的 \\ 、URL 的 //），状态会"永不结束"
//   → 从该点起后面全部被跳过 → 数百处目标一处都找不到（且现象极具迷惑性）。
//   因此**每个状态都带"破坏半径"上限**：超过上限就判定误判并强制退出。
//   宁可偶尔把注释/字符串当代码，也绝不能把整个文件吞掉。
//
// 纯 Java、无 Android 依赖 → 可直接 JVM 单测（见 JsSyntaxPatchTest，含真实 bug 的回归用例）。

public final class JsSyntaxPatch {

    /** 私有标识符重命名前缀（带 dy 标记，避免与站方自有变量撞名） */
    private static final String PRIV_PREFIX = "__dy_";

    /** 行注释破坏半径：真实代码几乎没有这么长的单行注释；压缩 bundle 则可能整文件两行 */
    private static final int MAX_LINE_COMMENT = 2000;
    /** 块注释破坏半径 */
    private static final int MAX_BLOCK_COMMENT = 20000;
    /** 字符串/模板串破坏半径 */
    private static final int MAX_STRING_SPAN = 20000;

    private JsSyntaxPatch() {}

    /** 替换结果（供日志与单测核对） */
    public static final class Result {
        public final String js;
        public final int privateId;   // 改了多少处私有标识符
        public final int logical;     // 改了多少处 ??=
        public final int classField;  // 改了多少处类字段
        public final boolean changed;

        Result(String js, int p, int l, int f) {
            this.js = js;
            this.privateId = p;
            this.logical = l;
            this.classField = f;
            this.changed = p + l + f > 0;
        }
    }

    /**
     * 字符状态机：只回答"当前字符是否处于代码上下文"。
     * 三处替换共用同一份实现，避免各写一遍而导致某处漏修（历史上漏过两次）。
     */
    private static final class Scan {
        boolean inStr, inStr2, inTpl, inLineCmt, inBlockCmt;
        int spanStart;   // 当前状态起始位置，用于破坏半径判断

        /** 推进一个字符；返回 true 表示当前字符处于"代码上下文"（可安全改写） */
        boolean feed(StringBuilder sb, int i) {
            char c = sb.charAt(i);
            char prev = i > 0 ? sb.charAt(i - 1) : 0;
            int len = sb.length();

            if (inLineCmt) {
                if (c == '\n' || i - spanStart > MAX_LINE_COMMENT) { inLineCmt = false; }
                return false;
            }
            if (inBlockCmt) {
                if (c == '/' && prev == '*') inBlockCmt = false;
                else if (i - spanStart > MAX_BLOCK_COMMENT) inBlockCmt = false;
                return false;
            }
            if (inStr) {
                if (c == '\'' && !escapedBefore(sb, i)) inStr = false;
                else if (i - spanStart > MAX_STRING_SPAN) inStr = false;
                return false;
            }
            if (inStr2) {
                if (c == '"' && !escapedBefore(sb, i)) inStr2 = false;
                else if (i - spanStart > MAX_STRING_SPAN) inStr2 = false;
                return false;
            }
            if (inTpl) {
                if (c == '`' && !escapedBefore(sb, i)) inTpl = false;
                else if (i - spanStart > MAX_STRING_SPAN) inTpl = false;
                return false;
            }

            // 进入注释（需判别：URL 的 "http://x" 不该算）
            if (c == '/' && i + 1 < len) {
                char nx = sb.charAt(i + 1);
                if (nx == '/' && isCommentStart(sb, i)) { inLineCmt = true; spanStart = i; return false; }
                if (nx == '*' && isCommentStart(sb, i)) { inBlockCmt = true; spanStart = i; return false; }
            }
            if (c == '\'') { inStr = true; spanStart = i; return false; }
            if (c == '"') { inStr2 = true; spanStart = i; return false; }
            if (c == '`') { inTpl = true; spanStart = i; return false; }
            return true;
        }
    }

    /**
     * 主入口：把 JS 里 83 不支持的语法改写掉。
     * 无目标时原样返回（不分配多余开销）；异常时返回原文（宁可不改，也不能把页面改坏）。
     */
    public static Result patch(String js) {
        if (js == null || js.isEmpty()) return new Result(js == null ? "" : js, 0, 0, 0);
        try {
            StringBuilder sb = new StringBuilder(js);
            int nPriv = patchPrivateId(sb);
            int nLogic = patchLogicalAssign(sb);
            int nField = patchClassField(sb);
            return new Result(sb.toString(), nPriv, nLogic, nField);
        } catch (Throwable t) {
            return new Result(js, 0, 0, 0);   // 保底：改不动就别改
        }
    }

    /**
     * ① 私有标识符：#x → __dy_x
     * 只在代码上下文替换，因此字符串/CSS 颜色 "#fff"、"#1890ff" 不受影响（单测覆盖）。
     */
    static int patchPrivateId(StringBuilder sb) {
        int n = 0;
        int len = sb.length();
        Scan s = new Scan();
        for (int i = 0; i < len; i++) {
            if (!s.feed(sb, i)) continue;
            char c = sb.charAt(i);
            if (c == '#' && i + 1 < len) {
                char nx = sb.charAt(i + 1);
                if (isIdStart(nx)) {
                    // ⚠️ 必须替换 '#' 本身（只插前缀会留下 "#__dy_p"，仍是非法语法）
                    sb.replace(i, i + 1, PRIV_PREFIX);
                    len = sb.length();
                    i += PRIV_PREFIX.length();
                    while (i < len && isIdPart(sb.charAt(i))) i++;
                    i--;
                    n++;
                }
            }
        }
        return n;
    }

    /** ② 逻辑赋值：a ??= b → a = a ?? b（同样只在代码上下文） */
    static int patchLogicalAssign(StringBuilder sb) {
        int n = 0;
        int len = sb.length();
        Scan s = new Scan();
        int i = 0;
        while (i < len) {
            if (!s.feed(sb, i)) { i++; continue; }
            char c = sb.charAt(i);
            if (c == '?' && i + 2 < len && sb.charAt(i + 1) == '?' && sb.charAt(i + 2) == '='
                    && i + 3 < len && sb.charAt(i + 3) != '=') {
                String lhs = leftOperand(sb, i);
                if (!lhs.isEmpty()) {
                    int ls = i - lhs.length() - trailingSpaces(sb, i - lhs.length());
                    sb.replace(ls, i + 3, lhs + " = " + lhs + " ?? ");
                    len = sb.length();
                    i = ls + (lhs + " = " + lhs + " ?? ").length();
                    n++;
                    continue;
                }
            }
            i++;
        }
        return n;
    }

    /** 从 pos 往前数空白字符个数 */
    private static int trailingSpaces(StringBuilder sb, int pos) {
        int k = 0;
        while (pos - 1 - k >= 0 && isSpace(sb.charAt(pos - 1 - k))) k++;
        return k;
    }

    /**
     * ③ 类字段无初始化：class C { value; } → class C { value = undefined; }
     * ⚠️ 只在**类体第一层**生效。第一版没做这个判断，把 switch 的 `break;`
     *    也补成了 `break = undefined;`，反而把原本能解析的 lib-polyfill 改崩了。
     */
    static int patchClassField(StringBuilder sb) {
        int n = 0;
        int len = sb.length();
        Scan sc = new Scan();
        java.util.ArrayDeque<Integer> classBrace = new java.util.ArrayDeque<>();
        int depth = 0;
        boolean pendingClass = false;

        for (int i = 0; i < len; i++) {
            char c = sb.charAt(i);
            // 注释/字符串状态推进（不依赖它返回值，因为我们还要数花括号）
            boolean code = sc.feed(sb, i);

            if (code && !pendingClass && c == 'c' && matchesAt(sb, i, "class")) {
                char before = i > 0 ? sb.charAt(i - 1) : 0;
                char after = i + 5 < len ? sb.charAt(i + 5) : 0;
                if (!isIdPart(before) && (after == 0 || !isIdPart(after))) {
                    pendingClass = true;
                    i += 4;
                    continue;
                }
            }

            if (c == '{') {
                depth++;
                if (pendingClass) { classBrace.push(depth); pendingClass = false; }
                // ⚠️ 类体第一个字段就紧跟在 { 后，不能跳过
                if (inClassBody(classBrace, depth)) {
                    int ke = tryFieldAt(sb, i + 1, len);
                    if (ke > 0) {
                        sb.insert(ke, " = undefined");
                        len = sb.length();
                        i = ke - 1;
                        n++;
                    }
                }
                continue;
            }
            if (c == '}') {
                if (!classBrace.isEmpty() && classBrace.peek() == depth) classBrace.pop();
                depth--;
                continue;
            }
            if (c == ';' && inClassBody(classBrace, depth)) {
                int ke = tryFieldAt(sb, i + 1, len);
                if (ke > 0) {
                    sb.insert(ke, " = undefined");
                    len = sb.length();
                    i = ke - 1;
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean inClassBody(java.util.ArrayDeque<Integer> st, int depth) {
        return !st.isEmpty() && st.peek() == depth;
    }

    /** 从 from 起读一个"无初始化类字段"：`[ws] 标识符 [ws] ;`，返回插入点；不是返回 -1 */
    private static int tryFieldAt(StringBuilder sb, int from, int len) {
        int j = from;
        while (j < len && isSpace(sb.charAt(j))) j++;
        if (j >= len || !isIdStart(sb.charAt(j))) return -1;
        while (j < len && isIdPart(sb.charAt(j))) j++;
        int ke = j;
        while (j < len && isSpace(sb.charAt(j))) j++;
        if (j < len && sb.charAt(j) == ';') return ke;
        return -1;
    }

    /**
     * 判断 pos 处的 `/` 是不是注释起点。
     * 只看 `//` 会误伤 URL（"http://x"）；而压缩 bundle 是超长单行，
     * 一旦误判，状态永不结束 → 后面全被吞。故用"前一个有意义字符"做启发式判别。
     */
    private static boolean isCommentStart(StringBuilder sb, int pos) {
        for (int k = pos - 1; k >= 0; k--) {
            char p = sb.charAt(k);
            if (isSpace(p)) continue;
            if (isIdPart(p)) return false;                        // a/b、1/2
            if (p == ')' || p == ']' || p == '}') return false;   // (a)/b
            if (p == '"' || p == '\'' || p == '`') return false;
            return true;
        }
        return true;
    }

    /**
     * 判断 pos 处字符是否被反斜杠转义。
     * ⚠️ 不能只写 `prev != '\\'`：`"a\\"`（字符串以两个反斜杠结尾）会被误判成引号被转义
     *    → 字符串状态永不结束。正确做法：往前数连续反斜杠，**奇数**才是转义。
     */
    private static boolean escapedBefore(StringBuilder sb, int pos) {
        int bs = 0;
        for (int k = pos - 1; k >= 0 && sb.charAt(k) == '\\'; k--) bs++;
        return (bs & 1) == 1;
    }

    /** 从 ??= 处往前取左值（标识符 / a.b） */
    private static String leftOperand(StringBuilder sb, int opPos) {
        int end = opPos;
        while (end > 0 && isSpace(sb.charAt(end - 1))) end--;
        if (end <= 0) return "";
        int i = end;
        while (i > 0) {
            char c = sb.charAt(i - 1);
            if (isIdPart(c) || c == '.') { i--; continue; }
            break;
        }
        if (i >= end) return "";
        String s = sb.substring(i, end);
        if (s.startsWith(".") || s.endsWith(".")) return "";
        return s;
    }

    private static boolean matchesAt(StringBuilder sb, int pos, String word) {
        if (pos + word.length() > sb.length()) return false;
        for (int k = 0; k < word.length(); k++) {
            if (sb.charAt(pos + k) != word.charAt(k)) return false;
        }
        return true;
    }

    private static boolean isIdStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdPart(char c) {
        return isIdStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }
}
