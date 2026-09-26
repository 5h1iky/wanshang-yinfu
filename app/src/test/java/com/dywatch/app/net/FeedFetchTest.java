package com.dywatch.app.net;

// 联网冒烟测试：真实拉一条推荐流（仅 -Dnetwork.test=true 时执行，默认 Assume 跳过）
// 这是 M2 数据层的端到端验证：Rhino 签名 + OkHttp 请求 + Gson 解析全链路。

import com.dywatch.app.feed.FeedVideo;
import com.dywatch.app.sign.Signer;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FeedFetchTest {

    @Test
    public void fetchFeed_realNetwork() throws Exception {
        Assume.assumeTrue("联网测试需 -Dnetwork.test=true", Boolean.getBoolean("network.test"));
        DouyinApi api = new DouyinApi(newSigner());
        List<FeedVideo> list = api.fetchFeed(5);
        assertFalse("feed 为空", list.isEmpty());
        System.out.println("联网冒烟 OK，条数=" + list.size() + "，首条=" + list.get(0).title);
    }

    /**
     * 拉流冒烟加严：地址必须真的探活，不能只验 URL 前缀。
     * 教训（2026-09-26）：老断言只看 https://www.douyin.com/ 前缀加标题非空，
     * 自拼的缺签名地址同样能通过这种断言 —— 等于没断言。这里对每条发 Range GET，
     * 以 HTTP 2xx/3xx 加响应体非空为准（带播放用的 Referer 头，与 DouyinApi.playHeaders 一致）。
     */
    @Test
    public void fetchFeed_urlsArePlayable() throws Exception {
        Assume.assumeTrue("联网测试需 -Dnetwork.test=true", Boolean.getBoolean("network.test"));
        DouyinApi api = new DouyinApi(newSigner());
        List<FeedVideo> list = api.fetchFeed(5);
        assertFalse("feed 为空", list.isEmpty());

        OkHttpClient probe = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        int checked = 0;
        for (FeedVideo v : list) {
            Request req = new Request.Builder()
                    .url(v.playUrl)
                    .header("User-Agent", DouyinApi.UA)
                    .header("Referer", "https://www.douyin.com/")
                    .header("Range", "bytes=0-1023")
                    .build();
            try (Response resp = probe.newCall(req).execute()) {
                int code = resp.code();
                assertTrue("播放地址探活失败 HTTP " + code + " url=" + v.playUrl,
                        code >= 200 && code < 400);
                ResponseBody body = resp.body();
                assertNotNull("探活空响应: " + v.playUrl, body);
                if (body.contentLength() == 0) {
                    assertTrue("探活响应体为空（签名无效的典型表现）: " + v.playUrl,
                            body.bytes().length > 0);
                }
                checked++;
                System.out.println("探活 OK HTTP " + code + " "
                        + v.playUrl.substring(0, Math.min(60, v.playUrl.length())));
            }
        }
        assertTrue("一条都没探活", checked > 0);
    }

    private static Signer newSigner() throws Exception {
        File dir = new File("src/main/assets/sign");
        return new Signer(Arrays.asList(
                new String(Files.readAllBytes(new File(dir, "utils.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "sm3.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "vm_decode.js").toPath()), StandardCharsets.UTF_8)));
    }
}
