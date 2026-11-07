package com.dywatch.app.chat.api;

// 凭证提取器：用一个**临时隐藏 WebView** 打开抖音主页，跑一段只读 JS 把
// 「IndexedDB secure-store + localStorage 里的 SDK 凭证」读出来。
//
// 为什么必须借 WebView：
//   IndexedDB 与 localStorage 都在浏览器的存储分区里，原生侧没有公开 API 能直接读
//   WebView 的 IndexedDB。但只要页面在**同一 origin**（www.douyin.com）下，
//   一段同源 JS 就能读到，读出来经 addJavascriptInterface 回传即可。
//
// ⚠️ 这条路径**只在需要发送/历史上翻时才唤醒一次**：
//   · 读会话列表 / 收新消息完全不需要凭证（cookie 就够，见 ImApi）；
//   · 凭证有效期约 30 分钟（见 ImCreds.TTL），过期才重新提取；
//   · 提取完成后立刻销毁 WebView，不常驻（这正是 API 化要拿到的内存收益）。
//
// ⚠️ 安全：JS 只读、不住任何东西；回传的值只经 JSON 交给 ImCreds 落 SharedPreferences。
//    日志里只记长度，不记值（与项目既有口径一致）。

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dywatch.app.login.LoginManager;
import com.dywatch.app.util.AppLog;

public final class CredExtractor {

    private static final String TAG = "imcred";

    /** 与登录同一个 origin：同源才读得到那个分区里的 IndexedDB */
    private static final String URL = "https://www.douyin.com/";

    /** 提取超时：真机实测 5~15s（要等页面把 secure-store 建起来） */
    private static final long TIMEOUT_MS = 20_000L;

    public interface Callback {
        /** ok=false 时 why 是给用户看的一句话（不含凭证内容） */
        void onResult(boolean ok, ImCreds creds, String why);
    }

    private final Context mCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private WebView mWeb;
    private Callback mCb;
    private boolean mDone;
    /** 同一页面只跑一条轮询，避免 onPageFinished 重入时叠加多条 */
    private boolean mPolling;
    private long mStartedAt;

