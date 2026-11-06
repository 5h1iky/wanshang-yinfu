package com.dywatch.app.chat.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Pb 编解码回归：对齐 tools/selftest-pb.js 的核心用例。 */
public class PbTest {

    @Test
    public void varintRoundTrip() {
        byte[] enc = Pb.writer().varint(1, 2043).varint(2, 10001).toByteArray();
        List<Pb.Field> f = Pb.decode(enc);
        assertEquals(2, f.size());
        assertEquals(2043, Pb.first(f, 1).asLong());
        assertEquals(10001, Pb.first(f, 2).asLong());
    }

    @Test
    public void largeVarintRoundTrip() {
        // 会话 shortId 是 19 位整数，超过 int 范围——必须用 long
        long big = 7689419292843983419L;
        byte[] enc = Pb.writer().varint(2, big).toByteArray();
        assertEquals(big, Pb.first(Pb.decode(enc), 2).asLong());
    }

    @Test
    public void stringAndNested() {
        byte[] inner = Pb.writer().string(1, "app_name").string(2, "douyin_pc").toByteArray();
        byte[] enc = Pb.writer().varint(18, 1).message(15, inner).toByteArray();
        List<Pb.Field> f = Pb.decode(enc);
        assertEquals(1, Pb.first(f, 18).asLong());
        List<Pb.Field> kv = Pb.nested(f, 15);
        assertEquals("app_name", Pb.first(kv, 1).asString());
        assertEquals("douyin_pc", Pb.first(kv, 2).asString());
    }

    @Test
    public void malformedThrows() {
        // 截断的 varint 必须抛，而不是静默返回错值（真机上这会表现为"服务端说 token 无效"）
        byte[] bad = new byte[]{(byte) 0x80};
        boolean threw = false;
        try {
            Pb.decode(bad);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("截断 varint 应抛异常", threw);
    }
}
