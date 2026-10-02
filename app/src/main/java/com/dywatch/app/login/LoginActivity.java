package com.dywatch.app.login;

// M1 登录（架构 A：WebView 协议引擎）v2：
// ①先预热 www.douyin.com（拿 ttwid/设备 cookie，缺它 QR 接口会被风控拦）；
// ②再进 sso.douyin.com 的抖音品牌登录页（⚠️ login.douyin.com 现在只出"今日头条登录"，勿用）；
// ③轮询 sessionid → 落盘本机 → 进入应用。30 秒无痛重登 = 过期后回本页再扫。

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dywatch.app.ui.UiActivity;

import com.dywatch.app.R;
import com.dywatch.app.feed.FeedActivity;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.util.AppLog;

public class LoginActivity extends UiActivity {

    // 预热：先拿设备 cookie（实测缺它时 QR 接口被拦/页面降级）
    private static final String WARM_URL = "https://www.douyin.com/";
    // 抖音品牌登录页（sso 路径；login.douyin.com 已变成今日头条登录，勿用）
    private static final String LOGIN_URL =
            "https://sso.douyin.com/get_qrcode/?service=https%3A%2F%2Fwww.douyin.com"
                    + "&need_logo=false&aid=6383&account_sdk_source=web"
                    + "&qrcode_use_style=web_qrcode&language=zh";
    private static final String HOME_URL = "https://www.douyin.com/";

    private WebView mWebView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mDone;
    private boolean mWarmed;

    /**
     * 页面适配：整体缩放到屏宽 + 把二维码克隆成全屏白底覆盖层。
     *
     * ⚠️ 2026-10-01 修复"二维码不再自动全屏"：
     *   登录页是 SPA，实测 HTML 里 canvas/img/svg **一个都没有**（二维码 100% 由 JS 动态生成）。
     *   旧实现在 onPageFinished 后**固定等 400ms 且只试一次**，手表（32 位 A53 + WebView 83）
     *   渲染慢于 400ms 时就找不到二维码 → 覆盖层永远不出现。
     *   手机渲染快所以看不出问题——这就是"某版本会自动全屏、现在又没了"的真相：
     *   **代码一直没变，也没被删，只是手表上稳定错过那 400ms 窗口**。
     *
     * 修法：改成**轮询等待**（最多 8 秒，每 300ms 找一次），找到即叠加并停止；
     *       并把候选从 img/canvas 放宽到含二维码图的元素（含 CSS background-image）。
     * 结果仍回传 overlay=Y/N，便于真机确认（adb logcat -s dywatch 看"页面适配"）。
     */
    private static final String FIT_QR_JS =
            "(function(){try{"
                    + "var vw=document.documentElement.clientWidth;"
                    + "var sw=document.documentElement.scrollWidth;"
                    + "var bw=document.body?document.body.scrollWidth:0;"
                    + "var wide=Math.max(sw,bw,vw);"
                    + "var scale=Math.min(1,vw/wide);"
                    // 找二维码：img / canvas / 带二维码背景图的元素
                    + "function findQr(){var cands=document.querySelectorAll('img,canvas,div,span');"
                    + "var best=null,ba=0;"
                    + "for(var i=0;i<cands.length;i++){var e=cands[i];var r=e.getBoundingClientRect();"
                    + "var a=r.width*r.height;if(a<=ba)continue;"
                    + "var sq=Math.abs(r.width-r.height)/Math.max(r.width,1);"
                    + "if(sq>=0.4||r.width<50)continue;"
                    + "if(e.tagName==='IMG'||e.tagName==='CANVAS'){best=e;ba=a;continue;}"
                    + "var bg=getComputedStyle(e).backgroundImage||'';"
                    + "if(bg&&bg.indexOf('url(')===0){best=e;ba=a;}}"
                    + "return best;}"
                    + "function srcOf(e){if(!e)return '';"
                    + "if(e.tagName==='CANVAS'&&e.toDataURL){try{return e.toDataURL();}catch(x){return '';}}"
                    + "if(e.tagName==='IMG')return e.src||'';"
                    + "var bg=getComputedStyle(e).backgroundImage||'';"
                    + "var m=bg.match(/url\\(\"?'?([^\"')]+)/);return m?m[1]:'';}"
                    + "var tries=0,timer=setInterval(function(){"
                    + "tries++;var best=findQr();var src=srcOf(best);"
                    + "if(src){clearInterval(timer);"
                    + "document.documentElement.style.zoom='1';"
                    + "var wrap=document.createElement('div');"
                    + "wrap.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;background:#fff;"
                    + "z-index:2147483647;display:flex;align-items:center;justify-content:center;';"
                    + "var im=new Image();im.src=src;"
                    + "im.style.cssText='width:84vw;height:84vw;max-width:82vh;max-height:82vh;';"
                    + "wrap.appendChild(im);"
                    + "wrap.onclick=function(){location.reload();};"
                    + "document.body.appendChild(wrap);"
                    + "if(window.FitBridge){FitBridge.report('vw='+vw+' s='+scale.toFixed(2)"
                    + "+' qr=Y overlay=Y tries='+tries);}"
                    + "return;}"
                    + "if(tries>=27){"   // 27 × 300ms ≈ 8 秒
                    + "clearInterval(timer);"
                    + "if(scale<0.999){document.documentElement.style.zoom=Math.floor(scale*100)+'%';}"
                    + "if(window.FitBridge){FitBridge.report('vw='+vw+' s='+scale.toFixed(2)"
                    + "+' qr=N overlay=N tries='+tries);}}"
                    + "},300);"
                    + "}catch(e){if(window.FitBridge){FitBridge.report('ERR '+e);}}})();";

