package com.dywatch.app.net;

// UpdateChecker 纯逻辑单测（离线）

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {

    private static final String SAMPLE =
            "{\"tag_name\":\"v0.2.0\",\"body\":\"修复若干问题\\n新增聊天\","
                    + "\"html_url\":\"https://example.com/r\","
                    + "\"assets\":[{\"name\":\"wanshang-yinfu-0.2.0.apk\","
                    + "\"browser_download_url\":\"https://example.com/a.apk\"}]}";

    @Test
    public void parseRelease_ok() {
        UpdateChecker.UpdateInfo info = UpdateChecker.parseRelease(SAMPLE);
        assertNotNull(info);
        assertEquals("0.2.0", info.version);
        assertTrue(info.notes.startsWith("修复"));
        assertEquals("https://example.com/a.apk", info.apkUrl);
        assertEquals("https://example.com/r", info.htmlUrl);
    }

    @Test
    public void parseRelease_bad() {
        assertNull(UpdateChecker.parseRelease("{}"));
        assertNull(UpdateChecker.parseRelease("not json"));
    }

    // ---- 形状守卫（2026-10-02，代码审计 M5）----
    // 这几条原来会**抛异常**而不是返回 null，而 check() 跑在一个裸线程上 →
    // 异常 = 启动瞬间闪退。GitHub 的返回形状不由我们控制（限流/降级/字段改名都会变）。
    @Test
    public void parseRelease_assets形状异常不炸() {
        // assets 是对象 → 旧实现 getAsJsonArray 直接抛 UnsupportedOperationException
        UpdateChecker.UpdateInfo a = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.3.0\",\"assets\":{\"oops\":1}}");
        assertNotNull("应该保住版本号，只是没有下载地址", a);
        assertEquals("0.3.0", a.version);
        assertEquals("", a.apkUrl);
    }

    @Test
    public void parseRelease_assets元素不是对象不炸() {
        UpdateChecker.UpdateInfo a = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.3.1\",\"assets\":[\"字符串\",null,42]}");
        assertNotNull(a);
        assertEquals("", a.apkUrl);
    }

    @Test
    public void parseRelease_文本字段是对象不炸() {
        // body/tag_name 被换成对象时，getAsString 会抛
        UpdateChecker.UpdateInfo a = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v0.4.0\",\"body\":{\"x\":1},\"html_url\":[1,2]}");
        assertNotNull(a);
        assertEquals("0.4.0", a.version);
        assertEquals("", a.notes);
        assertEquals("", a.htmlUrl);
    }

    @Test
    public void parseRelease_顶层不是对象返回null() {
        assertNull(UpdateChecker.parseRelease("[1,2,3]"));
        assertNull(UpdateChecker.parseRelease("null"));
        assertNull(UpdateChecker.parseRelease(""));
    }

    @Test
    public void isNewer_compares() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"));
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"));
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"));
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateChecker.isNewer("0.0.9", "0.1.0"));
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0")); // 数值比较不是字符串比较
    }
}
