package com.dywatch.app.chat.api;

/**
 * 凭证提取状态机（纯逻辑，JVM 可测）。
 *
 * 2026-11-07 审计：旧 CredExtractor 一拿到空/不完整结果就 finish(false) 并销毁 WebView，
 * 后面的重试循环实际失效。这里的规则是：
 *   · 只有**完整凭证**才 COMPLETE；
 *   · 不完整结果一律 WAIT（继续等下一轮 JS 读取）；
 *   · 只有达到总超时才 TIMEOUT；
 *   · 完整凭证优先于超时（最后一刻拿到也算成功）。
 */
public final class CredExtractionPolicy {

    public enum Decision {
        WAIT,
        COMPLETE,
        TIMEOUT
    }

    private CredExtractionPolicy() {}

    public static Decision decide(boolean complete, long elapsedMs, long timeoutMs) {
        if (complete) return Decision.COMPLETE;
        if (timeoutMs > 0 && elapsedMs >= timeoutMs) return Decision.TIMEOUT;
        return Decision.WAIT;
    }
}
