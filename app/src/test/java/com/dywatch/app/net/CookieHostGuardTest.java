package com.dywatch.app.net;

// Cookie 出站守卫的端到端回归测试（2026-10-02，代码审计 M2）。
//
// 不是"调一下 isAllowedHost 看返回值"那种测法 —— 这里**真的起两个本地 HTTP 服务**，
// 让 OkHttp 自己走一遍 302 重定向，然后断言目标服务器到底有没有收到 Cookie。
// 因为要复现的正是"OkHttp 在重定向时把手工塞的请求头原样复制到新主机"这个行为：
// 只有走完整条链（含 RetryAndFollowUpInterceptor → 网络拦截器）才能证明它被挡住了。
//
// 两个服务分别绑 127.0.0.1 与 127.0.0.2（整个 127.0.0.0/8 都是回环）：
// 对拦截器来说这是两个不同的 host，但不需要 DNS、不需要改 hosts、不用 wildcard 绑定。
// 用裸 ServerSocket 而不是 com.sun.net.httpserver —— 后者在 AGP 的编译参数下不可见。

import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CookieHostGuardTest {

    private static final String SECRET = "sessionid=SECRET_VALUE";

    @Test
    public void 跨主机302时cookie被摘掉() throws Exception {
        try (MiniServer fin = new MiniServer("127.0.0.2", 200, null);
             MiniServer hop = new MiniServer("127.0.0.1", 302,
                     "http://127.0.0.2:" + fin.port() + "/final")) {
            // 白名单里只放行第一跳的主机：于是"第一跳带着 Cookie、第二跳没了"
            // 只能归因于拦截器，而不是一开始就没带。
            String code = call(hop, new CookieHostGuard(new String[]{"127.0.0.1"}));
            assertEquals("200", code);
            assertEquals("跨主机重定向后不该再带 Cookie", "(none)", fin.cookie());
            assertEquals("第一跳应当带着 Cookie", SECRET, hop.cookie());
        }
    }

    @Test
    public void 同主机302时cookie保留() throws Exception {
        try (MiniServer fin = new MiniServer("127.0.0.1", 200, null);
             MiniServer hop = new MiniServer("127.0.0.1", 302,
                     "http://127.0.0.1:" + fin.port() + "/final")) {
            String code = call(hop, new CookieHostGuard(new String[]{"127.0.0.1"}));
            assertEquals("200", code);
            // 反向对照：证明拦截器不是"无条件把 Cookie 删掉"——那样等于把功能砍了
            assertEquals("同主机跳转必须保留 Cookie", SECRET, fin.cookie());
        }
    }

    @Test
    public void 默认白名单下压根不带出去() throws Exception {
        try (MiniServer s = new MiniServer("127.0.0.1", 200, null)) {
            String code = call(s, new CookieHostGuard());
            assertEquals("200", code);
            assertEquals("非抖音域一律不带 Cookie", "(none)", s.cookie());
        }
    }

    @Test
    public void 抖音主站保留_站外摘掉() {
        assertTrue(CookieHostGuard.isAllowedHost("www.douyin.com"));
        assertTrue(CookieHostGuard.isAllowedHost("DOUYIN.COM"));
        assertTrue(CookieHostGuard.isAllowedHost("sso.douyin.com"));
        assertTrue(CookieHostGuard.isAllowedHost("snssdk.com"));
        assertFalse("后缀必须是整段匹配：evil-douyin.com 不是抖音域",
                CookieHostGuard.isAllowedHost("evil-douyin.com"));
        assertFalse(CookieHostGuard.isAllowedHost("douyin.com.evil.net"));
        assertFalse(CookieHostGuard.isAllowedHost("example.com"));
        assertFalse(CookieHostGuard.isAllowedHost(null));
        assertFalse(CookieHostGuard.isAllowedUrl("http://example.com/x"));
        assertFalse(CookieHostGuard.isAllowedUrl("not a url"));
    }

    private String call(MiniServer server, CookieHostGuard guard) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .addNetworkInterceptor(guard)
                .build();
        Request req = new Request.Builder()
                .url("http://" + server.host() + ":" + server.port() + "/hop")
                .header("Cookie", SECRET)
                .build();
        AtomicReference<String> code = new AtomicReference<>("(无响应)");
        try (Response resp = client.newCall(req).execute()) {
            code.set(String.valueOf(resp.code()));
        }
        return code.get();
    }

    /** 极简 HTTP 服务：只为"回一个固定响应 + 记录收到的 Cookie"而存在 */
    private static final class MiniServer implements Closeable {
        private final ServerSocket server;
        private final AtomicReference<String> cookie = new AtomicReference<>("(none)");
        private volatile boolean stopped;

        MiniServer(String bindHost, int status, String location) throws Exception {
            final int st = status;
            final String loc = location;
            server = new ServerSocket(0, 8, InetAddress.getByName(bindHost));
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(st, loc);
                }
            }, "mini-http-" + bindHost);
            t.setDaemon(true);
            t.start();
        }

        String host() {
            return server.getInetAddress().getHostAddress();
        }

        int port() {
            return server.getLocalPort();
        }

        String cookie() {
            return cookie.get();
        }

        private void serve(int status, String location) {
            while (!stopped) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Cookie:", 0, 7)) {
                            cookie.set(line.substring(7).trim());
                        }
                    }
                    StringBuilder sb = new StringBuilder();
                    if (status == 302) {
                        sb.append("HTTP/1.1 302 Found\r\nLocation: ").append(location).append("\r\n");
                    } else {
                        sb.append("HTTP/1.1 200 OK\r\n");
                    }
                    sb.append("Content-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
                    OutputStream out = s.getOutputStream();
                    out.write(sb.toString().getBytes("UTF-8"));
                    out.flush();
                } catch (Exception ignored) {
                    // 关服务时 accept 会抛，正常
                }
            }
        }

        @Override
        public void close() {
            stopped = true;
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }
    }
}
