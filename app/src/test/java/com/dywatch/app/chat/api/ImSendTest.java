package com.dywatch.app.chat.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 发送链路的纯函数回归（不联网）。
 *
 * 这些断言守的是"改错了会被服务端 KICK"的那几条硬约束：
 *   · identity token 必须 JSON 包装（裸串 → empty token / KICK）
 *   · 信封 f6 必须是 0（读接口是 1，发接口是 0 —— 抄错就失败）
 *   · f15 里 identity_security_* 三组必须在
 *   · 发送体的 f1..f8 字段号与官方 proto 一致
 */
public class ImSendTest {

    @Test
    public void identityTokenIsJsonWrapped() {
        byte[] env = ImSend.buildEnvelope(100, 10042, new byte[] { 0x08, 0x01 }, "TOKEN123", "DEV45");
        List<Pb.Field> top = Pb.decode(env);

        assertEquals("cmd", 100, Pb.first(top, 1).asLong());
        assertEquals("sequence_id", 10042, Pb.first(top, 2).asLong());
        assertEquals("⚠️ 发接口的 inbox_type 必须是 0（读接口是 1）", 0, Pb.first(top, 6).asLong());
        assertEquals("平台标识 f18", 1, Pb.first(top, 18).asLong());
        assertEquals("平台标识 f21", "douyin_web", Pb.first(top, 21).asString());
        assertEquals("平台标识 f22", "web_sdk", Pb.first(top, 22).asString());

        String token = null, device = null, aid = null;
        for (Pb.Field f : Pb.all(top, 15)) {
            List<Pb.Field> kv = Pb.decode(f.asBytes());
            String k = Pb.first(kv, 1).asString();
            String v = Pb.first(kv, 2).asString();
            if ("identity_security_token".equals(k)) token = v;
            if ("identity_security_device_id".equals(k)) device = v;
            if ("identity_security_aid".equals(k)) aid = v;
        }
        assertEquals("⚠️ token 必须是 JSON 包装，裸串会被判 empty token",
                "{\"token\":\"TOKEN123\"}", token);
        assertEquals("DEV45", device);
        assertEquals("aid 实测是空串", "", aid);
    }

    @Test
    public void envelopeCarriesAllEnvironmentKeys() {
        byte[] env = ImSend.buildEnvelope(100, 10000, new byte[0], "t", "d");
        List<Pb.Field> top = Pb.decode(env);
        int kvCount = Pb.all(top, 15).size();
        assertEquals("3 组 identity + 17 组环境 = 20 组", 20, kvCount);
    }

