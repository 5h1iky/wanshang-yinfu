package com.dywatch.app.net;

// Cookie 出站守卫（2026-10-02，代码审计 M2）。
//
// 问题：DouyinApi 是**手工**往请求头里塞 Cookie 的（不是 OkHttp 的 CookieJar），
// 而 client 开着 followRedirects(true)。OkHttp 的重定向重试是在
// RetryAndFollowUpInterceptor 里做的，它会把原请求头**原样复制**到跳转目标
// （实测其字节码常量池里只有 Authorization 的 sameScheme 判断，**没有 Cookie** 这一项）。
// 于是：抖音（或链路上的任何一环）回一个 302 到站外主机，我们就会把
// **完整登录态 cookie** 一起送给那台机器。
//
// 修法：注册成**网络拦截器**（不是应用拦截器）——应用拦截器只看得到最初那次请求，
// 而网络拦截器在每一跳上都会被调用，正好能逐跳检查。
// 规则很硬：只对抖音自家域带 Cookie，其余主机一律摘掉。
//
// 用 java.net.URI 而不是 android.net.Uri：这个类要保持纯 JVM 可测（有单测，
// 而且是**真起两个本地 HTTP 服务 + 真 302 跳转**的端到端测法）。

import java.net.URI;
import java.util.Locale;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public final class CookieHostGuard implements Interceptor {

    /**
     * 允许携带 Cookie 的域名后缀。与 ChatEngine 的导航白名单同族：
     * 会话本来就是在这些域之间生效的（douyin 主站 + 同族接口/风控域）。
     */
    private static final String[] DEFAULT_ALLOWED = {
            "douyin.com",
            "snssdk.com",
            "bytedance.com",
            "amemv.com",
            "ixigua.com",
    };

    private final String[] allowed;

    public CookieHostGuard() {
        this(DEFAULT_ALLOWED);
    }

    /** 仅供单测注入白名单（生产代码只用无参构造） */
    CookieHostGuard(String[] allowed) {
        this.allowed = allowed;
    }

    @Override
    public Response intercept(Chain chain) throws java.io.IOException {
        Request req = chain.request();
        if (req.header("Cookie") != null && !matches(req.url().host(), allowed)) {
            req = req.newBuilder().removeHeader("Cookie").build();
        }
        return chain.proceed(req);
    }

    /** 该主机是否属于抖音自家域（生产口径） */
    public static boolean isAllowedHost(String host) {
        return matches(host, DEFAULT_ALLOWED);
    }

    static boolean matches(String host, String[] suffixes) {
        if (host == null || suffixes == null) return false;
        String h = host.toLowerCase(Locale.US);
        for (String suffix : suffixes) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        }
        return false;
    }

    /** 供单测直接调：从 URL 串取 host（取不到视为不允许） */
    public static boolean isAllowedUrl(String url) {
        if (url == null) return false;
        try {
            return isAllowedHost(new URI(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
