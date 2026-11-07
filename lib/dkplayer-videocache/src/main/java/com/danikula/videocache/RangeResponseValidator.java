package com.danikula.videocache;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 的 HTTP Range 响应校验。
 *
 * <p>背景：源站（尤其是带签名/CDN 的短视频地址）有时会忽略 {@code Range: bytes=N-}，
 * 直接回 200 + 整个文件。此时如果把响应体从头开始写进偏移 N 的缓存文件，缓存就被
 * 污染了：播放器读到的是「后半段位置上的前半段数据」，表现为长视频播到一半后再也
 * 播不动、拖进度条也无效。</p>
 *
 * <p>规则（与 {@link HttpUrlSource#open(long)} 配合）：</p>
 * <ul>
 *   <li>offset == 0：接受 200（整段）或 206（合法的部分响应）。</li>
 *   <li>offset &gt; 0：必须是 206，且 Content-Range 的起点必须严格等于 offset；
 *       200 或起点不匹配一律拒绝，由上层抛 {@link ProxyCacheException} 触发直连回退。</li>
 * </ul>
 *
 * <p>本类不依赖 Android，可在 JVM 单测中直接验证。</p>
 */
public final class RangeResponseValidator {

    private static final Pattern CONTENT_RANGE =
            Pattern.compile("^\\s*bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$",
                    Pattern.CASE_INSENSITIVE);

    private RangeResponseValidator() {
    }

    /**
     * 该响应能否用于请求的偏移量。
     *
     * @param responseCode HTTP 状态码
     * @param contentRange Content-Range 响应头（可空）
     * @param requestedOffset 请求的起始偏移（字节）
     */
    public static boolean isAcceptable(int responseCode, String contentRange, long requestedOffset) {
        long offset = Math.max(0, requestedOffset);
        if (responseCode == 200) {
            // 服务器忽略了 Range：只有从 0 开始请求时才是完整、正确的数据。
            return offset == 0;
        }
        if (responseCode == 206) {
            return parseStart(contentRange) == offset;
        }
        return false;
    }

    /** Content-Range 的起始字节；缺失/格式非法返回 -1。 */
    public static long parseStart(String contentRange) {
        Matcher matcher = CONTENT_RANGE.matcher(contentRange == null ? "" : contentRange);
        if (!matcher.matches()) return -1;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Content-Range 里的总长度；未知（*）或缺失返回 -1。 */
    public static long parseTotalLength(String contentRange) {
        Matcher matcher = CONTENT_RANGE.matcher(contentRange == null ? "" : contentRange);
        if (!matcher.matches()) return -1;
        String total = matcher.group(3);
        if (total == null || "*".equals(total)) return -1;
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 计算源文件总长度：206 优先用 Content-Range 的 total；没有 total 时退化为
     * offset + Content-Length。200 的 Content-Length 就是总长度。
     */
    public static long resolveTotalLength(int responseCode, String contentRange,
                                          long contentLength, long requestedOffset) {
        if (responseCode == 206) {
            long total = parseTotalLength(contentRange);
            if (total >= 0) return total;
            if (contentLength >= 0) return Math.max(0, requestedOffset) + contentLength;
            return -1;
        }
        return contentLength;
    }
}
