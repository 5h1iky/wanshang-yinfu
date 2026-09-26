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

import static org.junit.Assert.assertFalse;

public class FeedFetchTest {

    @Test
    public void fetchFeed_realNetwork() throws Exception {
        Assume.assumeTrue("联网测试需 -Dnetwork.test=true", Boolean.getBoolean("network.test"));
        File dir = new File("src/main/assets/sign");
        Signer signer = new Signer(Arrays.asList(
                new String(Files.readAllBytes(new File(dir, "utils.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "sm3.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "vm_decode.js").toPath()), StandardCharsets.UTF_8)));
        DouyinApi api = new DouyinApi(signer);
        List<FeedVideo> list = api.fetchFeed(5);
        assertFalse("feed 为空", list.isEmpty());
        System.out.println("联网冒烟 OK，条数=" + list.size() + "，首条=" + list.get(0).title);
    }
}
