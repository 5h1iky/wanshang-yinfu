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

    /** 页面适配：整体缩放到屏宽，放大二维码并居中；结果回传统计值供调参 */
    private static final String FIT_QR_JS =
            "(function(){try{"
                    + "var vw=document.documentElement.clientWidth;"
                    + "var sw=document.documentElement.scrollWidth;"
                    + "var bw=document.body?document.body.scrollWidth:0;"
                    + "var wide=Math.max(sw,bw,vw);"
                    + "var scale=Math.min(1,vw/wide);"
                    + "if(scale<0.999){document.documentElement.style.zoom=Math.floor(scale*100)+'%';}"
                    + "setTimeout(function(){"
                    + "var cands=document.querySelectorAll('img,canvas');var best=null,ba=0;"
                    + "for(var i=0;i<cands.length;i++){var r=cands[i].getBoundingClientRect();"
                    + "var a=r.width*r.height;var sq=Math.abs(r.width-r.height)/Math.max(r.width,1);"
                    + "if(a>ba&&sq<0.4&&r.width>=50){best=cands[i];ba=a;}}"
                    + "var info='vw='+vw+' sw='+sw+' bw='+bw+' s='+scale.toFixed(2)+' qr='+(best?'Y':'N');"
                    // 终极适配：把二维码克隆成全屏白底覆盖层（不依赖页面布局，永远完整可扫）
                    // 点击覆盖层 = 刷新页面（二维码过期时用）
                    + "if(best){document.documentElement.style.zoom='1';"
                    + "var src=(best.tagName==='CANVAS'&&best.toDataURL)?best.toDataURL():(best.src||'');"
                    + "if(src){var wrap=document.createElement('div');"
                    + "wrap.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;background:#fff;"
                    + "z-index:2147483647;display:flex;align-items:center;justify-content:center;';"
                    + "var img=new Image();img.src=src;"
                    + "img.style.cssText='width:84vw;height:84vw;max-width:82vh;max-height:82vh;';"
                    + "wrap.appendChild(img);"
                    + "wrap.onclick=function(){location.reload();};"
                    + "document.body.appendChild(wrap);info=info+' overlay=Y';}}"
                    + "if(window.FitBridge){FitBridge.report(info);}"
                    + "},400);"
                    + "}catch(e){if(window.FitBridge){FitBridge.report('ERR '+e);}}})();";

    /** JS→Java：回传页面适配统计（调参用） */
    private final class FitBridge {
        @android.webkit.JavascriptInterface
        public void report(String info) {
            AppLog.i("login", "页面适配: " + info);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setHint("页面适配 " + info + "（QR 可扫即可）");
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
            AppLog.i("login", "会话校验通过（" + r + "），登录完成");
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
