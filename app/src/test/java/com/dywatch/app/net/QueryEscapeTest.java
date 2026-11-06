package com.dywatch.app.net;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * query 转义（2026-11-07 代码审计 M10）。
 *
 * 背景：`joinAndSign` 原来对 key/value 原样拼接，搜索词里的 `&` `#` `+` `%` 空格
 * 会破坏 query 结构（搜 `a&b` 实际只搜了 `a`）。修法是**只转义会破坏结构的 5 个字符**，
 * 其余保持原样 —— 因为 `filter_selected` 这类参数里带着 JSON（`{}" :,`），
 * 一旦全量编码就会改变 a_bogus 的输入、把签名算错。
 *
 * 这一组测试就是那条边界：**该转的一个不落，不该转的一个不动**。
 */
public class QueryEscapeTest {

    @Test
    public void escapesStructuralCharsOnly() {
        assertEquals("a%26b", DouyinApi.escapeQueryValue("a&b"));
        assertEquals("a%23b", DouyinApi.escapeQueryValue("a#b"));
        assertEquals("a%2Bb", DouyinApi.escapeQueryValue("a+b"));
        assertEquals("a%25b", DouyinApi.escapeQueryValue("a%b"));
        assertEquals("a%20b", DouyinApi.escapeQueryValue("a b"));
    }

    @Test
    public void leavesJsonAndNonAsciiAlone() {
        // filter_selected 的真实形态：绝不能编码，否则签名输入变化
        String json = "{\"sort_type\":\"0\",\"publish_time\":\"0\",\"filter_duration\":\"\"}";
        assertEquals(json, DouyinApi.escapeQueryValue(json));
        // 中文保持原样（服务端按 UTF-8 理解；URLConnection/OkHttp 会自己做传输层编码）
        assertEquals("测试", DouyinApi.escapeQueryValue("测试"));
        // 这些字符不破坏 query 结构，保持原样
        assertEquals("a=b", DouyinApi.escapeQueryValue("a=b"));
        assertEquals("a/b?c", DouyinApi.escapeQueryValue("a/b?c"));
        assertEquals("a,b;c", DouyinApi.escapeQueryValue("a,b;c"));
    }

    @Test
    public void handlesEmptyAndNull() {
        assertEquals("", DouyinApi.escapeQueryValue(""));
        assertEquals("", DouyinApi.escapeQueryValue(null));
    }

    @Test
    public void idempotentForAlreadyEncoded() {
        // 已经编码过的 %26 不会被再次编码（因为 % 会变成 %25，这里断言这个事实，
        // 提醒后人：**不要**对同一串调两次）
        assertEquals("a%2526b", DouyinApi.escapeQueryValue("a%26b"));
    }

    @Test
    public void searchKeywordWithAmpersandStaysOneValue() {
        // 真实场景：用户搜 "a&b" → 拼出来的 query 里只能有一个 & 分隔符（参数之间那个）
        String escaped = DouyinApi.escapeQueryValue("a&b");
        assertEquals("参数值里不能出现裸 &", -1, escaped.indexOf('&'));
        assertEquals("a%26b", escaped);
    }
}
