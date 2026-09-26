package com.dywatch.app.net;

// DouyinApi 里几个纯函数/解析边界的单测（离线，不联网）。
// 重点：JsonNull 边界——带登录态拉到的内容里混有图文贴（video 为 null），
// Gson 的 getAsJsonObject 遇到 JsonNull 会抛 ClassCastException，真机踩过。

import com.dywatch.app.feed.FeedVideo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DouyinApiParseTest {

    @Test
    public void commentTime_bucketsAreSane() {
        long now = System.currentTimeMillis() / 1000;
        assertEquals("刚刚", DouyinApi.formatCommentTime(now));
        assertEquals("5分钟前", DouyinApi.formatCommentTime(now - 5 * 60));
        assertEquals("3小时前", DouyinApi.formatCommentTime(now - 3 * 3600));
        assertEquals("2天前", DouyinApi.formatCommentTime(now - 2 * 86400));
        assertEquals("", DouyinApi.formatCommentTime(0));
    }

    /** video 为 null（图文贴）不能让整批解析炸掉，应跳过这一条 */
    @Test
    public void parseFeed_skipsItemWithNullVideo() {
        String json = "{\"aweme_list\":["
                + "{\"aweme_id\":\"1\",\"desc\":\"图文贴\",\"video\":null,"
                + "\"author\":{\"nickname\":\"a\"}},"
                + "{\"aweme_id\":\"2\",\"desc\":\"正常视频\","
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}},"
                + "\"author\":{\"nickname\":\"b\",\"sec_uid\":\"SEC\"}}"
                + "]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals("图文贴应被跳过，只留正常视频", 1, list.size());
        assertEquals("2", list.get(0).awemeId);
        assertEquals("SEC", list.get(0).authorSecUid);
    }

    /** statistics 为 null 时计数应回落为 0，而不是抛异常 */
    @Test
    public void parseFeed_handlesNullStatistics() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"9\",\"desc\":\"d\","
                + "\"statistics\":null,"
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals(0L, list.get(0).diggCount);
        assertEquals(0L, list.get(0).commentCount);
    }

    /** author 为 null 也不能炸 */
    @Test
    public void parseFeed_handlesNullAuthor() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"8\",\"desc\":\"d\","
                + "\"author\":null,"
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?x=1\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals("", list.get(0).authorName);
        assertEquals("", list.get(0).authorSecUid);
    }

    /** 整条 aweme_list 为 null 时返回空表而不是抛异常 */
    @Test
    public void parseFeed_handlesNullAwemeList() {
        List<FeedVideo> list = DouyinApi.parseFeed("{\"aweme_list\":null}");
        assertTrue(list.isEmpty());
    }

    /** 非 JSON 对象直接返回空表 */
    @Test
    public void parseFeed_handlesNonObject() {
        assertTrue(DouyinApi.parseFeed("[]").isEmpty());
    }

    /** 没有可播地址的条目应被丢弃（返回 null） */
    @Test
    public void parseFeed_skipsItemWithoutPlayUrl() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"7\",\"desc\":\"d\","
                + "\"video\":{\"play_addr\":{\"uri\":\"u\",\"url_list\":[]}}}]}";
        assertTrue(DouyinApi.parseFeed(json).isEmpty());
    }
}
