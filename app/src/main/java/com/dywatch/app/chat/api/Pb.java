package com.dywatch.app.chat.api;

// 最小 protobuf wire 编解码器（纯 Java，无第三方依赖）。
//
// 为什么自己写而不引 protobuf-java：
//   1. 我们只需要 wire 层（读字段/写字段），不需要 .proto 编译、不需要反射消息类；
//   2. 引 protobuf-java 约 +1MB 体积，手表 APK 体积敏感（32 位基线 6.3MB）；
//   3. IM 的字段结构是"打补丁"用法（读几个字段、拼一个请求体），全量消息类反而累赘。
//
// 端口自 tools/pb.js（项目内已验证，selftest-pb 10/10 PASS）。
// ⚠️ 落地纪律：只做本文件需要的 wire 操作，别扩成通用库。
//
// wire type：0=varint 1=64bit 2=len-delimited 5=32bit
// ⚠️ 字段边界一旦错位，服务端会回"token 无效"这类误导性错误——见 调研\19 §2。

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public final class Pb {

    private Pb() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final int W_VARINT = 0;
    public static final int W_64BIT = 1;
    public static final int W_LEN = 2;
    public static final int W_32BIT = 5;

    /** 一个已解码字段。wire==W_LEN 时 value 是 byte[]；W_VARINT 时是 Long。 */
    public static final class Field {
        public final int number;
        public final int wire;
        public final Object value;

        Field(int number, int wire, Object value) {
            this.number = number;
            this.wire = wire;
            this.value = value;
        }

        public long asLong() {
            return value instanceof Long ? (Long) value : 0L;
        }

        public byte[] asBytes() {
            return value instanceof byte[] ? (byte[]) value : new byte[0];
        }

        public String asString() {
            return new String(asBytes(), UTF8);
        }
    }

    /** 解析一层字段（不递归）。字段边界错位时抛 IllegalArgumentException。 */
    public static List<Field> decode(byte[] buf) {
        List<Field> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < buf.length) {
            long key = readVarint(buf, pos);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (number == 0) throw new IllegalArgumentException("field 0 @ " + pos[0]);
            switch (wire) {
                case W_VARINT:
                    out.add(new Field(number, wire, readVarint(buf, pos)));
                    break;
                case W_64BIT:
                    out.add(new Field(number, wire, slice(buf, pos, 8)));
                    break;
                case W_LEN: {
                    long len = readVarint(buf, pos);
                    if (len < 0 || len > Integer.MAX_VALUE) throw new IllegalArgumentException("bad len");
                    out.add(new Field(number, wire, slice(buf, pos, (int) len)));
                    break;
                }
                case W_32BIT:
                    out.add(new Field(number, wire, slice(buf, pos, 4)));
                    break;
                default:
                    throw new IllegalArgumentException("wire " + wire + " @ " + pos[0]);
            }
        }
        return out;
    }

    /** 取第一个指定字段；没有返回 null */
    public static Field first(List<Field> fields, int number) {
        for (Field f : fields) if (f.number == number) return f;
        return null;
    }

    /** 取第一个指定字段的嵌套消息；没有返回空列表 */
    public static List<Field> nested(List<Field> fields, int number) {
        Field f = first(fields, number);
        return f == null || f.wire != W_LEN ? new ArrayList<Field>() : decode(f.asBytes());
    }

    /** 取所有指定字段 */
    public static List<Field> all(List<Field> fields, int number) {
        List<Field> out = new ArrayList<>();
        for (Field f : fields) if (f.number == number) out.add(f);
        return out;
    }

    // ---------- 写 ----------

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        public Writer varint(int number, long value) {
            writeKey(out, number, W_VARINT);
            writeVarint(out, value);
            return this;
        }

        public Writer bytes(int number, byte[] value) {
            writeKey(out, number, W_LEN);
            writeVarint(out, value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int number, String value) {
            return bytes(number, value == null ? new byte[0] : value.getBytes(UTF8));
        }

        /** 嵌套消息：调用方先 encode 好再塞进来 */
        public Writer message(int number, byte[] encoded) {
            return bytes(number, encoded);
        }

        public Writer message(int number, Writer nested) {
            return bytes(number, nested.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    public static Writer writer() {
        return new Writer();
    }

    // ---------- 底层 ----------

    private static byte[] slice(byte[] buf, int[] pos, int len) {
        if (pos[0] + len > buf.length) {
            throw new IllegalArgumentException("len EOF @ " + pos[0] + " want " + len + " have " + (buf.length - pos[0]));
        }
        byte[] out = new byte[len];
        System.arraycopy(buf, pos[0], out, 0, len);
        pos[0] += len;
        return out;
    }

    static long readVarint(byte[] buf, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= buf.length) throw new IllegalArgumentException("varint EOF");
            int b = buf[pos[0]++] & 0xff;
            result |= ((long) (b & 0x7f)) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("varint too long");
        }
        return result;
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7f));
    }

    private static void writeKey(ByteArrayOutputStream out, int number, int wire) {
        writeVarint(out, (((long) number) << 3) | wire);
    }
}
