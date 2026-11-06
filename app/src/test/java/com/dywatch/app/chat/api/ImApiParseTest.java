package com.dywatch.app.chat.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 用真机抓到的**脱敏**响应做回归（fixture: resources/im/init_conv_list.bin）。
 *
 * 数据来源：2026-11-07 真机 init 请求的响应（29,538B），
 * 已做等长脱敏（uid → 1000000000000001 / 2000000000000000002），结构原封不动。
 *
 * 这份 fixture 是"协议基线"的守门人：
 *   · 抖音改字段 → 断言红，立刻知道要跟进；
 *   · 解析逻辑改错 → 断言红。
 */
public class ImApiParseTest {

    private static byte[] fixture() throws Exception {
        try (InputStream in = ImApiParseTest.class.getResourceAsStream("/im/init_conv_list.bin")) {
            if (in == null) throw new IllegalStateException("缺 fixture: /im/init_conv_list.bin");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void parsesSevenConversations() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        assertEquals("真机样本是 7 个会话", 7, r.convs.size());
    }

    @Test
    public void conversationFieldsParsed() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        ImModels.Conv first = r.convs.get(0);
        assertTrue("conversation_id 应形如 0:1:x:y", first.id.startsWith("0:1:"));
        assertEquals("ticket 实测 121 字符", 121, first.ticket.length());
        assertTrue("shortId 应是 19 位大整数", first.shortId > 1_000_000_000_000_000L);
        assertFalse("最近消息不应为空", first.recent.isEmpty());
        assertEquals("首个会话实测 14 条消息", 14, first.recent.size());
    }

    @Test
    public void messageDirectionResolved() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        ImModels.Conv first = r.convs.get(0);
        ImModels.Msg last = first.recent.get(first.recent.size() - 1);
        assertEquals("文本消息 type=7", 7, last.type);
        assertEquals("最后一条实测是「收到」", "收到", last.text);
        assertTrue("时间戳应是毫秒级", last.timeMs > 1_600_000_000_000L);
    }

    @Test
    public void cursorBackfilled() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        assertNotNull(r.next);
        assertFalse("游标不应为空", r.next.isEmpty());
        assertTrue("version 应是 16 位大数", r.next.version > 1_000_000_000_000_000L);
    }

    @Test
    public void errorStatusIsReportedNotSilentlyEmpty() {
        // 服务端非 OK（实测见过 "empty token" / "too many requests"）必须报告，
        // 绝不能当成"空会话列表"——那会让 UI 显示"没有会话"这种假事实。
        byte[] fake = Pb.writer()
                .varint(1, 2043)
                .string(4, "empty token")
                .toByteArray();
        ImApi.Init r = ImApi.parseInit(fake);
        assertFalse("非 OK 状态必须 !ok()", r.ok());
        assertEquals("empty token", r.error);
        assertTrue("且不应带出会话", r.convs.isEmpty());
    }

    @Test
    public void extractTextHandlesEscapes() {
        assertEquals("收到", ImApi.extractText("{\"aweType\":700,\"text\":\"收到\"}"));
        assertEquals("a\"b", ImApi.extractText("{\"text\":\"a\\\"b\"}"));
        assertEquals("换行", ImApi.extractText("{\"text\":\"换行\"}"));
        assertEquals("", ImApi.extractText("{}"));
    }

    /**
     * 字段语义锁定（对齐官方 proto 定义）。
     *
     * ⚠️ 这条测试是为 2026-11-07 的一次真实错误立的碑：
     * 我最初手工解析把 MessageBody.f4 当时间戳、f13 当序号，两个都错位。
     * 官方 proto 一直在 tools/douyin-spider-ref/dy_live/protobuf/proto/default/im_proto.proto，
     * 正确映射是：
     *   f4  = index_in_conversation（序号，值很大但不是时间）
     *   f10 = create_time（毫秒时间戳，1.6e12 量级）
     *   f13 = order_in_conversation
     */
    @Test
    public void messageFieldSemanticsMatchOfficialProto() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        for (ImModels.Conv c : r.convs) {
            for (ImModels.Msg m : c.recent) {
                if (m.type != 7) continue;
                // create_time（f10）必须是 2020 年之后的毫秒时间戳
                assertTrue("create_time 应是毫秒时间戳，实际=" + m.timeMs,
                        m.timeMs > 1_600_000_000_000L && m.timeMs < 2_000_000_000_000L);
                // index_in_conversation（f4）是 1e15 量级的大序号
                assertTrue("index_in_conversation 应是超大序号，实际=" + m.indexInConversation,
                        m.indexInConversation > 1_000_000_000_000_000L);
            }
        }
    }
}
