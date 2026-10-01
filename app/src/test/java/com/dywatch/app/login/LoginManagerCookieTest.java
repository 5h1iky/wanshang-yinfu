package com.dywatch.app.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * cookie 回写的域名补全（2026-10-01 真实踩坑：会话"回写成功"却拉不到会话）。
 *
 * 背景：`CookieManager.setCookie(url, "k=v")` 写出的是 **host-only** cookie（只对 www.douyin.com 生效），
 * 而登录会话是 `.douyin.com` 的**域 cookie**（imapi 等子域也要带）。只回写 host-only 时：
 * 页面能开、脚本正常、就是没有会话数据。所以回写时必须显式补 Domain/Path。
 */
public class LoginManagerCookieTest {

    @Test
    public void 裸键值对会补上域与路径() {
        String out = LoginManager.withDouyinDomain("sessionid=abc123");
        assertTrue(out, out.startsWith("sessionid=abc123"));
        assertTrue("要带域 " + out, out.contains("Domain=.douyin.com"));
        assertTrue("要带路径 " + out, out.contains("Path=/"));
    }

    @Test
    public void 已有域的不重复追加() {
        String out = LoginManager.withDouyinDomain("sessionid=abc; Domain=.douyin.com; Path=/");
        assertEquals(1, countOf(out.toLowerCase(), "domain="));
        assertEquals(1, countOf(out.toLowerCase(), "path="));
    }

    @Test
    public void 只缺路径时只补路径() {
        String out = LoginManager.withDouyinDomain("sid_guard=xyz; Domain=.douyin.com");
        assertEquals(1, countOf(out.toLowerCase(), "domain="));
        assertEquals(1, countOf(out.toLowerCase(), "path="));
        assertTrue(out.contains("sid_guard=xyz"));
    }

    @Test
    public void 大小写不敏感_不会重复追加() {
        String out = LoginManager.withDouyinDomain("a=1; domain=.douyin.com; PATH=/");
        assertEquals(1, countOf(out.toLowerCase(), "domain="));
        assertEquals(1, countOf(out.toLowerCase(), "path="));
    }

    @Test
    public void 空值与null原样返回() {
        assertEquals("", LoginManager.withDouyinDomain(""));
        assertNull(LoginManager.withDouyinDomain(null));
    }

    @Test
    public void 值原样保留_不被改动() {
        String out = LoginManager.withDouyinDomain("sid_ucp_v1=AAHKZ8");
        assertTrue("原值要完整保留：" + out, out.startsWith("sid_ucp_v1=AAHKZ8"));
        assertTrue(out.contains("Domain=.douyin.com"));
    }

    private static int countOf(String s, String needle) {
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
