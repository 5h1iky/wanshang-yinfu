package com.dywatch.app.net;

// DouyinApi 纯函数单测（离线）：解析真实接口响应样本 + query 构造含签名。
// 联网冒烟测试见 FeedFetchTest（默认跳过，-Dnetwork.test=true 时执行）。

import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.sign.Signer;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DouyinApiTest {

    private static Signer newSigner() throws Exception {
        File dir = new File("src/main/assets/sign");
        List<String> js = Arrays.asList(
                read(new File(dir, "utils.js")),
                read(new File(dir, "sm3.js")),
                read(new File(dir, "vm_decode.js")));
        return new Signer(js);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void parseFeed_realResponseSample() throws Exception {
        File f = new File("src/test/resources/feed_sample.json");
        assertTrue("缺少测试样本: " + f.getAbsolutePath(), f.exists());
        List<FeedVideo> list = DouyinApi.parseFeed(read(f));
        assertFalse("样本应解析出视频", list.isEmpty());
        FeedVideo v = list.get(0);
        assertNotNull(v.playUrl);
        assertTrue("playUrl 异常: " + v.playUrl, v.playUrl.startsWith("http"));
        assertTrue("coverUrl 异常: " + v.coverUrl, v.coverUrl.startsWith("http"));
    }

    @Test
    public void buildFeedQuery_containsSignature() throws Exception {
        String q = DouyinApi.buildFeedQuery(10, newSigner());
        assertTrue("缺 a_bogus: " + q, q.contains("&a_bogus="));
        assertTrue("缺 count", q.contains("count=10"));
        assertTrue("缺设备参数", q.contains("device_platform=webapp"));
    }

    /** 手表选档：H.264 + 540p 优先、同档取最低码率；HEVC 一律不选（低端 SoC 无硬解） */
    @Test
    public void pickGearUrl_prefersH264_540p_lowestBitrate() {
        String json = "{"
                + "\"bit_rate\":["
                + gear("normal_1080_0", 1993040, 0, 0, "g1080") + ","
                + gear("normal_540_0", 900000, 1, 0, "g540hevc") + ","
                + gear("low_540_0", 1127383, 0, 0, "g540low") + ","
                + gear("normal_540_0", 1683402, 0, 0, "g540hi") + ","
                + gear("normal_720_0", 1332178, 0, 0, "g720")
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String url = DouyinApi.pickGearUrl(video, "7412");
        assertTrue("应选 H.264 的 540p 档，实际: " + url, url.contains("g540low"));
        assertFalse("不该选 HEVC 档: " + url, url.contains("g540hevc"));
        assertFalse("不该选 1080p: " + url, url.contains("g1080"));
        assertFalse("同档应取更低码率: " + url, url.contains("g540hi"));
    }

    /** 没有 H.264 档时不能硬凑，返回空让调用方回退默认 play_addr */
    @Test
    public void pickGearUrl_returnsEmptyWhenOnlyHevc() {
        String json = "{\"bit_rate\":[" + gear("normal_540_0", 900000, 1, 0, "a") + ","
                + gear("normal_1080_0", 900000, 0, 1, "b") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /** is_bytevc1 是字节的 HEVC 变体，同样要排除 */
    @Test
    public void pickGearUrl_excludesBytevc1() {
        String json = "{\"bit_rate\":[" + gear("normal_540_0", 1000, 0, 1, "bytevc") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /**
     * DASH 档是单轨流（站点另发请求配音频），MediaPlayer 播它会没声音 →
     * 即使档位更优也必须跳过，退而选非 DASH 的那一档。
     */
    @Test
    public void pickGearUrl_skipsDashGearToKeepAudio() {
        String json = "{\"bit_rate\":["
                + dashGear("normal_540_0", 500000, "dash540") + ","   // 更优档，但是 DASH
                + gear("normal_720_0", 1300000, 0, 0, "mp4720")       // 次优但带音轨
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String url = DouyinApi.pickGearUrl(video, "7412");
        assertTrue("应避开 DASH 单轨档，实际: " + url, url.contains("mp4720"));
        assertFalse("不能返回 DASH 地址（会没声音）: " + url, url.contains("dash540"));
    }

    /** 全是 DASH 时宁可返回空，让上层回退默认 play_addr，也不能给用户无声视频 */
    @Test
    public void pickGearUrl_returnsEmptyWhenAllDash() {
        String json = "{\"bit_rate\":[" + dashGear("normal_540_0", 500000, "a") + ","
                + dashGear("normal_720_0", 900000, "b") + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(DouyinApi.pickGearUrl(video, "7412").isEmpty());
    }

    /** 画质开关要真的改变选档结果：省流量走 540p，清晰档才允许 1080p */
    @Test
    public void pickGearUrl_respectsQualitySetting() {
        String json = "{\"bit_rate\":["
                + gear("normal_1080_0", 1993040, 0, 0, "g1080") + ","
                + gear("normal_720_0", 1332178, 0, 0, "g720") + ","
                + gear("adapt_low_540_0", 478000, 0, 0, "g540")
                + "]}";
        com.google.gson.JsonObject video =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        int saved = DouyinApi.sQuality;
        try {
            DouyinApi.sQuality = 0;   // 省流量
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g540"));
            DouyinApi.sQuality = 1;   // 平衡
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g720"));
            DouyinApi.sQuality = 2;   // 清晰
            assertTrue(DouyinApi.pickGearUrl(video, "7412").contains("g1080"));
        } finally {
            DouyinApi.sQuality = saved;
        }
    }

    private static String gear(String name, long br, int h265, int bytevc1, String marker) {
        return "{\"gear_name\":\"" + name + "\",\"bit_rate\":" + br
                + ",\"is_h265\":" + h265 + ",\"is_bytevc1\":" + bytevc1
                + ",\"play_addr\":{\"uri\":\"v0200fg10000d98f4jno\",\"url_list\":["
                + "\"https://v11-weba.douyinvod.com/x/" + marker + "\","
                + "\"https://www.douyin.com/aweme/v1/play/?file_id=" + marker + "&sign=abc\"]}}";
    }

    /** DASH 档：www 候选路径是 /aweme/v1/play/dash/，单轨无音频 */
    private static String dashGear(String name, long br, String marker) {
        return "{\"gear_name\":\"" + name + "\",\"bit_rate\":" + br
                + ",\"is_h265\":0,\"is_bytevc1\":0"
                + ",\"play_addr\":{\"uri\":\"v0200fg10000d98f4jno\",\"url_list\":["
                + "\"https://v11-weba.douyinvod.com/x/" + marker + "\","
                + "\"https://www.douyin.com/aweme/v1/play/dash/?file_id=" + marker + "&sign=abc\"]}}";
    }
}