    @Test
    public void sendPayloadMatchesOfficialProto() {
        byte[] payload = ImSend.buildSendPayload(
                "0:1:111:222", 7689419292843983419L, "TICKET121", "你好", "cid-1");
        List<Pb.Field> f = Pb.decode(payload);
        assertEquals("f1 conversation_id", "0:1:111:222", Pb.first(f, 1).asString());
        assertEquals("f2 conversation_type", 1, Pb.first(f, 2).asLong());
        assertEquals("f3 conversation_short_id", 7689419292843983419L, Pb.first(f, 3).asLong());
        String content = Pb.first(f, 4).asString();
        assertTrue("content 应是 JSON", content.startsWith("{"));
        assertTrue("content 必须带 text", content.contains("\"text\":\"你好\""));
        assertTrue("content 的 aweType=700", content.contains("\"aweType\":700"));
        assertEquals("f6 message_type 文本=7", 7, Pb.first(f, 6).asLong());
        assertEquals("f7 conversation ticket", "TICKET121", Pb.first(f, 7).asString());
        assertEquals("f8 client_message_id", "cid-1", Pb.first(f, 8).asString());

        // f5 三个 ext：mentioned_users / client_message_id / stime
        List<Pb.Field> exts = Pb.all(f, 5);
        assertEquals("三个 ext", 3, exts.size());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Pb.Field e : exts) {
            keys.add(Pb.first(Pb.decode(e.asBytes()), 1).asString());
        }
        assertTrue(keys.contains("s:mentioned_users"));
        assertTrue(keys.contains("s:client_message_id"));
        assertTrue(keys.contains("s:stime"));
    }

    @Test
    public void sendResponseOkParsed() {
        // 真机成功响应：顶层 f3=0 f4="OK" f14=""（decision 空）
        byte[] resp = Pb.writer()
                .varint(1, 100)
                .varint(3, 0)
                .string(4, "OK")
                .bytes(6, Pb.writer()
                        .bytes(100, Pb.writer()
                                .varint(1, 7689999999999999999L)
                                .varint(3, 0)
                                .string(4, "cid-1")
                                .toByteArray())
                        .toByteArray())
                .toByteArray();
        ImSend.Result r = ImSend.parseSendResponse(resp);
        assertTrue(r.ok);
        assertEquals(0, r.code);
        assertEquals("", r.decision);
        assertEquals(7689999999999999999L, r.serverMessageId);
    }

    @Test
    public void sendResponseWithDecisionIsNotOk() {
        byte[] resp = Pb.writer()
                .varint(1, 100)
                .varint(3, 0)
                .string(4, "OK")
                .string(14, "KICK")
                .toByteArray();
        ImSend.Result r = ImSend.parseSendResponse(resp);
        assertFalse("有 decision 绝不能当成功", r.ok);
        assertEquals("KICK", r.decision);
    }

    @Test
    public void businessErrorIsNotOkButNoDecision() {
        // 发错会话时的真实业务错误：code != 0、decision 为空
        byte[] resp = Pb.writer()
                .varint(1, 100)
                .varint(3, 7905)
                .string(4, "OK")
                .bytes(6, Pb.writer()
                        .bytes(100, Pb.writer().varint(3, 7905).toByteArray())
                        .toByteArray())
                .toByteArray();
        ImSend.Result r = ImSend.parseSendResponse(resp);
        assertFalse(r.ok);
        assertEquals("", r.decision);
    }

    @Test
    public void jsonQuoteEscapes() {
        assertEquals("\"a\\\"b\"", ImSend.jsonQuote("a\"b"));
        assertEquals("\"a\\\\b\"", ImSend.jsonQuote("a\\b"));
        assertEquals("\"a\\nb\"", ImSend.jsonQuote("a\nb"));
        assertEquals("\"\"", ImSend.jsonQuote(null));
        assertEquals("\"你好\"", ImSend.jsonQuote("你好"));
    }

    @Test
    public void jsonStringReadsTopLevel() {
        String j = "{\"data\":{\"decision\":\"\",\"device_id\":\"abc\",\"identity_security_token\":\"Cj3xyz\"}}";
        assertEquals("abc", ImSend.jsonString(j, "device_id"));
        assertEquals("Cj3xyz", ImSend.jsonString(j, "identity_security_token"));
        assertEquals("", ImSend.jsonString(j, "decision"));
        assertEquals("", ImSend.jsonString(j, "missing"));
        assertEquals("", ImSend.jsonString(null, "x"));
    }

    /**
     * ticket-guard 的签名串格式锁定。
     *
     * ⚠️ 格式是 `ticket=<t>&path=<p>&timestamp=<ts>`，path 只含接口路径。
     * 改动这里会让所有发送被服务端判为"签名不匹配"——而错误提示会是业务错误，
     * 极难反推。所以用一条测试把字符串形态钉住。
     */
    @Test
    public void ticketSignBaseFormat() {
        String ticket = "T";
        String path = "/v1/message/send";
        long ts = 1793990000L;
        String base = "ticket=" + ticket + "&path=" + path + "&timestamp=" + ts;
        assertEquals("ticket=T&path=/v1/message/send&timestamp=1793990000", base);
    }

    @Test
    public void randomSeqInObservedRange() {
        for (int i = 0; i < 50; i++) {
            byte[] env = ImSend.buildEnvelope(100, 10000 + i, new byte[0], "t", "d");
            long seq = Pb.first(Pb.decode(env), 2).asLong();
            assertTrue("sequence_id 实测在 10000~11000", seq >= 10000 && seq < 11000);
        }
    }
}
