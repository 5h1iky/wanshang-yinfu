package com.dywatch.app.chat;

// 老内核诊断：把"私信打不开"这件笼统的事，拆成**可自查的具体原因**。
//
// 背景（2026-10-01，真机坐实）：手表 WebView 是 Chromium 83，抖音私信微前端用了
// 83 解析不了的语法（私有方法 / ??= / 类字段），导致整个模块解析失败 → 会话列表永远空。
// 用户看到的现象是"正在等待会话数据…（自动重试）"，很容易误判成网络问题。
//
// 本类做两件事：
//   ① 判定本机内核够不够（够 → 不打扰；不够 → 说清楚差多少）
//   ② 给出"卡在哪一步"的分级文案：内核 / 登录 / 页面 / 数据
// 纯静态、纯 JVM 可测（不依赖 Android 类），单测直接跑。

public final class LegacyKernel {

    /** Chrome 83 是本项目实测到的最老内核（OPPO OWW212 / Android 11） */
    public static final int MIN_CHROME_FOR_IM = 92;

    private LegacyKernel() {}

    /** 内核信息：主版本号 + 包名（解析失败时 major = -1） */
    public static final class Kernel {
        public final int major;      // Chrome 主版本，如 83 / 108；未知 = -1
        public final String raw;     // 原始 versionName，如 "83.0.4103.120"
        public final String pkg;     // WebView 包名，可能为 ""

        public Kernel(int major, String raw, String pkg) {
            this.major = major;
            this.raw = raw == null ? "" : raw;
            this.pkg = pkg == null ? "" : pkg;
        }

        public boolean known() { return major > 0; }

        /** 内核是否达到私信所需版本 */
        public boolean supportsIm() { return known() && major >= MIN_CHROME_FOR_IM; }
    }

