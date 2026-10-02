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

    private static final String RELEASE_URL =
            "https://github.com/5h1iky/wanshang-yinfu/releases/latest";

    private static final String FULL =
            "{\"enabled\":true,\"id\":\"2026-09-27-01\",\"level\":\"warn\","
                    + "\"title\":\"签名已更新\",\"text\":\"已自动修复，无需操作\","
                    + "\"link\":\"" + RELEASE_URL + "\",\"showUntil\":0}";

    @Test
    public void parse_full() {
        AnnounceApi.Announcement a = AnnounceApi.parse(FULL, NOW);
        assertNotNull(a);
        assertEquals("2026-09-27-01", a.id);
        assertEquals(AnnounceApi.LEVEL_WARN, a.level);
        assertEquals("签名已更新", a.title);
        assertEquals("已自动修复，无需操作", a.text);
        assertEquals(RELEASE_URL, a.link);
        assertEquals(0L, a.showUntil);
    }

    // ---- 外链白名单（2026-10-02，代码审计 M4）----
    // 公告 JSON 来自网络，link 会被直接交给 ACTION_VIEW。只认 https + GitHub 域。
    @Test
    public void safeLink_只放行https的github链接() {
        assertEquals(RELEASE_URL, AnnounceApi.safeLink(RELEASE_URL));
        assertEquals("https://github.com/a/b", AnnounceApi.safeLink("https://github.com/a/b"));
        // 子域也放行（GitHub 的下载会跳到 objects.githubusercontent.com 之类，
        // 但公告这里只用到 github.com；保留子域是为了将来放行 gist 等）
        assertEquals("https://gist.github.com/x", AnnounceApi.safeLink("https://gist.github.com/x"));
    }

    @Test
    public void safeLink_拒绝http与非github域() {
        assertEquals("", AnnounceApi.safeLink("http://github.com/a/b"));          // 降级到明文
        assertEquals("", AnnounceApi.safeLink("https://example.com/n"));
        assertEquals("", AnnounceApi.safeLink("https://github.com.evil.net/x")); // 后缀伪装
        assertEquals("", AnnounceApi.safeLink("https://evil-github.com/x"));
        assertEquals("", AnnounceApi.safeLink("javascript:alert(1)"));
        assertEquals("", AnnounceApi.safeLink("intent://x#Intent;scheme=http;end"));
        assertEquals("", AnnounceApi.safeLink("file:///etc/passwd"));
        assertEquals("", AnnounceApi.safeLink("/releases/latest"));               // 相对路径
        assertEquals("", AnnounceApi.safeLink(""));
        assertEquals("", AnnounceApi.safeLink(null));
    }

    @Test
    public void parse_站外link被清空但公告照常显示() {
        String json = "{\"enabled\":true,\"id\":\"x1\",\"title\":\"标题\","
                + "\"text\":\"正文\",\"link\":\"https://phish.example/x\"}";
        AnnounceApi.Announcement a = AnnounceApi.parse(json, NOW);
        assertNotNull("公告本身要正常显示", a);
        assertEquals("", a.link);
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
        assertTrue(AnnounceApi.shouldShow(a, "other-id-old", NOW));     // 别的已读 id 不影响
        assertFalse(AnnounceApi.shouldShow(null, "", NOW));             // 无公告 → 不显示
        // 过期判定独立生效（即使没读过）
        AnnounceApi.Announcement exp = AnnounceApi.parse(
                "{\"id\":\"y\",\"title\":\"t\",\"showUntil\":" + (NOW + 10) + "}", NOW);
        assertNotNull(exp);
        assertTrue(AnnounceApi.shouldShow(exp, "", NOW));
        assertFalse(AnnounceApi.shouldShow(exp, "", NOW + 100));
    }

    @Test
    public void endpoints_orderedByFreshnessThenReachability() {
        // 顺序是"新鲜度 + 可达性"双重实测的结论，改了必须重新测：
        //   gh-api（无 CDN 缓存、每次都新鲜、国内可达）→ jsdelivr（可达但有 12h 分支缓存）
        //   → jsdelivr-fastly → worker（KV 即时但本网络不可达，故排最后避免拖慢）
        assertEquals(4, AnnounceApi.ENDPOINTS.length);
        assertEquals("gh-api", AnnounceApi.ENDPOINTS[0].name);
        assertTrue(AnnounceApi.ENDPOINTS[1].url.contains("cdn.jsdelivr.net"));
        assertEquals("worker", AnnounceApi.ENDPOINTS[3].name);
    }

    @Test
    public void githubContents_decodesBase64WithNewlines() {
        // GitHub contents API 会把 base64 按 60 字符插换行——必须能解开
        String inner = "{\"enabled\":true,\"id\":\"gh-1\",\"title\":\"来自 API\"}";
        String b64 = java.util.Base64.getMimeEncoder(60, new byte[]{'\n'})
                .encodeToString(inner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String apiResp = "{\"name\":\"announce.json\",\"encoding\":\"base64\",\"content\":\"" + b64 + "\"}";
        AnnounceApi.Announcement a = AnnounceApi.parseGithubContents(apiResp, NOW);
        assertNotNull(a);
        assertEquals("gh-1", a.id);
        assertEquals("来自 API", a.title);
    }

    @Test
    public void githubContents_badInputs() {
        assertNull(AnnounceApi.parseGithubContents("not json", NOW));
        // 非法 base64 解出的内容不是公告 JSON → null
        assertNull(AnnounceApi.parseGithubContents("{\"content\":\"!!!\"}", NOW));
    }

    @Test
    public void githubContents_rawVariantFallsBackToPlainParse() {
        // 实测坑：Accept 带 vnd.github.raw+json 时 GitHub 直接返回裸文件内容（没有 content 字段）。
        // 解析器必须兼容这种形态，否则会误判成"无有效公告"（真机 diagnostics 里就是这么暴露的）。
        AnnounceApi.Announcement a = AnnounceApi.parseGithubContents(FULL, NOW);
        assertNotNull(a);
        assertEquals("2026-09-27-01", a.id);
        // 裸的 disabled 公告照样按"无公告"处理
        assertNull(AnnounceApi.parseGithubContents("{\"enabled\":false,\"id\":\"x\",\"title\":\"t\"}", NOW));
    }

    @Test
    public void decodeBase64_basic() {
        // 自实现的解码器（不用 android.util.Base64 以保 JVM 可测；不用 java.util.Base64 因 minSdk 21）
        String src = "{\"a\":1}";
        String enc = java.util.Base64.getEncoder().encodeToString(src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(src, new String(AnnounceApi.decodeBase64(enc), java.nio.charset.StandardCharsets.UTF_8));
        // 带换行也要能解
        String wrapped = enc.substring(0, 4) + "\n" + enc.substring(4);
        assertEquals(src, new String(AnnounceApi.decodeBase64(wrapped), java.nio.charset.StandardCharsets.UTF_8));
    }
}
