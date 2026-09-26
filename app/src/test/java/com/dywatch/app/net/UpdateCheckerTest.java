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
