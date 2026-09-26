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
}