    /** JS→Java：回传页面适配统计（调参用） */
    private final class FitBridge {
        @android.webkit.JavascriptInterface
        public void report(String info) {
            AppLog.i("login", "页面适配: " + info);
            // ⚠️ 别把调试串（vw=372 s=0.86 overlay=Y）直接给用户看：这是开发期调参用的。
            //    只在没找到二维码时提示一句人话，其余保持原有引导文案。
            final boolean hasOverlay = info != null && info.contains("overlay=Y");
            final boolean gaveUp = info != null && info.contains("overlay=N");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (hasOverlay) {
                        setHint("请用手机抖音「扫一扫」登录（点二维码可刷新）");
                    } else if (gaveUp) {
                        setHint("请用手机抖音「扫一扫」登录；若二维码太小，可点页面刷新重试");
                    }
                }
            });
        }

        /** 证据式登录校验结果（profile/self 返回 OK|昵称 或 FAIL|原因） */
        @android.webkit.JavascriptInterface
        public void verify(final String result) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onVerifyResult(result);
                }
            });
        }
    }

    /**
     * 证据式登录（真机教训：cookie 里出现 sessionid ≠ 登录成功，中间态/秒死会话会误报）：
     * 必须 profile/self 返回真实 uid 才落盘会话、进应用。
     */
    private static final String VERIFY_JS =
            "(function(){try{"
                    + "fetch('https://www.douyin.com/aweme/v1/web/user/profile/self/"
                    + "?device_platform=webapp&aid=6383&channel=channel_pc_web&version_code=170400"
                    + "&cookie_enabled=true&platform=PC',{credentials:'include'})"
                    + ".then(function(r){return r.json();})"
                    + ".then(function(j){"
                    + "if(j&&j.user&&j.user.uid){FitBridge.verify('OK|'+(j.user.nickname||''));}"
                    + "else{FitBridge.verify('FAIL|status='+(j?j.status_code:'?'));}"
                    + "}).catch(function(e){FitBridge.verify('ERR|'+e);});"
                    + "}catch(e){FitBridge.verify('ERR|'+e);}})();";

    private boolean mVerifying;
    private int mVerifyFails;

    /** 校验结果处理：OK 才算登录成功；FAIL 继续等（会话可能还在落地），多次失败提示重扫 */
    private void onVerifyResult(String result) {
        if (mDone) return;
        mVerifying = false;
        String r = result == null ? "" : result;
        if (r.startsWith("OK")) {
            mDone = true;
            String cookies = CookieManager.getInstance().getCookie(HOME_URL);
            LoginManager.saveCookies(LoginActivity.this, cookies);
            LoginManager.setVerified(LoginActivity.this, true);
            AppLog.i("login", "会话校验通过（昵称=" + (r.length() > 3 ? "有" : "无") + "），登录完成");
            startActivity(new android.content.Intent(LoginActivity.this, FeedActivity.class));
            finish();
            return;
        }
        mVerifyFails++;
        AppLog.i("login", "会话校验未过(" + mVerifyFails + "): " + r);
        if (mVerifyFails >= 5) {
            setHint("登录未生效（" + r + "），请点二维码刷新后重扫；若要求验证码请按页面提示完成");
        } else {
            setHint("会话校验中…（" + r + "）");
        }
        mHandler.removeCallbacks(mCookiePoll);
        mHandler.postDelayed(mCookiePoll, 3000);
    }

    private final Runnable mCookiePoll = new Runnable() {
        @Override
        public void run() {
            if (mDone) return;
            String cookies = CookieManager.getInstance().getCookie(HOME_URL);
            if (cookies != null && cookies.contains("sessionid=") && !mVerifying) {
                // 不直接宣布成功：先证据式校验（profile/self 拿到 uid 才落盘）
                mVerifying = true;
                setHint("检测到会话，正在校验…");
                AppLog.i("login", "检测到 sessionid，开始会话校验");
                mWebView.evaluateJavascript(VERIFY_JS, null);
            }
            mHandler.postDelayed(this, 2000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        setPageTitle("登录");

        mWebView = findViewById(R.id.login_webview);
        mWebView.addJavascriptInterface(new FitBridge(), "FitBridge");
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // ⚠️ 必须 wide viewport：页面按宽度切流程，窄屏会变"今日头条/手机号登录"，宽屏才是抖音扫码页
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(DouyinApi.UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(mWebView, true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                AppLog.i("login", "页面加载完成: " + url);
                com.dywatch.app.ui.Loading.show(LoginActivity.this, false);   // 页面出来了 → 停转圈
                if (!mWarmed && url.contains("douyin.com") && !url.contains("sso.")) {
                    // 预热完成 → 进登录页
                    mWarmed = true;
                    setHint("正在打开登录页…");
                    mWebView.loadUrl(LOGIN_URL);
                    return;
                }
                setHint("请用手机抖音「扫一扫」登录（页面上的二维码）");
                // 智能适配：整体缩放到屏宽 + 放大二维码并滚动居中（手表小屏关键）
                view.evaluateJavascript(FIT_QR_JS, null);
                mHandler.removeCallbacks(mCookiePoll);
                mHandler.postDelayed(mCookiePoll, 1000);
            }
        });

        setHint("正在预热设备环境…");
        com.dywatch.app.ui.Loading.show(this, true);
        mWebView.loadUrl(WARM_URL);

        // 手表硬件适配 §10：可见返回键
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void setHint(String text) {
        android.widget.TextView hint = findViewById(R.id.tv_login_hint);
        if (hint != null) hint.setText(text);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mCookiePoll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mDone) mHandler.postDelayed(mCookiePoll, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mCookiePoll);
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
    }
}
