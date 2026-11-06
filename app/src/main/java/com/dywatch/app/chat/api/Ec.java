package com.dywatch.app.chat.api;

// P-256 椭圆曲线 + ECDH + HKDF-SHA256 + HMAC-SHA256（纯 Java，无 Android 依赖）。
//
// 为什么自己实现曲线运算而不调 javax.crypto：
//   1. 项目铁律「32 位必须可用」，而 Android 上 EC 的 KeyFactory/KeyAgreement 由厂商 Conscrypt
//      提供，老机型（Android 5/6）行为不一致，出问题时是一团黑盒；
//   2. 我们要的只是 P-256 一点乘 + 一个 ECDH，用 BigInteger 写死曲线参数反而**更可验证**——
//      单测直接拿标准测试向量断言（见 EcTest）；
//   3. 纯 Java ⇒ 同一份代码在 JVM 单测和手表上跑出完全相同的结果，不需要"真机才知道对不对"。
//
// ⚠️ 这里的 ECDH **不做**任何侧信道防护（不追求常数时间）。它的用途是复刻网页 SDK 的
//    ticket-guard 握手，对端是抖音服务端而不是本地攻击者；私钥也只在本机内存里活几秒。
//    这一点必须写清楚，免得后人误以为这是一个通用密码学库。

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * P-256 ECDH 与 DA 相关的小工具。
 *
 * 端点与用途（协议细节见 调研\20 §2.3）：
 *   客户端私钥（IndexedDB `ec_privateKey`，PEM PKCS#8）
 *     + 服务端证书（localStorage `security-sdk/s_sdk_server_cert_key`，PEM X.509）
 *     → ECDH 共享秘密 → HKDF-SHA256(salt="", info="") → 32B HMAC key
 *     → HMAC-SHA256 算 `bd-ticket-guard-client-data`
 */
