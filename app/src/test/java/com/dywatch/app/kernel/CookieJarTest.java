package com.dywatch.app.kernel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 内核版 cookie 传递的纯逻辑单测（2026-10-25，kernel 变体）。
 *
 * 为什么值得测：内核版要把 `LoginManager` 存的 cookie 头灌进 Gecko 的 cookie 仓，
 * 这段解析一旦出错（少一条 ttwid / 值被截断），现象是"页面能开但没登录"，
 * 排查成本极高。纯函数先锁住。
 */
public class CookieJarTest {

    @Test
    public void 正常头_逐条拆开() {
        List<Map<String, Object>> list = CookieJar.fromHeader("ttwid=abc123; odin_tt=def; uid=42");
        assertEquals(3, list.size());
        assertEquals("ttwid", list.get(0).get("name"));
        assertEquals("abc123", list.get(0).get("value"));
        assertEquals("uid", list.get(2).get("name"));
        assertEquals("42", list.get(2).get("value"));
    }

    @Test
    public void 空值与畸形片段不炸也不产出() {
        assertTrue(CookieJar.fromHeader(null).isEmpty());
        assertTrue(CookieJar.fromHeader("").isEmpty());
        assertTrue(CookieJar.fromHeader(" ; ; ").isEmpty());
        // 没有等号的片段丢掉；名字为空的丢掉
        assertEquals(1, CookieJar.fromHeader("broken; =empty; ok=1").size());
    }

    @Test
    public void 等号在值里时只按第一个等号切() {
        // 抖音的部分票据值自身带 '='（base64 尾巴），切错就会把值弄坏
        List<Map<String, Object>> list = CookieJar.fromHeader("token=ab=cd==");
        assertEquals(1, list.size());
        assertEquals("token", list.get(0).get("name"));
        assertEquals("ab=cd==", list.get(0).get("value"));
    }

    @Test
    public void 两边空白被吃掉() {
        List<Map<String, Object>> list = CookieJar.fromHeader("  a = 1 ; b= 2 ");
        assertEquals("a", list.get(0).get("name"));
        assertEquals("1", list.get(0).get("value"));
        assertEquals("2", list.get(1).get("value"));
    }

    @Test
    public void httpOnly标记存在且默认false() {
        Map<String, Object> c = CookieJar.fromHeader("x=1").get(0);
        assertTrue(c.containsKey("httpOnly"));
        assertFalse((Boolean) c.get("httpOnly"));
    }
}
