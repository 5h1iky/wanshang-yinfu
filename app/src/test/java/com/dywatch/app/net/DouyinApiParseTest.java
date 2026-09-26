package com.dywatch.app.net;

// DouyinApi 里几个纯函数/解析边界的单测（离线，不联网）。
// 重点：JsonNull 边界——带登录态拉到的内容里混有图文贴（video 为 null），
// Gson 的 getAsJsonObject 遇到 JsonNull 会抛 ClassCastException，真机踩过。

import com.dywatch.app.feed.FeedVideo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ---- 2026-09-27 新增：头像与封面（用户报"作者头像一直没加载出来"） ----
    //
    // ⚠️ 断言必须能证伪：老测试只断言 coverUrl.startsWith("http")——而 bug 版本拼出来的
    //    https://www.douyin.com/aweme/v1/play/?video_id=<封面uri> 同样以 http 开头，
    //    所以那条断言在 bug 存在时**照样通过**，等于没断言（项目文档里记过这个坑）。
    //    这里改用"宿主必须是图床、且绝不能是播放端点"来判定。

    /** 封面：必须是图床直链，不能是播放端点（urlFromAddr 误用于图片的回归） */
    @Test
    public void parseFeed_coverIsImageUrl_notPlayEndpoint() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"11\",\"desc\":\"d\","
                + "\"video\":{\"cover\":{\"uri\":\"image-cut-tos-priv/abc\",\"url_list\":["
                + "\"https://p9-pc-sign.douyinpic.com/image-cut-tos-priv/abc~tplv.jpeg?x=1\"]},"
                + "\"play_addr\":{\"uri\":\"v0300\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v0300\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        String cover = list.get(0).coverUrl;
        assertTrue("封面应取图床直链，实际: " + cover, cover.contains("douyinpic.com"));
        assertFalse("封面绝不能是播放端点（拿播放端点当图床 = 永远黑屏）: " + cover,
                cover.contains("/aweme/v1/play/"));
        // 播放地址不受影响，仍走播放端点
        assertTrue("playUrl 应保持跳转式播放地址", list.get(0).playUrl.contains("/aweme/v1/play/"));
    }

    /** 封面 url_list 为空时返回空串，而不是拿 uri 去拼一个假图片地址 */
    @Test
    public void parseFeed_emptyCoverListYieldsEmpty_notFabricated() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"12\",\"desc\":\"d\","
                + "\"video\":{\"cover\":{\"uri\":\"image-cut-tos-priv/abc\",\"url_list\":[]},"
                + "\"play_addr\":{\"uri\":\"v0300\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v0300\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals("封面列表为空就该是空串", "", list.get(0).coverUrl);
    }

    /** 头像：author.avatar_thumb → FeedVideo.authorAvatar */
    @Test
    public void parseFeed_readsAuthorAvatar() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"13\",\"desc\":\"d\","
                + "\"author\":{\"nickname\":\"n\",\"sec_uid\":\"S\","
                + "\"avatar_thumb\":{\"uri\":\"100x100/x\",\"url_list\":["
                + "\"https://p3-pc.douyinpic.com/aweme/100x100/aweme-avatar/x.jpeg?from=1\"]}},"
                + "\"video\":{\"play_addr\":{\"uri\":\"v\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        String av = list.get(0).authorAvatar;
        assertTrue("头像应取 douyinpic 直链，实际: " + av, av.contains("douyinpic.com"));
        assertTrue("头像不该被拼成播放端点: " + av, !av.contains("/aweme/v1/play/"));
    }

    /** 没有头像字段时是空串（渲染层据此回退占位图），不能抛异常 */
    @Test
    public void parseFeed_missingAvatarYieldsEmpty() {
        String json = "{\"aweme_list\":[{\"aweme_id\":\"14\",\"desc\":\"d\","
                + "\"author\":{\"nickname\":\"n\"},"
                + "\"video\":{\"play_addr\":{\"uri\":\"v\",\"url_list\":["
                + "\"https://www.douyin.com/aweme/v1/play/?video_id=v\"]}}}]}";
        List<FeedVideo> list = DouyinApi.parseFeed(json);
        assertEquals(1, list.size());
        assertEquals("", list.get(0).authorAvatar);
    }

    /** imageUrl 的安全边界：url_list 里混 JsonNull / 非 http 项时要跳过，不能抛 */
    @Test
    public void imageUrl_skipsNullAndNonHttpEntries() {
        com.google.gson.JsonObject holder = com.google.gson.JsonParser.parseString(
                "{\"cover\":{\"url_list\":[null,\"\",\"ftp://x\","
                        + "\"https://p1.douyinpic.com/ok.jpeg\"]}}").getAsJsonObject();
        assertEquals("https://p1.douyinpic.com/ok.jpeg", DouyinApi.imageUrl(holder, "cover"));
        // 字段不存在 / 为 null / 空列表 → 一律空串
        assertEquals("", DouyinApi.imageUrl(holder, "nope"));
        assertEquals("", DouyinApi.imageUrl(
                com.google.gson.JsonParser.parseString("{\"cover\":null}").getAsJsonObject(), "cover"));
        assertEquals("", DouyinApi.imageUrl(
                com.google.gson.JsonParser.parseString("{\"cover\":{\"url_list\":[]}}").getAsJsonObject(), "cover"));
    }
}
