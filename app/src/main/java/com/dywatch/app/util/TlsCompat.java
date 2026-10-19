package com.dywatch.app.util;

import android.content.Context;
import android.os.Build;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 老系统的 TLS 兜底：**自带一份新的 CA 证书列表**。
 *
 * 解决的坑（Android 4.4 模拟器上实测出来的）：
 * ```
 * javax.net.ssl.SSLHandshakeException:
 *   java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.
 * ```
 * 原因是**系统内置的根证书是出厂那年的**（Android 4.4 是 2013 年），而抖音现在用的证书
 * 签发链里有个更新的根（比如 Let's Encrypt 的 ISRG Root X1，2015 年才有）——
 * 老系统压根不认识它，握手直接失败。表现就是"一条视频都拉不到"。
 *
 * 做法：把 Mozilla 的 CA 列表（assets/cacert.pem）打进包里，自己建一个 SSLContext，
 * 既配给 HttpsURLConnection（视频预加载/代理走这条），也交给 OkHttp（接口请求走这条）。
 *
 * ⚠️ 只对 **API < 21** 启用：新系统的 CA 库本来就是维护着的，没必要多此一举，
 *    也避免把自己锁在某个静态列表上。
 */
public final class TlsCompat {

    private static SSLContext sCtx;
    private static X509TrustManager sTm;

    private TlsCompat() {
    }

    /** 装一次即可（在 Application.onCreate 里调）。失败不抛，只是继续用系统默认。 */
    public static void install(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return;   // API 21+ 系统 CA 和 TLS 栈都够新
        }

        // ---- ① 先把 TLS 实现换成 Conscrypt（自带 BoringSSL）----
        //
        // 为什么非换不可：Android 4.4 自带的 OpenSSL 1.0.1 太老，握手时服务器直接回
        //   "error:1407742E:SSL routines:SSL23_GET_SERVER_HELLO:tlsv1 alert protocol version"
        // —— 现代服务（抖音这种）早就不收老协议了。应用层把 enabledProtocols 设成 TLS1.2
        // 也改不了底层 OpenSSL 发出的 ClientHello，只能整个 provider 换掉。
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1);
            AppLog.i("tls", "已装入 Conscrypt（现代 TLS 实现，替换老系统的 OpenSSL）");
        } catch (Throwable t) {
            AppLog.i("tls", "装入 Conscrypt 失败，将退回系统实现: " + t);
        }

        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream is = context.getAssets().open("cacert.pem");
            int count = 0;
            try {
                // ⚠️ 不能用 cf.generateCertificates(is) 一把梭：Android 4.4 的实现遇到它不认识的
                //    证书（较新的扩展/签名算法）会让**整批**返回空 —— 实测打过这条日志：
                //    "[tls] cacert.pem 里没有证书，跳过"，结果 CA 一个都没装上。
                //    改成手工按 PEM 块切分、逐个解析，坏的那块丢掉、好的照收。
                String text = readAll(is);
                final String BEGIN = "-----BEGIN CERTIFICATE-----";
                final String END = "-----END CERTIFICATE-----";
                int from = 0;
                while (true) {
                    int b = text.indexOf(BEGIN, from);
                    if (b < 0) break;
                    int e = text.indexOf(END, b);
                    if (e < 0) break;
                    String block = text.substring(b, e + END.length());
                    from = e + END.length();
                    try {
                        byte[] der = android.util.Base64.decode(
                                block.replace(BEGIN, "").replace(END, "").replaceAll("\\s", ""),
                                android.util.Base64.DEFAULT);
                        Certificate c = cf.generateCertificate(new java.io.ByteArrayInputStream(der));
                        ks.setCertificateEntry("ca" + (count++), c);
                    } catch (Throwable ignored) {
                        // 单张证书解析失败不影响其他证书（老系统认不得新算法是常态）
                    }
                }
            } finally {
                try {
                    is.close();
                } catch (Throwable ignored) {
                }
            }
            if (count == 0) {
                AppLog.i("tls", "cacert.pem 里没有证书，跳过");
                return;
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            // 装入 Conscrypt 之后，"TLS" 走的是 BoringSSL（支持 TLS 1.0~1.3、自动协商），
            // 不再是老 OpenSSL 那个只会 TLS 1.0 的实现 —— 所以这里用 "TLS" 就好。
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);

            sCtx = ctx;
            sTm = firstX509(tmf);

            // 视频预加载/代理走的是 HttpsURLConnection，必须配全局默认
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());

            AppLog.i("tls", "老系统（API " + Build.VERSION.SDK_INT + "）已装载自带 CA：" + count + " 个证书");
        } catch (Throwable t) {
            // 兜底失败也不该影响别的功能（顶多是老设备上仍连不上）
            AppLog.i("tls", "装载自带 CA 失败：" + t);
        }
    }

    /** 给 OkHttp 用；未启用（API 21+ 或装载失败）时返回 null，调用方保持默认即可 */
    public static SSLContext sslContext() {
        return sCtx;
    }

    public static X509TrustManager trustManager() {
        return sTm;
    }

    /** 把 CA 文本整个读进来（后面要按 PEM 块手工切分，所以不能边读边解析） */
    private static String readAll(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        // 用 Charset.forName 而非 StandardCharsets：后者是 API 19 才有的类，Android 4.0–4.3 会崩
        return new String(bos.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    private static X509TrustManager firstX509(TrustManagerFactory tmf) {
        for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        return null;
    }
}
