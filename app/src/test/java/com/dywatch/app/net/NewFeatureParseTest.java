package com.dywatch.app.net;

import com.dywatch.app.chat.model.Comment;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 新功能的离线协议边界；样本不含真实账号或私人内容。 */
public class NewFeatureParseTest {
    private JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test public void following_acceptsActualFieldAndSkipsIncompleteUsers() {
        List<DouyinApi.UserSummary> list = DouyinApi.parseFollowing(json(
                "{\"followings\":[{\"uid\":\"1\",\"sec_uid\":\"sec\",\"nickname\":\"n\","
                        + "\"follower_count\":\"\",\"aweme_count\":null,"
                        + "\"signature\":\"s\",\"avatar_thumb\":{\"url_list\":[\"https://p.example/a.jpg\"]}},"
                        + "{\"uid\":\"missing-sec\"}]}"));
        assertEquals(1, list.size());
        assertEquals("sec", list.get(0).secUid);
        assertEquals("n", list.get(0).nickname);
        assertEquals("https://p.example/a.jpg", list.get(0).avatarUrl);
    }

    @Test public void comment_preservesReplyIdentityAndTarget() {
        Comment c = DouyinApi.parseComment(json("{\"cid\":\"c1\",\"text\":\"text\","
                + "\"reply_comment_total\":3,\"reply_to_reply_id\":\"c0\","
                + "\"user\":{\"sec_uid\":\"u\",\"nickname\":\"n\"}}"));
        assertEquals("c1", c.cid);
        assertEquals(3, c.replyCount);
        assertEquals("c0", c.replyToCid);
        assertEquals("u", c.secUid);
    }

    @Test public void comment_readsEmbeddedReplyPreview() {
        Comment c = DouyinApi.parseComment(json("{\"cid\":\"root\",\"reply_comment_total\":2,"
                + "\"reply_comment\":[{\"cid\":\"child\",\"text\":\"preview\","
                + "\"user\":{\"nickname\":\"n\"}}]}"));
        assertEquals(1, c.previewReplies.size());
        assertEquals("child", c.previewReplies.get(0).cid);
        assertEquals("preview", c.previewReplies.get(0).text);
    }

    @Test public void liveRooms_handlesSearchAndPartitionShapes() {
        List<DouyinApi.LiveRoom> search = DouyinApi.parseLiveRooms(json(
                "{\"data\":[{\"lives\":[{\"web_rid\":\"r\",\"title\":\"t\","
                        + "\"author\":{\"nickname\":\"n\"}}]}]}"));
        assertEquals(1, search.size());
        assertEquals("r", search.get(0).webRid);
        List<DouyinApi.LiveRoom> partition = DouyinApi.parseLiveRooms(json(
                "{\"data\":{\"data\":[{\"web_rid\":\"r2\",\"room\":{\"title\":\"t2\","
                        + "\"owner\":{\"nickname\":\"n2\"}}}]}}"));
        assertEquals(1, partition.size());
        assertEquals("r2", partition.get(0).webRid);
        assertEquals("n2", partition.get(0).ownerName);

        // 手机实测：搜索结果 data[].lives 是对象，web_rid 位于字符串化 rawdata。
        List<DouyinApi.LiveRoom> wrapped = DouyinApi.parseLiveRooms(json(
                "{\"data\":[{\"lives\":{\"rawdata\":"
                        + "\"{\\\"room\\\":{\\\"web_rid\\\":\\\"r3\\\",\\\"title\\\":\\\"t3\\\"}}\","
                        + "\"author\":{\"nickname\":\"n3\"}}}]}"));
        assertEquals(1, wrapped.size());
        assertEquals("r3", wrapped.get(0).webRid);
        assertEquals("n3", wrapped.get(0).ownerName);
    }

    @Test public void liveStreams_preferActualMapsAndSupportNestedJson() {
        List<DouyinApi.LiveStream> streams = DouyinApi.parseLiveStreams(json(
                "{\"hls_pull_url_map\":{\"SD1\":\"https://cdn.example/low.m3u8\"},"
                        + "\"flv_pull_url\":{\"HD1\":\"https://cdn.example/high.flv\"},"
                        + "\"live_core_sdk_data\":{\"pull_data\":{\"stream_data\":"
                        + "\"{\\\"data\\\":[{\\\"url\\\":\\\"https://cdn.example/nested.m3u8\\\",\\\"v_codec\\\":\\\"h264\\\"}]}\"}}}"));
        assertEquals(3, streams.size());
        assertTrue(streams.get(0).hls);
        assertFalse(streams.get(1).hls);
        assertEquals("h264", streams.get(2).codec);
    }

    @Test public void notice_usesRealGeneralNoticeAndCommentAwemeShape() {
        // 结构与手机实测一致（仅保留键名，不含用户数据）。
        String json = "{\"notice_list_v2\":["
                + "{\"nid_str\":\"a\",\"create_time\":0,\"general_notice\":{\"sender_name\":\"n\",\"content\":\"c\"},"
                + "\"aweme_id\":\"1\"},"
                + "{\"nid_str\":\"b\",\"comment\":{\"comment\":{\"text\":\"reply\"},"
                + "\"aweme\":{\"aweme_id\":\"2\"}}}]}";
        List<DouyinApi.NoticeItem> list = DouyinApi.parseNoticesForTest(json(json), 700);
        assertEquals(2, list.size());
        assertEquals("n", list.get(0).nickname);
        assertEquals("c", list.get(0).text);
        assertEquals("1", list.get(0).awemeId);
        assertEquals("2", list.get(1).awemeId);
    }

    @Test public void liveStreams_rewritesCleartextToHttpsAndRejectsOtherSchemes() {
        assertTrue(DouyinApi.parseLiveStreams(null).isEmpty());
        List<DouyinApi.LiveStream> streams = DouyinApi.parseLiveStreams(json(
                "{\"hls_pull_url_map\":{\"SD1\":\"http://cdn.example/a.m3u8\"}}"));
        assertEquals(1, streams.size());
        assertTrue(streams.get(0).url.startsWith("https://"));
        assertTrue(DouyinApi.parseLiveStreams(json(
                "{\"hls_pull_url_map\":{\"SD1\":\"rtmp://unsafe.example/a\"}}")).isEmpty());
    }
}
