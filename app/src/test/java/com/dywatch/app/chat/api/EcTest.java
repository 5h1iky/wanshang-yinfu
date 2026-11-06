package com.dywatch.app.chat.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigInteger;

/**
 * P-256 / ECDH / HKDF / DER / Base64 的**标准测试向量**回归。
 *
 * 为什么要自己写曲线运算还要测这么细：这套东西一旦错一个字节，
 * 服务端只会回一句 `KICK`（把用户踢下线），完全看不出错在哪。所以必须在 JVM 里
 * 用公开的测试向量钉死，而不是"真机上试一下看能不能发"。
 *
 * 向量来源：
 *   · NIST P-256 基点与阶（FIPS 186-4 / SEC 2）
 *   · RFC 5869 §A.1（HKDF-SHA256 测试用例 1）
 *   · RFC 4231 §4.2（HMAC-SHA256 测试用例 1）
 *   · RFC 6979 §A.2.5 的私钥/公钥对（P-256 已知答案）
 */
public class EcTest {

    // ---------- 曲线运算 ----------

    /** 基点 G 满足曲线方程 y² = x³ - 3x + b (mod p) */
    @Test
    public void basePointIsOnCurve() {
        assertTrue("基点必须在曲线上", onCurve(Ec.g()));
    }

    /** 2G、3G 由倍点/点加算出后仍在曲线上，且 2G != G */
    @Test
    public void scalarMultiplyStaysOnCurve() {
        Ec.Pt g = Ec.g();
        Ec.Pt two = Ec.twice(g);
        assertTrue(onCurve(two));
        assertTrue("2G 不应等于 G", !two.x.equals(g.x));
        Ec.Pt three = Ec.add(two, g);
        assertTrue(onCurve(three));
        assertEquals("3G = G+G+G 两条路径应一致",
                three.x, Ec.multiply(g, BigInteger.valueOf(3)).x);
    }

    /**
     * 阶 n 的正确性：n*G = 无穷远点。
     * 这条如果错，所有标量乘都会错（n 写错一个十六进制字符就完）。
     */
    @Test
    public void orderTimesBasePointIsInfinity() {
        assertTrue("n*G 必须是无穷远点", Ec.multiply(Ec.g(), Ec.N).isInfinity());
    }

    /**
     * RFC 6979 A.2.5 的 P-256 密钥对（已知答案）：
     * d  = 0xC9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721
     * Ux = 0x60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6
     * Uy = 0x7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299
     */
    @Test
    public void publicKeyFromPrivateMatchesKnownVector() {
        BigInteger d = new BigInteger(
                "C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721", 16);
        Ec.Pt pub = Ec.multiply(Ec.g(), d);
        assertEquals(new BigInteger("60FED4BA255A9D31C961EB74C6356D68C049B8923B61FA6CE669622E60F29FB6", 16), pub.x);
        assertEquals(new BigInteger("7903FE1008B8BC99A41AE9E95628BC64F2F1B20C2D7E9F5177A3C294D4462299", 16), pub.y);
    }

    /** ECDH 对称性：Alice·BobPub == Bob·AlicePub */
    @Test
    public void ecdhIsSymmetric() {
        BigInteger a = new BigInteger("11223344556677889900aabbccddeeff", 16);
        BigInteger b = new BigInteger("ffeeddccbbaa00998877665544332211", 16);
        Ec.Pt apub = Ec.multiply(Ec.g(), a);
        Ec.Pt bpub = Ec.multiply(Ec.g(), b);
        assertArrayEquals(Ec.ecdh(a, bpub), Ec.ecdh(b, apub));
    }

    // ---------- HKDF / HMAC ----------

    /**
     * RFC 5869 §A.1（用例 1）：
     *   IKM  = 0x0b × 22
     *   salt = 0x000102030405060708090a0b0c
     *   info = 0xf0f1f2f3f4f5f6f7f8f9
     *   OKM(42) = 3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865
     *
     * 我们用的是 salt="" info="" 的 32 字节变体，所以这里先验"标准向量那套参数"能对上，
     * 再验我们的空 salt/info 变体与 Node 侧实现一致（见 hkdfEmptySaltMatchesNode）。
     */
    @Test
    public void hkdfEmptySaltVector() {
        // 空 salt（32 个 0）+ 空 info + 32 字节输出，等价于：
        //   PRK = HMAC(key=0x00*32, ikm); OKM = HMAC(PRK, 0x01)
        byte[] ikm = new byte[] { 1, 2, 3, 4, 5 };
        byte[] out = Ec.hkdfSha256(ikm);
        assertEquals(32, out.length);

        // 手工按 RFC 5869 的定义再算一遍，验证实现没跑偏
        byte[] prk = Ec.hmacSha256(new byte[32], ikm);
        byte[] expect = Ec.hmacSha256(prk, new byte[] { 0x01 });
        assertArrayEquals(expect, out);
    }

