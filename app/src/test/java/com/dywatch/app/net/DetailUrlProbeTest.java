package com.dywatch.app.net;

// 诊断用（联网）：detail 接口到底给不给"可播的"播放地址？
// 逐个候选发 Range GET，打印主机名 + HTTP 状态，用真实响应决定 firstUrl 该选谁。
// 运行：gradlew :app:testDebugUnitTest -Dnetwork.test=true --tests '*DetailUrlProbeTest*'

import com.dywatch.app.sign.Signer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DetailUrlProbeTest {

    @Test
    public void probeEveryPlayCandidate() throws Exception {
        Assume.assumeTrue("联网诊断需 -Dnetwork.test=true", Boolean.getBoolean("network.test"));
        File dir = new File("src/main/assets/sign");
        Signer signer = new Signer(Arrays.asList(
                new String(Files.readAllBytes(new File(dir, "utils.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "sm3.js").toPath()), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(new File(dir, "vm_decode.js").toPath()), StandardCharsets.UTF_8)));
        String id = "7658893735081676042";
        String url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?"
                + DouyinApi.buildDetailQuery(id, signer);

        OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).build();
        Request req = new Request.Builder().url(url)
                .header("User-Agent", DouyinApi.UA)
                .header("Referer", "https://www.douyin.com/jingxuan")
                .build();
        String body;
        try (Response r = client.newCall(req).execute()) {
            body = r.body() == null ? "" : r.body().string();
        }
        JsonElement el = JsonParser.parseString(body);
        JsonObject d = el.getAsJsonObject().getAsJsonObject("aweme_detail");
        System.out.println("detail status_code=" + opt(el.getAsJsonObject(), "status_code"));
        if (d == null) { System.out.println("无 aweme_detail，body 前 200=" + body.substring(0, Math.min(200, body.length()))); return; }
        JsonObject video = d.getAsJsonObject("video");
        System.out.println("video keys=" + keysOf(video));

        String[] keys = {"play_addr", "play_addr_265", "play_addr_h264", "download_addr", "thumbnail"};
        for (String k : keys) {
            JsonObject addr = video.getAsJsonObject(k);
            if (addr == null) { System.out.println("--- " + k + " = (无)"); continue; }
            JsonArray list = addr.getAsJsonArray("url_list");
            System.out.println("--- " + k + " uri=" + opt(addr, "uri") + " 候选数=" + (list == null ? 0 : list.size()));
            if (list == null) continue;
            for (int i = 0; i < list.size(); i++) {
                String u = list.get(i).getAsString();
                System.out.println("    [" + i + "] host=" + hostOf(u) + " len=" + u.length() + " 尾部=" + tail(u));
                try {
                    Request probe = new Request.Builder().url(u)
                            .header("User-Agent", DouyinApi.UA)
                            .header("Referer", "https://www.douyin.com/")
                            .header("Range", "bytes=0-1023")
                            .build();
                    try (Response pr = client.newCall(probe).execute()) {
                        System.out.println("        → HTTP " + pr.code() + " type="
                                + (pr.header("Content-Type") == null ? "?" : pr.header("Content-Type")));
                    }
                } catch (Exception e) {
                    System.out.println("        → 探测异常 " + e);
                }
            }
        }
    }

    private static String hostOf(String u) {
        try { return new java.net.URL(u).getHost(); } catch (Exception e) { return "?"; }
    }
    private static String tail(String u) {
        return u.length() > 90 ? u.substring(u.length() - 90) : u;
    }
    private static String keysOf(JsonObject o) {
        StringBuilder sb = new StringBuilder();
        for (String k : o.keySet()) sb.append(k).append(',');
        return sb.toString();
    }
    private static String opt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