    /** 总超时：到点才给失败终态；中途的空/不完整结果一律继续等。 */
    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            if (mDone) return;
            AppLog.i(TAG, "凭证提取总超时");
            finish(false, null, "读取凭证超时");
        }
    };

    /**
     * 页面就绪后的轮询：不设“尝试次数上限”，一直读到完整凭证或总超时。
     * 旧实现 3 次就 finish(false)，而 IndexedDB 建立经常要 5~15s，重试等于白做。
     */
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (mDone || mWeb == null) {
                mPolling = false;
                return;
            }
            long elapsed = System.currentTimeMillis() - mStartedAt;
            if (CredExtractionPolicy.decide(false, elapsed, TIMEOUT_MS)
                    == CredExtractionPolicy.Decision.TIMEOUT) {
                mPolling = false;
                finish(false, null, "读取凭证超时");
                return;
            }
            try {
                mWeb.evaluateJavascript(JS_EXTRACT, null);
            } catch (Throwable t) {
                mPolling = false;
                finish(false, null, "读取凭证失败");
                return;
            }
            mMain.postDelayed(this, 2500L);
        }
    };

    public CredExtractor(Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    /**
     * 异步提取一次。回调一定在主线程；无论成败都会自行销毁 WebView。
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void extract(final Callback cb) {
        mCb = cb;
        mDone = false;
        mPolling = false;
        mStartedAt = System.currentTimeMillis();

        mWeb = new WebView(mCtx);
        WebSettings s = mWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // UA 要与协议请求一致：页面会按 UA 决定走 PC 版 SDK
        s.setUserAgentString(ImUserInfo.UA);
        mWeb.addJavascriptInterface(new Bridge(), "DyCred");

        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // IndexedDB 是异步建的，页面 load 完不一定已经有 secure-store。
                // 给 1.5s 缓冲后开始轮询；同一文档只启动一条轮询。
                if (mDone || mPolling) return;
                mPolling = true;
                mMain.postDelayed(mPoll, 1500L);
            }
        });

        // 会话 cookie 先回写一次（幂等，只补缺失键）—— 否则临时 WebView 是"未登录"状态，
        // 有些 SDK 只在登录态下才把凭证写进 secure-store。
        LoginManager.restoreToCookieManager(mCtx);
        try {
            mWeb.loadUrl(URL);
        } catch (Throwable t) {
            finish(false, null, "无法打开网页（" + t.getClass().getSimpleName() + "）");
            return;
        }

        mMain.postDelayed(mTimeout, TIMEOUT_MS);
    }

    /** 销毁（正常路径由 finish 自动调；页面提前退出时也应调，避免泄漏） */
    public void destroy() {
        mDone = true;
        mCb = null;
        mPolling = false;
        mMain.removeCallbacks(mPoll);
        mMain.removeCallbacks(mTimeout);
        destroyWeb();
    }

    private void finish(boolean ok, ImCreds creds, String why) {
        if (mDone) return;   // 唯一终态：重复 finish 不再回调
        mDone = true;
        mPolling = false;
        mMain.removeCallbacks(mPoll);
        mMain.removeCallbacks(mTimeout);
        destroyWeb();
        Callback cb = mCb;
        mCb = null;
        if (cb != null) cb.onResult(ok, creds, why);
    }

    private void destroyWeb() {
        final WebView w = mWeb;
        mWeb = null;
        if (w == null) return;
        try {
            w.stopLoading();
            w.setWebViewClient(new WebViewClient());
            w.removeJavascriptInterface("DyCred");
            w.loadUrl("about:blank");
            w.destroy();
        } catch (Throwable t) {
            AppLog.i(TAG, "销毁临时 WebView 失败: " + t.getClass().getSimpleName());
        }
    }

    /** 页面 → 原生回传通道 */
    private final class Bridge {
        @JavascriptInterface
        public void result(String json) {
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    onJsResult(json);
                }
            });
        }

        @JavascriptInterface
        public void fail(String why) {
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    // 页面这一轮读不到不等于永远读不到（可能 secure-store 还没建好）：
                    // 不在这里结束，交给轮询/总超时。
                    AppLog.i(TAG, "页面报告读取失败（继续等重试）: "
                            + (why == null ? "" : why));
                    if (CredExtractionPolicy.decide(false,
                            System.currentTimeMillis() - mStartedAt, TIMEOUT_MS)
                            == CredExtractionPolicy.Decision.TIMEOUT) {
                        finish(false, null, why == null ? "读取凭证超时" : why);
                    }
                }
            });
        }
    }

    private void onJsResult(String json) {
        if (mDone) return;
        ImCreds c;
        try {
            c = new ImCreds();
            c.ecPrivateKey = pick(json, "priv");
            c.ticket = pick(json, "ticket");
            c.tsSign = pick(json, "tssign");
            c.clientCert = pick(json, "client");
            c.serverCert = pick(json, "server");
            c.extractedAt = System.currentTimeMillis();
        } catch (Throwable t) {
            // 单次解析失败可能是页面还没写完，继续等下一轮，不要提前销毁 WebView。
            AppLog.i(TAG, "凭证解析失败（继续等重试）");
            return;
        }

        long elapsed = System.currentTimeMillis() - mStartedAt;
        CredExtractionPolicy.Decision d =
                CredExtractionPolicy.decide(c.complete(), elapsed, TIMEOUT_MS);
        if (d == CredExtractionPolicy.Decision.COMPLETE) {
            c.save(mCtx);
            AppLog.i(TAG, "凭证已提取 " + c.describe());
            finish(true, c, "");
            return;
        }
        if (d == CredExtractionPolicy.Decision.TIMEOUT) {
            AppLog.i(TAG, "凭证不完整且已到总超时: " + c.describe());
            finish(false, null, "读取凭证超时");
            return;
        }
        // 关键修复：不完整结果只记日志，继续等 mPoll 的下一轮。
        AppLog.i(TAG, "凭证不完整（继续重试）: " + c.describe());
    }

    /** 从 JS 交回来的扁平 JSON 里取字段（值本身可能含引号/换行，所以用带转义的解析） */
    private static String pick(String json, String key) {
        return ImSend.jsonString(json, key);
    }

    /**
     * 只读 JS：把 secure-store 里的 3 条记录 + localStorage 的 server cert 拼成一个 JSON 回传。
     *
     * 注意几点（都是真机踩过的）：
     *   · 每条记录是 `{"data": "<json 字符串>"}` —— 要 JSON.parse 两层；
     *   · 私钥是 PEM 明文（`-----BEGIN PRIVATE KEY-----`），不是 CryptoKey；
     *   · server cert 在 localStorage，值是 `{"cert": "-----BEGIN CERTIFICATE-----…", "sn": …}`；
     *   · 任何一步取不到都不要抛到外面（否则 Java 侧只看到空），而是回传缺哪个。
     */
    private static final String JS_EXTRACT = "(function(){"
            + "if(!window.DyCred){return;}"
            + "function odb(n){return new Promise(function(res,rej){"
            + "  var r=indexedDB.open(n);"
            + "  r.onsuccess=function(){res(r.result)};"
            + "  r.onerror=function(){rej(r.error)};});}"
            + "function getAll(db,st){return new Promise(function(res){"
            + "  try{var tx=db.transaction(st,'readonly');var o=tx.objectStore(st);var k=o.getAll();"
            + "    k.onsuccess=function(){res(k.result)};k.onerror=function(){res([])};}catch(e){res([])}});}"
            + "function q(s){return JSON.stringify(s==null?'':String(s));}"
            + "try{"
            + "  var out={priv:'',ticket:'',tssign:'',client:'',server:''};"
            + "  odb('secure-store').then(function(db){"
            + "    return getAll(db,'cryptvalues').then(function(vals){"
            + "      (vals||[]).forEach(function(s){"
            + "        try{"
            + "          var o=(typeof s==='string')?JSON.parse(s):s;"
            + "          var d=(typeof o.data==='string')?JSON.parse(o.data):o.data;"
            + "          if(!d){return;}"
            + "          if(d.ec_privateKey){out.priv=d.ec_privateKey;}"
            + "          if(d.ticket){out.ticket=d.ticket;out.tssign=d.ts_sign||'';out.client=d.client_cert||'';}"
            + "        }catch(e){}"
            + "      });"
            + "      try{"
            + "        var sc=localStorage.getItem('security-sdk/s_sdk_server_cert_key');"
            + "        if(sc){var so=JSON.parse(sc);out.server=so.cert||'';}"
            + "      }catch(e){}"
            + "      DyCred.result('{\"priv\":'+q(out.priv)+',\"ticket\":'+q(out.ticket)"
            + "        +',\"tssign\":'+q(out.tssign)+',\"client\":'+q(out.client)"
            + "        +',\"server\":'+q(out.server)+'}');"
            + "    });"
            + "  }).catch(function(e){"
            + "    try{DyCred.result('{\"priv\":\"\",\"ticket\":\"\",\"tssign\":\"\",\"client\":\"\",\"server\":\"\"}');}"
            + "    catch(x){DyCred.fail('网页里找不到安全存储');}"
            + "  });"
            + "}catch(e){"
            + "  try{DyCred.fail('读取凭证异常');}catch(x){}"
            + "}"
            + "})();";
}