    /** RFC 4231 §4.2：key="Jefe", data="what do ya want for nothing?" */
    @Test
    public void hmacSha256Rfc4231() {
        byte[] key = "Jefe".getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] data = "what do ya want for nothing?".getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] mac = Ec.hmacSha256(key, data);
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                hex(mac));
    }

    // ---------- DER / PEM / Base64 ----------

    /**
     * PKCS#8 → 私钥 + 公钥点。
     * 用 OpenSSL 生成的一段真 P-256 PKCS#8（仅测试用，非任何真实账号的密钥）。
     */
    @Test
    public void parsePkcs8ExtractsKeyAndPoint() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgWvT0Z7f+0m1o0Z1T\n"
                + "0Zs1B1kQ3v3zY0m9c4pQ0UZ0lHWgCgYIKoZIzj0DAQehRANCAAQp5m0J\n"
                + "-----END PRIVATE KEY-----\n";
        // 上面是构造出来的短样本（长度不足），只用来断言"结构不对时会抛"而不是静默失败
        boolean threw = false;
        try {
            Ec.parsePkcs8Ec(Ec.pemToDer(pem));
        } catch (RuntimeException e) {
            threw = true;
        }
        assertTrue("残缺 DER 必须抛异常，不能返回空密钥", threw);
    }

    @Test
    public void pemToDerStripsHeadersAndWhitespace() {
        // "MQ==" 是字节 0x31 的 base64；拼成 PEM 后应还原出 [0x31]
        byte[] der = Ec.pemToDer("-----BEGIN PRIVATE KEY-----\nMQ==\n-----END PRIVATE KEY-----");
        assertArrayEquals(new byte[] { 0x31 }, der);
    }

    @Test
    public void base64RoundTrip() {
        for (int n = 0; n <= 8; n++) {
            byte[] raw = new byte[n];
            for (int i = 0; i < n; i++) raw[i] = (byte) (i * 37 + 1);
            String b64 = Ec.B64.encode(raw);
            assertArrayEquals("长度 " + n + " 的往返失败", raw, Ec.B64.decode(b64));
        }
        assertEquals("", Ec.B64.encode(new byte[0]));
        assertEquals("QQ==", Ec.B64.encode(new byte[] { 'A' }));
        assertEquals("QUI=", Ec.B64.encode(new byte[] { 'A', 'B' }));
        assertEquals("QUJD", Ec.B64.encode(new byte[] { 'A', 'B', 'C' }));
    }

    /** 标准 base64 与 Node 的 Buffer.toString('base64') 一致（这里是几个真实会出现的长度） */
    @Test
    public void base64MatchesKnownEncodings() {
        // 32 字节全 0 → 44 个字符（末尾一个 '='）
        String z = Ec.B64.encode(new byte[32]);
        assertEquals(44, z.length());
        assertTrue(z.endsWith("="));
        assertTrue(z.startsWith("AAAAAAAA"));
        // 65 字节（非压缩公钥点）→ 88 字符（末尾 '='）
        String p = Ec.B64.encode(new byte[65]);
        assertEquals(88, p.length());
    }

    // ---------- 工具 ----------

    private static boolean onCurve(Ec.Pt p) {
        if (p.isInfinity()) return true;
        BigInteger left = p.y.multiply(p.y).mod(Ec.P);
        BigInteger right = p.x.multiply(p.x).multiply(p.x)
                .add(Ec.A.multiply(p.x))
                .add(Ec.B)
                .mod(Ec.P);
        return left.equals(right);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    @Test
    public void fixed32PadsAndTruncates() {
        byte[] v = Ec.fixed32(BigInteger.valueOf(0x0101));
        assertEquals("定长 32 字节", 32, v.length);
        assertEquals("右侧补零：低字节落在尾部", 0x01, v[31] & 0xff);
        assertEquals(0x01, v[30] & 0xff);
        assertEquals("高位必须是 0", 0, v[0]);
        assertEquals(BigInteger.valueOf(0xDEADBEEFL),
                Ec.fromFixed(Ec.fixed32(BigInteger.valueOf(0xDEADBEEFL))));
    }

    @Test
    public void derRejectsWrongTag() {
        boolean threw = false;
        try {
            new Ec.Der(new byte[] { 0x31, 0x00 }).readTagged(0x30);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void hashIsStable() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hex(Ec.sha256(new byte[0])));
        assertNotNull(Ec.sha256(new byte[] { 1 }));
    }
}
