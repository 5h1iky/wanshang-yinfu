package com.dywatch.app.net;

// AnnounceApi 纯逻辑单测（离线）：解析与"该不该显示"的判定。
// 口径来源（用户拍板）：enabled=false / 缺 id / 缺 title / 已过期 → 都算"无公告"；
// 同一条读过（lastReadId 相同）不再提示。

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AnnounceApiTest {

    private static final long NOW = 1_790_000_000_000L;

    private static final String FULL =
            "{\"enabled\":true,\"id\":\"2026-09-27-01\",\"level\":\"warn\","
                    + "\"title\":\"签名已更新\",\"text\":\"已自动修复，无需操作\","
                    + "\"link\":\"https://example.com/n\",\"showUntil\":0}";

    @Test
    public void parse_full() {
        AnnounceApi.Announcement a = AnnounceApi.parse(FULL, NOW);
        assertNotNull(a);
        assertEquals("2026-09-27-01", a.id);
        assertEquals(AnnounceApi.LEVEL_WARN, a.level);
        assertEquals("签名已更新", a.title);
        assertEquals("已自动修复，无需操作", a.text);
        assertEquals("https://example.com/n", a.link);
        assertEquals(0L, a.showUntil);
    }

    @Test
    public void parse_disabledIsNoAnnouncement() {
        String json = "{\"enabled\":false,\"id\":\"x\",\"title\":\"t\"}";
        assertNull(AnnounceApi.parse(json, NOW));
    }

    @Test
    public void parse_missingIdOrTitle() {
        assertNull(AnnounceApi.parse("{\"title\":\"t\"}", NOW));
        assertNull(AnnounceApi.parse("{\"id\":\"x\"}", NOW));
        assertNull(AnnounceApi.parse("{\"id\":\"\",\"title\":\"t\"}", NOW));
    }

    @Test
    public void parse_expired() {
        String json = "{\"id\":\"x\",\"title\":\"t\",\"showUntil\":" + (NOW - 1) + "}";
        assertNull(AnnounceApi.parse(json, NOW));
        // 未过期照常返回
        String json2 = "{\"id\":\"x\",\"title\":\"t\",\"showUntil\":" + (NOW + 1000) + "}";
        assertNotNull(AnnounceApi.parse(json2, NOW));
    }

    @Test
    public void parse_badInput() {
        assertNull(AnnounceApi.parse("not json", NOW));
        assertNull(AnnounceApi.parse("[]", NOW));
        assertNull(AnnounceApi.parse("", NOW));
        assertNull(AnnounceApi.parse(null, NOW));
    }

    @Test
    public void parse_levelFallsBackToInfo() {
        AnnounceApi.Announcement a = AnnounceApi.parse("{\"id\":\"x\",\"title\":\"t\",\"level\":\"weird\"}", NOW);
        assertNotNull(a);
        assertEquals(AnnounceApi.LEVEL_INFO, a.level);
        // 缺 level 也是 info
        AnnounceApi.Announcement b = AnnounceApi.parse("{\"id\":\"x\",\"title\":\"t\"}", NOW);
        assertNotNull(b);
        assertEquals(AnnounceApi.LEVEL_INFO, b.level);
    }

    @Test
    public void shouldShow_readAndExpiryRules() {
        AnnounceApi.Announcement a = AnnounceApi.parse(FULL, NOW);
        assertNotNull(a);
        assertTrue(AnnounceApi.shouldShow(a, "", NOW));                 // 没读过 → 显示
        assertFalse(AnnounceApi.shouldShow(a, a.id, NOW));              // 同 id 已读 → 不显示
        assertFalse(AnnounceApi.shouldShow(a, "other-id-old", NOW) == false); // 其它已读 id 不影响
        assertFalse(AnnounceApi.shouldShow(null, "", NOW));             // 无公告 → 不显示
        // 过期判定独立生效（即使没读过）
        AnnounceApi.Announcement exp = AnnounceApi.parse(
                "{\"id\":\"y\",\"title\":\"t\",\"showUntil\":" + (NOW + 10) + "}", NOW);
        assertNotNull(exp);
        assertTrue(AnnounceApi.shouldShow(exp, "", NOW));
        assertFalse(AnnounceApi.shouldShow(exp, "", NOW + 100));
    }

    @Test
    public void endpoints_orderedByReachability() {
        // 通道顺序是连通性实测的结论：jsDelivr 优先（国内可达），Worker 兜底。
        // 这条断言防"手滑改顺序"——顺序变了必须重新测连通性。
        assertEquals(3, AnnounceApi.ENDPOINTS.length);
        assertTrue(AnnounceApi.ENDPOINTS[0].contains("cdn.jsdelivr.net"));
        assertTrue(AnnounceApi.ENDPOINTS[2].contains("workers.dev"));
    }
}
