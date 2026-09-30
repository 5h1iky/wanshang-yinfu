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
     * 不说"网络错误"，直接说清是本机内核太老，并给出版本号差距。
     */
    public static String kernelHint(Kernel k) {
        if (k == null) return "无法确认网页内核版本，私信可能不可用";
        if (!k.known()) return "网页内核版本未知（" + k.raw + "），私信可能不可用";
        if (k.supportsIm()) return "";   // 够用 → 不打扰
        return "本机网页内核过旧（Chromium " + k.major + "，需 " + MIN_CHROME_FOR_IM
                + "+），抖音私信页无法加载";
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
     */
    public static boolean isKernelDeadEnd(Kernel k, int retries) {
        return k != null && k.known() && !k.supportsIm() && retries >= 2;
    }
}
