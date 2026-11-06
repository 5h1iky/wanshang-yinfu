package com.dywatch.app.chat.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 会话里的"对方身份"必须解析出来（昵称查询靠它）。
 *
 * 2026-11-07 真机发现：原生通道会话列表显示的是 uid 而不是昵称。
 * 根因是参与者列表（ParticipantsPage）没解析对，`peerSecUid` 为空 →
 * 昵称批量查询被整段跳过。这条测试把 `peerUid` / `peerSecUid` 钉住。
 */
public class ConvPeerTest {

    private static byte[] fixture() throws Exception {
        try (InputStream in = ConvPeerTest.class.getResourceAsStream("/im/init_conv_list.bin")) {
            if (in == null) throw new IllegalStateException("缺 fixture");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void everyConversationHasPeerIdentity() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        assertFalse(r.convs.isEmpty());
        for (ImModels.Conv c : r.convs) {
            assertTrue("会话 " + c.id + " 缺 peerUid", c.peerUid != 0);
            assertFalse("会话 " + c.id + " 缺 peerSecUid（昵称查询会整段跳过）",
                    c.peerSecUid.isEmpty());
            assertTrue("peerSecUid 应形如 MS4wLjAB", c.peerSecUid.startsWith("MS4wLjAB"));
        }
    }

    @Test
    public void selfUidExcludedFromPeer() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        assertTrue("selfUid 应解析出来", r.selfUid != 0);
        for (ImModels.Conv c : r.convs) {
            assertTrue("peer 不应等于自己", c.peerUid != r.selfUid);
            assertTrue("会话 id 里应含自己", c.id.contains(String.valueOf(r.selfUid)));
        }
    }

    @Test
    public void recentMessagesSortedByIndex() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        for (ImModels.Conv c : r.convs) {
            long prev = -1;
            for (ImModels.Msg m : c.recent) {
                assertTrue("消息必须按 index 升序（旧→新）", m.indexInConversation >= prev);
                prev = m.indexInConversation;
            }
        }
    }

    @Test
    public void messageDirectionFromProtocol() throws Exception {
        // 方向必须在解析阶段定好（sender == selfUid → OUT），而不是靠 UI 猜。
        // 2026-11-07 真机"消息全显示成我"就是这里缺席。
        ImApi.Init r = ImApi.parseInit(fixture());
        int out = 0, in = 0;
        for (ImModels.Conv c : r.convs) {
            for (ImModels.Msg m : c.recent) {
                if (m.senderUid == 0) continue;
                if (m.direction == ImModels.Msg.OUT) {
                    out++;
                    assertEquals("OUT 必须是自己发的", r.selfUid, m.senderUid);
                } else {
                    in++;
                    assertTrue("IN 必须不是自己发的", m.senderUid != r.selfUid);
                }
            }
        }
        // 真机样本里两个方向都有（自己发的 + 5h1iky 回的）
        assertTrue("样本里应有自己发的消息（实际 " + out + "）", out > 0);
        assertTrue("样本里应有对方的消息（实际 " + in + "）", in > 0);
    }

    @Test
    public void displayNameFallsBackToUid() {
        ImModels.Conv c = new ImModels.Conv();
        c.id = "0:1:1:2";
        c.peerUid = 2;
        assertEquals("2", c.displayName());
        c.nickname = "某人";
        assertEquals("某人", c.displayName());
    }

    @Test
    public void minIndexForHistoryPaging() throws Exception {
        ImApi.Init r = ImApi.parseInit(fixture());
        ImModels.Conv c = r.convs.get(0);
        long min = c.minIndex();
        assertTrue("minIndex 应是 1e15 量级", min > 1_000_000_000_000_000L);
        for (ImModels.Msg m : c.recent) {
            assertTrue("minIndex 必须 <= 每条消息的 index", min <= m.indexInConversation);
        }
    }

    @Test
    public void systemTipsBecomeReadableText() {
        // 系统提示消息（{"tips":"..."}）不能原样塞进气泡，要取 tips
        String json = "{\"tips\":\"对方回复或关注你之前，只能发送一条文字消息。\",\"template\":[]}";
        assertEquals("对方回复或关注你之前，只能发送一条文字消息。", ImApi.jsonField(json, "tips"));
    }

    @Test
    public void historyResponseParsed() {
        // 2041 的响应形状：f4="OK", f6.f2041.f1×N MessageInfo{ f2 = MessageBody }
        byte[] body = Pb.writer()
                .varint(1, 2041)
                .bytes(2041, Pb.writer()
                        .bytes(1, Pb.writer()          // MessageInfo
                                .varint(1, 0)
                                .bytes(2, Pb.writer()  // MessageBody
                                        .string(1, "0:1:1:2")
                                        .varint(4, 1672502400010000L)
                                        .varint(5, 7689419292843983419L)
                                        .varint(6, 7)
                                        .varint(7, 2)
                                        .string(8, "{\"text\":\"历史消息\"}")
                                        .varint(10, 1790332455891L)
                                        .toByteArray())
                                .toByteArray())
                        .toByteArray())
                .toByteArray();
        byte[] resp = Pb.writer()
                .varint(1, 2041)
                .varint(3, 0)
                .string(4, "OK")
                .varint(13, 1)
                .bytes(6, body)
                .toByteArray();
        List<ImModels.Msg> msgs = ImApi.parseHistory(resp);
        assertEquals(1, msgs.size());
        assertEquals("历史消息", msgs.get(0).text);
        assertEquals(ImModels.Msg.IN, msgs.get(0).direction);   // sender=2 != self=1 → 对方
        assertEquals(1672502400010000L, msgs.get(0).indexInConversation);
        assertEquals(1790332455891L, msgs.get(0).timeMs);
    }
}
