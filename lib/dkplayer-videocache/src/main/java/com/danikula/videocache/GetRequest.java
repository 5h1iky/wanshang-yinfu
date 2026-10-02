package com.danikula.videocache;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Model for Http GET request.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class GetRequest {

    private static final Pattern RANGE_HEADER_PATTERN = Pattern.compile("[R,r]ange:[ ]?bytes=(\\d*)-");
    private static final Pattern URL_PATTERN = Pattern.compile("GET /(.*) HTTP");

    public final String uri;
    public final long rangeOffset;
    public final boolean partial;

    public GetRequest(String request) {
        checkNotNull(request);
        long offset = findRangeOffset(request);
        this.rangeOffset = Math.max(0, offset);
        this.partial = offset >= 0;
        this.uri = findUri(request);
    }

    public static GetRequest read(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        StringBuilder stringRequest = new StringBuilder();
        String line;
        while (!TextUtils.isEmpty(line = reader.readLine())) { // until new line (headers ending)
            stringRequest.append(line).append('\n');
        }
        return new GetRequest(stringRequest.toString());
    }

    private long findRangeOffset(String request) {
        Matcher matcher = RANGE_HEADER_PATTERN.matcher(request);
        if (matcher.find()) {
            String rangeValue = matcher.group(1);
            // ⚠️ 本地修改（2026-10-02，代码审计 M1）：
            // 正则是 `bytes=(\d*)-`，`\d*` 允许**空串**；而 RFC 7233 的后缀范围
            // `Range: bytes=-500`（完全合法的请求，播放器/预加载都可能发）正好让
            // group(1) 为空 → Long.parseLong("") 抛 NumberFormatException。
            // 超长数字（溢出）同样会抛。
            // 后果：请求在 FutureTask 里被静默吞掉 —— 没有响应、没有日志，用户只看到
            // "这条视频放不出来"。两种输入都不是攻击，所以按"整段返回"优雅降级。
            if (rangeValue == null || rangeValue.isEmpty()) {
                return -1;   // 后缀范围（最后 N 字节）：当成不带 Range 处理
            }
            try {
                return Long.parseLong(rangeValue);
            } catch (NumberFormatException e) {
                Logger.error("Range 头解析失败，按整段处理: " + rangeValue);
                return -1;
            }
        }
        return -1;
    }

    private String findUri(String request) {
        Matcher matcher = URL_PATTERN.matcher(request);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Invalid request `" + request + "`: url not found!");
    }

    @Override
    public String toString() {
        return "GetRequest{" +
                "rangeOffset=" + rangeOffset +
                ", partial=" + partial +
                ", uri='" + uri + '\'' +
                '}';
    }
}