public final class Ec {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ---- NIST P-256（secp256r1 / prime256v1）曲线参数 ----
    static final BigInteger P = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16);
    static final BigInteger A = P.subtract(BigInteger.valueOf(3));   // a = -3 mod p
    static final BigInteger B = new BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16);
    static final BigInteger N = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
    private static final BigInteger GX = new BigInteger(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16);
    private static final BigInteger GY = new BigInteger(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16);

    /** 曲线点（仿射坐标）。Z=null 表示无穷远点。 */
    static final class Pt {
        final BigInteger x, y;

        Pt(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        boolean isInfinity() {
            return x == null || y == null;
        }
    }

    private Ec() {}

    // ---------- 曲线运算 ----------

    /** 点加：P + Q */
    static Pt add(Pt p, Pt q) {
        if (p.isInfinity()) return q;
        if (q.isInfinity()) return p;
        BigInteger dx = q.x.subtract(p.x).mod(P);
        if (dx.signum() == 0) {
            // 同 x：要么是同一点（→ 倍点），要么互为逆元（→ 无穷远）
            if (q.y.add(p.y).mod(P).signum() == 0) return new Pt(null, null);
            return twice(p);
        }
        BigInteger s = q.y.subtract(p.y).mod(P).multiply(dx.modInverse(P)).mod(P);
        BigInteger x3 = s.multiply(s).subtract(p.x).subtract(q.x).mod(P);
        BigInteger y3 = s.multiply(p.x.subtract(x3)).subtract(p.y).mod(P);
        return new Pt(x3, y3);
    }

    /** 倍点：2P */
    static Pt twice(Pt p) {
        if (p.isInfinity() || p.y.signum() == 0) return new Pt(null, null);
        BigInteger s = p.x.multiply(p.x).multiply(BigInteger.valueOf(3)).add(A).mod(P)
                .multiply(p.y.shiftLeft(1).modInverse(P)).mod(P);
        BigInteger x3 = s.multiply(s).subtract(p.x.shiftLeft(1)).mod(P);
        BigInteger y3 = s.multiply(p.x.subtract(x3)).subtract(p.y).mod(P);
        return new Pt(x3, y3);
    }

    /** 标量乘：k * P（double-and-add） */
    static Pt multiply(Pt p, BigInteger k) {
        BigInteger e = k.mod(N);
        Pt r = new Pt(null, null);
        Pt q = p;
        for (int i = 0; i < e.bitLength(); i++) {
            if (e.testBit(i)) r = add(r, q);
            q = twice(q);
        }
        return r;
    }

    /** 生成基点 */
    static Pt g() {
        return new Pt(GX, GY);
    }

    /** 32 字节定长大端（P-256 坐标/私钥的标准编码） */
    static byte[] fixed32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        if (b.length >= 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }

    static BigInteger fromFixed(byte[] b) {
        return new BigInteger(1, b);
    }

    // ---------- 解析 ----------

    /**
     * 从 PKCS#8 的 EC 私钥 DER 里取出私钥标量与公钥点。
     *
     * 结构（RFC 5915 + RFC 5208）：
     *   SEQUENCE {
     *     INTEGER 0,
     *     SEQUENCE { OID id-ecPublicKey, OID prime256v1 },
     *     OCTET STRING {                    ← 里面是 ECPrivateKey
     *       SEQUENCE {
     *         INTEGER 1,
     *         OCTET STRING <32 字节私钥>,
     *         [1] BIT STRING <04 || X || Y>  ← 可选，但抖音 SDK 生成的都带
     *       }
     *     }
     *   }
     *
     * @return {私钥 d, 公钥点} —— 公钥点在某些实现里缺失时会返回 null（调用方需自行点乘补出）
     */
    public static Object[] parsePkcs8Ec(byte[] der) {
        Der d = new Der(der);
        Der seq = d.readTagged(0x30);
        seq.readInteger();                       // version = 0
        seq.readTagged(0x30);                    // AlgorithmIdentifier
        Der octet = seq.readTagged(0x04);        // privateKey OCTET STRING
        Der ec = octet.readTagged(0x30);         // ECPrivateKey
        ec.readInteger();                        // version = 1
        byte[] priv = ec.readTagged(0x04).raw();
        Pt pub = null;
        Der rest = ec.peekTagged(0xA1);          // [1] publicKey
        if (rest != null) {
            Der bits = rest.readTagged(0x03);    // BIT STRING
            byte[] v = bits.raw();
            if (v.length >= 66 && v[0] == 0x00 && v[1] == 0x04) {
                // 未用位数(0) + 0x04 + X(32) + Y(32)
                pub = new Pt(fromFixed(slice(v, 2, 34)), fromFixed(slice(v, 34, 66)));
            }
        }
        BigInteger k = fromFixed(priv);
        if (pub == null) pub = multiply(g(), k);   // 没带公钥就自己算（成本可接受，只做一次）
        return new Object[] { k, pub };
    }

    /**
     * 取出 P-256 公钥点（`04 || X(32) || Y(32)`，共 65 字节）。
     *
     * ⚠️ 2026-11-07 真机踩坑：传进来的往往是**整张 X.509 证书**（不是裸 SPKI），
     *    直接按 SPKI 顺序读会读到 `tbsCertificate`（第一个孩子是 SEQUENCE 0x30 而不是 BIT STRING 0x03），
     *    于是抛 "DER 标签期望 0x3 实际 0x30"。
     *
     * 修法：**不猜结构**，直接在字节里找 P-256 SPKI 的固定前缀
     *     `3059301306072a8648ce3d020106082a8648ce3d030107034200`，
     *    其后 65 字节就是公钥点。这段前缀由 OID（prime256v1）决定，位置在证书里是唯一的，
     *    对"裸 SPKI"和"整张证书"两种输入都成立 —— 比按标签递归更省事也更稳。
     */
    public static Pt parseSpkiPoint(byte[] der) {
        if (der == null || der.length < 65) throw new IllegalArgumentException("公钥数据太短");
        byte[] prefix = hex(P256_SPKI_PREFIX);
        int at = indexOf(der, prefix);
        if (at < 0) {
            // 兜底：也可能是裸的 BIT STRING 内层（04||X||Y 直接给）
            for (int i = 0; i + 65 <= der.length; i++) {
                if (der[i] == 0x04 && looksLikePoint(der, i)) {
                    return new Pt(fromFixed(slice(der, i + 1, i + 33)),
                            fromFixed(slice(der, i + 33, i + 65)));
                }
            }
            throw new IllegalArgumentException("找不到 P-256 公钥（SPKI 前缀缺失）");
        }
        int p = at + prefix.length;
        // 前缀里的 034200 已经包含 BIT STRING 标签 + 长度 + 未用位 0，紧接的就是 04||X||Y
        if (p + 65 > der.length) throw new IllegalArgumentException("公钥点被截断");
        if (der[p] != 0x04) throw new IllegalArgumentException("不是非压缩点");
        return new Pt(fromFixed(slice(der, p + 1, p + 33)), fromFixed(slice(der, p + 33, p + 65)));
    }

    /** P-256 SubjectPublicKeyInfo 的固定前缀（AlgorithmIdentifier + BIT STRING 头） */
    private static final String P256_SPKI_PREFIX =
            "3059301306072a8648ce3d020106082a8648ce3d030107034200";

    /** 粗略校验：跟在 0x04 后的 64 字节"看起来像"曲线上一个点的坐标 */
    private static boolean looksLikePoint(byte[] b, int at) {
        if (at + 65 > b.length) return false;
        // X/Y 不能全 0（无穷远/占位），也不能明显超界
        boolean nonZero = false;
        for (int i = 1; i <= 64; i++) if (b[at + i] != 0) { nonZero = true; break; }
        if (!nonZero) return false;
        BigInteger x = fromFixed(slice(b, at + 1, at + 33));
        BigInteger y = fromFixed(slice(b, at + 33, at + 65));
        return x.compareTo(P) < 0 && y.compareTo(P) < 0;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 把 PEM（-----BEGIN XXX-----）拆成 DER 字节 */
    public static byte[] pemToDer(String pem) {
        if (pem == null) throw new IllegalArgumentException("PEM 为空");
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return B64.decode(body);
    }

    // ---------- ECDH / KDF ----------

    /**
     * ECDH 共享秘密：取 `d * Q` 的 X 坐标（32 字节大端）。
     * 与 Node 的 `crypto.diffieHellman()` / WebCrypto 的 `deriveBits` 输出一致。
     */
    public static byte[] ecdh(BigInteger d, Pt peer) {
        Pt s = multiply(peer, d);
        if (s.isInfinity()) throw new IllegalStateException("ECDH 得到无穷远点");
        return fixed32(s.x);
    }

    /**
     * HKDF-SHA256（RFC 5869），salt 与 info 均为空，输出 32 字节。
     *
     * 实测抖音的用法就是 `HKDF(salt="", info="", len=32)`：
     *   PRK = HMAC(salt=0x00*32, ikm)
     *   OKM = HMAC(PRK, info || 0x01)     ← 只需 1 个块（32 字节）
     */
    public static byte[] hkdfSha256(byte[] ikm) {
        byte[] salt = new byte[32];
        byte[] prk = hmacSha256(salt, ikm);
        byte[] infoAndCounter = new byte[] { 0x01 };
        return hmacSha256(prk, infoAndCounter);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        return hmacSha256(key, data, 0, data.length);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data, int off, int len) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            mac.update(data, off, len);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, to - from);
        return out;
    }

    static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(UTF8);
    }

    /**
     * 极简 DER 顺序读取器（只覆盖本文件用到的四种标签：SEQUENCE / INTEGER / OCTET STRING / BIT STRING）。
     * 不追求通用：**够用且出错就抛**，比半吊子的"宽容解析"更安全。
     */
    static final class Der {
        private final byte[] buf;
        int pos;

        Der(byte[] buf) {
            this.buf = buf;
        }

        /** 本节点的内容字节（供 BIT STRING / OCTET STRING 这类调用方直接取） */
        byte[] raw() {
            return buf;
        }

        /** 读下一个标签，校验期望值，返回内容（子读取器） */
        Der readTagged(int expectTag) {
            if (pos >= buf.length) throw new IllegalArgumentException("DER 提前结束");
            int tag = buf[pos++] & 0xff;
            if (tag != expectTag) {
                throw new IllegalArgumentException("DER 标签期望 0x" + Integer.toHexString(expectTag)
                        + " 实际 0x" + Integer.toHexString(tag));
            }
            int len = readLen();
            if (pos + len > buf.length) throw new IllegalArgumentException("DER 长度越界");
            Der sub = new Der(slice(buf, pos, pos + len));
            pos += len;
            return sub;
        }

        /** 若下一个就是该标签则读走，否则返回 null（不动 pos） */
        Der peekTagged(int tag) {
            if (pos < buf.length && (buf[pos] & 0xff) == tag) return readTagged(tag);
            return null;
        }

        byte[] readInteger() {
            return readTagged(0x02).buf;
        }

        private int readLen() {
            if (pos >= buf.length) throw new IllegalArgumentException("DER 长度缺失");
            int b = buf[pos++] & 0xff;
            if (b < 0x80) return b;
            int n = b & 0x7f;
            if (n == 0 || n > 4) throw new IllegalArgumentException("DER 不支持的变长编码");
            int len = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= buf.length) throw new IllegalArgumentException("DER 长度截断");
                len = (len << 8) | (buf[pos++] & 0xff);
            }
            return len;
        }
    }

    /**
     * Base64：自己实现，不用 android.util.Base64（它让 JVM 单测直接 "Stub!" 失败）
     * 也不用 java.util.Base64（API 26，本项目 minSdk 21）。
     */
    static final class B64 {
        private static final char[] STD =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        private static final int[] REV = new int[128];

        static {
            java.util.Arrays.fill(REV, -1);
            for (int i = 0; i < STD.length; i++) REV[STD[i]] = i;
            REV['-'] = 62;   // 容错 base64url
            REV['_'] = 63;
        }

        static String encode(byte[] data) {
            StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
            int i = 0;
            while (i + 2 < data.length) {
                int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
                sb.append(STD[(n >>> 18) & 63]).append(STD[(n >>> 12) & 63])
                        .append(STD[(n >>> 6) & 63]).append(STD[n & 63]);
                i += 3;
            }
            int left = data.length - i;
            if (left == 1) {
                int n = (data[i] & 0xff) << 16;
                sb.append(STD[(n >>> 18) & 63]).append(STD[(n >>> 12) & 63]).append("==");
            } else if (left == 2) {
                int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
                sb.append(STD[(n >>> 18) & 63]).append(STD[(n >>> 12) & 63])
                        .append(STD[(n >>> 6) & 63]).append('=');
            }
            return sb.toString();
        }

        static byte[] decode(String s) {
            if (s == null) return new byte[0];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4 + 4);
            int buf = 0, bits = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '=' ) break;
                if (c >= 128) continue;
                int v = REV[c];
                if (v < 0) continue;   // 跳过换行/空白等
                buf = (buf << 6) | v;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buf >>> bits) & 0xff);
                }
            }
            return out.toByteArray();
        }
    }
}