    /**
     * 解析 WebView 版本串的主版本号。
     * 纯函数（不依赖 Android）："83.0.4103.120" → 83；"108.0.5359.128" → 108；
     * 空串 / null / 非数字开头 → -1（未知）。
     */
    public static int parseMajor(String versionName) {
        if (versionName == null) return -1;
        String s = versionName.trim();
        if (s.isEmpty()) return -1;
        int i = 0;
        while (i < s.length() && (s.charAt(i) < '0' || s.charAt(i) > '9')) {
            // 跳过前导非数字（如 "Chrome/83..." 的 "Chrome/"）
            i++;
        }
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;   // 超长数字等极端情况
        }
    }

    public static Kernel from(String versionName, String pkg) {
        return new Kernel(parseMajor(versionName), versionName, pkg);
    }

    /**
     * 内核不足时的说明文案（诚实降级，方案 D）。
     *
     * ⚠️ 2026-10-25 改口径：旧文案写"抖音私信页**无法加载**"，真机上被打脸——
     *    语法补丁（方案 A）生效后 83 也能加载，只是 **pcim 模块要 2.5~3 分钟才开始下载**，
     *    实测冷启动 165s 成功、第二次 293s+ 才挂死。把"慢"说成"不能"，用户会直接放弃。
     *    现在一律只说"慢"，并给出已等时长与可选动作。
     */
    public static String kernelHint(Kernel k) {
        if (k == null) return "无法确认网页内核版本，私信页可能很慢；可继续等待或稍后再来";
        if (!k.known()) return "网页内核版本未知（" + k.raw + "），私信页可能很慢；可继续等待或稍后再来";
        if (k.supportsIm()) return "";   // 够用 → 不打扰
        return "本机网页内核偏旧（Chromium " + k.major + "），私信页加载很慢；可继续等待或稍后再来";
    }

    /** 同上，但把"已等多久"说清楚（终态文案，elapsedMs <= 0 时不带秒数） */
    public static String kernelHint(Kernel k, long elapsedMs) {
        String base = kernelHint(k);
        if (base.isEmpty() || elapsedMs <= 0) return base;
        return base.replace("；可继续等待或稍后再来",
                "（已等 " + (elapsedMs / 1000) + "s）；可继续等待或稍后再来");
    }

    /**
     * 会话列表"拿不到数据"时的分级提示：**告诉用户卡在哪一步**，而不是笼统的"等待中"。
     *
     * ⚠️ 2026-10-01 补：语法补丁（方案 A）生效后，内核不再是卡点——
     *    实测 SyntaxError 从 5 个降到 0，私信模块已能解析。
     *    这时若还显示"内核过旧"就是**误导**（用户会以为没救了，实际卡在别处）。
     *    所以调用方要传 patched，补丁生效时不再把锅甩给内核。
     *
     * @param k        内核信息（可空）
     * @param authed   登录态是否已确认
     * @param pageOk   私信页是否已加载完成
     * @param retries  已重试次数
     * @param patched  语法补丁是否已生效（生效则不再归咎内核）
     * @return 给用户的提示文案
     */
    public static String stuckHint(Kernel k, boolean authed, boolean pageOk, int retries,
                                   boolean patched) {
        if (!authed) {
            return "卡在「登录态」：未确认已登录，私信页不会返回数据（请回主屏重新登录）";
        }
        if (!pageOk) {
            return "卡在「页面加载」：私信页还没就绪（已重试 " + retries + " 次）";
        }
        if (patched) {
            // 补丁已生效 → 内核不再是原因，如实说"页面已通但没抓到"
            return "卡在「会话数据」：私信页已能加载（语法补丁生效），但没抓到会话"
                    + "（已重试 " + retries + " 次）";
        }
        if (k != null && k.known() && !k.supportsIm()) {
            return "卡在「网页内核」：本机 Chromium " + k.major + " 太旧（需 "
                    + MIN_CHROME_FOR_IM + "+），私信页脚本解析失败";
        }
        return "卡在「会话数据」：页面已就绪但没抓到会话（已重试 " + retries
                + " 次），可能是页面结构变了";
    }

    /** 兼容旧签名（补丁状态未知时按未生效处理） */
    public static String stuckHint(Kernel k, boolean authed, boolean pageOk, int retries) {
        return stuckHint(k, authed, pageOk, retries, false);
    }

    /**
     * 是否应判定为"内核导致的永久失败"（命中就不再无谓重试，直接给终态提示）。
     * 重试 8 次仍空 + 内核不足 → 认定无解，别再转圈。
     *
     * ⚠️ 2026-10-01：调用方必须**先过首屏预算**（{@link #stillLoading}）再问这个，
     *    否则会在"只是慢"的时候给出"没救"的结论（用户已反馈过这个误导）。
     *
     * ⚠️ 2026-10-25：**语法补丁生效时永远不算死路**（patched=true → false）。
     *    补丁把 SyntaxError 打到 0，内核不再是卡点；剩下的只是慢（pcim 模块下载晚）。
     *    旧代码在补丁生效时仍可能走到这里，直接给"内核过旧无法加载"，与事实相反。
     */
    public static boolean isKernelDeadEnd(Kernel k, int retries) {
        return isKernelDeadEnd(k, retries, false);
    }

    /** 补丁感知版：补丁已生效 → 内核不再背锅（恒 false） */
    public static boolean isKernelDeadEnd(Kernel k, int retries, boolean patched) {
        if (patched) return false;
        return k != null && k.known() && !k.supportsIm() && retries >= 2;
    }

    // ---------- 加载态 / 终态的判据（2026-10-01 新增，纯函数可 JVM 单测）----------

    /**
     * 首屏预算：这段时间内**一律只报进度、不许下结论**。
     *
     * 为什么需要：真机实测（2026-10-01，用户反馈 + 我的时间线观测）从进聊天页到会话数据回来，
     * 冷启动要 **35~40 秒**（引擎启动 ~10s + 抖音私信页 SPA 渲染 + 桥轮询），
     * 而老代码在 ~5 秒时就敢说"本机内核过旧、脚本解析失败"，紧接着数据又出来了 ——
     * 三条提示里两条在否定加载成功，直接打击等待意愿。
     *
     * ⚠️ 30 秒、60 秒、90 秒都不够：
     *    2026-10-01 冷启动最慢 61s（90s 那版留 1.5 倍余量）；
     *    **2026-10-25 手表两次冷启动实测**：①165s 成功（会话 7 条）；②293s+ 挂死。
     *    根因是**抖音 pcim 微前端包要到 2.5~3 分钟才开始下载**，慢在前端资源而不是本机。
     *    所以预算放到 **180 秒**（覆盖 165s 那次成功），且预算耗尽后也**只说慢、不判死**。
     */
    public static final long FIRST_LOAD_BUDGET_MS = 180_000L;

    /** 还在首屏预算内 = 加载中（调用方据此决定"报进度"还是"下结论"） */
    public static boolean stillLoading(long elapsedMs) {
        return elapsedMs < FIRST_LOAD_BUDGET_MS;
    }

    /**
     * 加载中的进度文案（纯函数）。
     * ⚠️ 单测锁死：这些文案**不许**出现"失败 / 异常 / 过旧 / 不可用 / 卡在"这类否定词——
     * 它们只在预算耗尽后的终态里出现。
     *
     * @param engineReady 引擎是否已就绪（false = 通道还在启动）
     * @param attempts    已尝试拉取次数（含首次）
     */
    public static String progressHint(boolean engineReady, int attempts) {
        return progressHint(engineReady, attempts, -1);
    }

    /**
     * 同上 + 带上"已等 N 秒"（2026-10-25 新增）。
     *
     * 为什么要秒数：手表上这条提示要挂 2~3 分钟，只写"正在拉取会话…"用户会以为界面卡死、
     * 反复退出重进（每次重进都会重新开 WebView，反而更慢）。数字在动 = 程序还活着。
     *
     * @param elapsedMs 已等待毫秒；< 0 表示不带秒数
     */
    public static String progressHint(boolean engineReady, int attempts, long elapsedMs) {
        String base;
        if (!engineReady) {
            base = "正在启动通道…（首次打开较慢，请稍等）";
        } else if (attempts <= 1) {
            base = "正在拉取会话…";
        } else {
            // 说"在等"而不是"卡住"：抖音私信页本身就要几十秒到几分钟，中途下结论必被打脸
            base = "正在拉取会话…（抖音私信页较慢，已重试 " + attempts + " 次）";
        }
        if (elapsedMs >= 1000) {
            base = base + "已等 " + (elapsedMs / 1000) + "s";
        }
        return base;
    }
}
