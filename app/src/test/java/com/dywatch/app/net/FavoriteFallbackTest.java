package com.dywatch.app.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「喜欢列表」失败路径的纯逻辑单测（2026-10-25，P0-2）。
 *
 * 锁死三条口径：
 *   ① 上游风控（403 + ArgusSecurityPlugin）必须识别成"被风控拦截"，不能说成网络错误；
 *   ② 界面文案**不许**把英文异常/类名甩给用户（历史 bug：Gson 的
 *      "MalformedJsonException: Use JsonReader.setLenient(true)..." 直接出现在表盘上）；
 *   ③ 日志要留响应原文（限长），否则下次定案又没有证据。
 */
public class FavoriteFallbackTest {

    private static final String ARGUS_403 =
            "Blocked by ArgusSecurityPlugin Uifid Not Found";

    @Test
    public void 风控纯文本要识别成被拦截而不是网络错误() {
        String m = DouyinApi.favoriteFailureMessage(403, ARGUS_403);
        assertTrue("应说清是风控拦截：" + m, m.contains("风控"));
        assertTrue("应安抚并给出重试建议：" + m, m.contains("稍后再试"));
    }

    @Test
    public void 即使状态码不是403_带Argus字样也按风控处理() {
        String m = DouyinApi.favoriteFailureMessage(200, ARGUS_403);
        assertTrue(m.contains("风控"));
    }

    @Test
    public void 非JSON响应归类为接口返回异常() {
        String m = DouyinApi.favoriteFailureMessage(200, "<html>502 Bad Gateway</html>");
        assertTrue("应说是接口返回异常：" + m, m.contains("返回异常"));
    }

    @Test
    public void 正常JSON但status非零_仍是不可用() {
        String m = DouyinApi.favoriteFailureMessage(200, "{\"status_code\":8}");
        assertTrue(m.contains("暂不可用"));
    }

    @Test
    public void 中文异常原样透出() {
        String own = "喜欢列表暂不可用（接口被风控拦截），稍后再试";
        assertEquals(own, DouyinApi.friendlyMessage(new java.io.IOException(own)));
    }

    @Test
    public void 英文异常一律翻成人话() {
        String[] english = {
                "timeout",
                "unknown host",
                "Unable to resolve host \"www.douyin.com\": No address associated with hostname",
                "java.net.SocketTimeoutException: timeout",
        };
        for (String msg : english) {
            String m = DouyinApi.friendlyMessage(new java.io.IOException(msg));
            assertFalse("不许把英文原样甩给用户：" + msg + " → " + m, m.contains("Exception"));
            assertTrue("应给中文提示：" + msg + " → " + m, DouyinApi.hasChinese(m));
        }
    }

    @Test
    public void 空异常给兜底文案() {
        assertTrue(DouyinApi.hasChinese(DouyinApi.friendlyMessage(null)));
        assertTrue(DouyinApi.hasChinese(DouyinApi.friendlyMessage(new RuntimeException())));
    }

    @Test
    public void 响应原文日志要压平换行并截断() {
        String body = "line1\nline2\r\n" + repeat('x', 300);
        String head = DouyinApi.headOf(body, 200);
        assertFalse("日志里不该保留换行（logcat 一行会被截断）：" + head, head.contains("\n"));
        assertTrue("超长要截断并留省略号", head.endsWith("…"));
        assertTrue("长度受控：" + head.length(), head.length() <= 201);
        assertEquals("", DouyinApi.headOf(null, 200));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
