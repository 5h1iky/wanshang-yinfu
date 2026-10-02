package com.dywatch.app.chat;

// 聊天协议引擎（架构 A：WebView 协议引擎）。
// 隐藏 WebView 加载抖音私信页，页面 SDK 负责全部票据/签名/风控（真浏览器环境），
// 本引擎通过注入 JS（chat_bridge.js）驱动页面：拉会话/拉消息/发消息。
// v1 为脚手架：JS 桥接口已定（见 assets/chat_bridge.js），DOM 选择器待真机联调校准。

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ChatEngine {

    public interface Listener {
        void onEngineReady();
        void onMessages(List<ChatMessage> list);
        void onConversations(List<com.dywatch.app.chat.model.Conversation> list);
        void onComments(List<com.dywatch.app.chat.model.Comment> list);
        /** 评论「加载更多」追加的一批；atEnd=true 表示评论区已滚到底 */
        void onCommentsMore(List<com.dywatch.app.chat.model.Comment> list, boolean atEnd);
        void onAuth(boolean ok, String message);
        void onEngineError(String err);
        /**
         * 引擎还在热身（2026-10-01 新增，用户反馈"刚进聊天页就报通道异常"）。
         *
         * ⚠️ 为什么不能复用 onEngineError：刚进页面时"引擎尚未 ready"是**完全正常**的状态
         * （真机实测：进页面 → onEngineReady 要十几秒），把它当错误显示，用户看到的就是
         * "通道异常: 通道未就绪"，紧接着几秒后列表又出来了 —— 提示自己在打自己的脸。
         * 语义分家：本回调 = **等待中**（页面应显示进度 + 转圈），onEngineError = 真出问题了。
         * （不用接口 default 方法：本项目 minSdk 21，显式实现更稳。）
         */
        void onEngineWaiting(String why);
    }

    /** 一次性互动动作（点赞/收藏/发评论）结果回调 */
    public interface ActionListener {
        void onActionResult(String action, boolean ok, String detail);
    }

    /**
     * 动作回调登记表：reqId → 发起方。
     *
     * ⚠️ 2026-10-02 修（代码审计 M17）：原来这里是一个**单槽** `mActionListener`，
     * 于是"同一条视频先点赞、再收藏"时，第二次注册会把第一次的回调顶掉——
     * 结果就是：点赞的结果被当成收藏的结果处理（弹错 toast、回滚错的状态），
     * 而收藏的结果**谁都收不到**（槽已被置空），只能等 30 秒兜底解锁，
     * 界面上永久留着一个"看着已收藏、其实没收藏"的假状态。
     *
     * 现在每个动作有独立请求号（reqId），桥把它原样回传（含跨页 sessionStorage 续跑那条路），
     * 引擎按 reqId 精确投递，互不覆盖。reqId 单调递增、永不复用，所以迟到的结果
     * 也不会被投给后来同 key 的请求。
     */
    private final java.util.Map<String, ActionListener> mActionCallbacks = new java.util.HashMap<>();
    private long mReqSeq;

    /** 取一个请求号；cb 非空时登记回调 */
    private String registerAction(ActionListener cb) {
        String id = "r" + (++mReqSeq);
        if (cb != null) mActionCallbacks.put(id, cb);
        return id;
    }

    /**
     * 撤销某次动作的回调登记（页面销毁时调用）。
     * 不撤销的话：回调对象会一直被引擎强引用到结果回来为止（页面已销毁 = 白留一串视图树）。
     */
    public void cancelAction(String reqId) {
        if (reqId != null) mActionCallbacks.remove(reqId);
    }

    // ⚠️ 私信入口是 /chat（实测 200+SPA）；/messages 是 404 死路由（调研报告过时勿信）
    private static final String MESSAGES_URL = "https://www.douyin.com/chat";

    /** 单例：会话列表页/会话页共用一个页面引擎（手表性能：WebView 全局只留一个） */
    private static ChatEngine sInstance;

    public static synchronized ChatEngine getInstance(android.content.Context ctx, Listener listener) {
        return getInstance(ctx, listener, null);
    }

    /**
     * @param initialUrl 仅首次创建时生效（引擎只有一个，后续页面靠 ensureImHome 切页）；
     *                   传 null = 默认私信页。
     */
    public static synchronized ChatEngine getInstance(android.content.Context ctx, Listener listener,
                                                      String initialUrl) {
        if (sInstance == null) {
            sInstance = new ChatEngine(ctx.getApplicationContext(), listener,
                    initialUrl == null ? MESSAGES_URL : initialUrl);
        } else if (listener != null) {
            // ⚠️ 只在传了监听器时换绑：曾经无条件赋值，导致视频页用 getInstance(ctx, null)
            // 取引擎时把聊天页的监听器静默摘掉（互动一次→聊天通道再也收不到回调）。
            sInstance.mListener = listener;
            if (sInstance.mReady) listener.onEngineReady();
        }
        return sInstance;
    }

    private final android.content.Context mCtx;
    private Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WebView mWebView;
    /** 触摸盾：只吃触摸的容器，包住引擎（见构造器注释） */
    private final android.widget.FrameLayout mShield;
    /** 本次注入的文档密钥；桥事件必须带着它（防页面内第三方框架伪造） */
    private volatile String mBridgeKey = "";
    private boolean mReady;
    /** 桥在文档里生成的令牌；同一个文档的重复 onPageFinished 靠它判重 */
    private String mDocToken = "";
    /**
     * 未就绪期间暂存的一次性动作（就绪后按序补发；跨页续跑由桥 sessionStorage 负责）。
     *
     * ⚠️ 2026-10-02 修：原来是一个 volatile String 单槽，未就绪时连点两次互动，
     * 第一次的动作会被第二次直接覆盖 —— 第一次永远发不出去，用户等 30 秒兜底。
     * 现在排队，按发起顺序补发。
     */
    private final java.util.ArrayDeque<String> mPendingJs = new java.util.ArrayDeque<>();

    @SuppressLint("SetJavaScriptEnabled")
    private ChatEngine(android.content.Context ctx, Listener listener, String homeUrl) {
        mCtx = ctx;
        mListener = listener;
        logWebViewEnv();   // 先自述内核版本：手表上"跑不跑得动"的第一手证据
        setupJsPatch();    // 内核过旧 → 启用私信 JS 语法补丁（方案 A）
        mWebView = new WebView(ctx);
        mWebView.addJavascriptInterface(this, BRIDGE_NAME); // JS → Java 回调
        // 触摸盾（2026-10-02，代码审计 H4-b）：引擎挂在内容层 index 0，页面根布局**不消费**的
        // 触摸会继续下发给下一个兄弟 —— 也就是这个网页。用户"点页头/时钟/空白处"时，网页
        // 会真的收到点击：可能把引擎导航走（聊天/评论 DOM 从此抓空）、弹出网页键盘，
        // 甚至看不见地对自己账号做了点赞/关注。加一层只吃触摸、不碰渲染的容器即可根治：
        // 尺寸/可见性/visibilityState 全不变（页面照常加载），但网页再也收不到手指事件。
        mShield = new TouchShield(ctx);
        mShield.addView(mWebView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(DouyinApi.UA);
        mWebView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                // 页面脚本报错是"DOM 抓不到东西"最常见的原因（尤其老内核跑不动现代 bundle）
                if (cm != null && cm.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    // 同样脱敏：页面报错里可能夹着带正文的 DOM 片段（审计 H3）
                    AppLog.i("engine", "JS错误: " + BridgeLog.safeText(cm.message())
                            + " @" + cm.sourceId() + ":" + cm.lineNumber());
                }
                return true;
            }
        });
        mWebView.setWebViewClient(new WebViewClient() {

            /**
             * 导航白名单（2026-10-02，代码审计 H4-a）。
             *
             * 原来没有这个覆写 = 引擎里发生的**任何**导航都会被照单全收：页面自己跳、
             * 被触摸穿透误触发的跳转、甚至广告/短链跳到站外，都会把这个"带着登录态的
             * 真浏览器"开到任意网址上去。而这个 WebView 里挂着 AndroidBridge，
             * 一旦停在非抖音页面上，等于把一个能回调原生协议的网页容器交给了别人。
             *
             * 口径：只放行抖音自家域（含登录/风控用到的同族域），其余一律拦下、只记 host
             * （不记完整 URL —— URL 的 query 里可能有搜索词等用户内容）。
             * 子框架不拦：iframe 里的第三方资源是页面正常渲染的一部分，不涉及本引擎的会话与桥。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest req) {
                if (req == null) return false;
                if (!req.isForMainFrame()) return false;   // 子框架照旧
                return blockIfOutside(req.getUrl() == null ? null : req.getUrl().toString());
            }

            /** API 21~23 走这个（minSdk 21 必须一起覆写，否则老设备上白名单形同虚设） */
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockIfOutside(url);
            }

            /** @return true = 拦下（WebView 不再加载） */
            private boolean blockIfOutside(String url) {
                if (isAllowedNavUrl(url)) return false;
                AppLog.i("engine", "拦下站外导航 host=" + hostOf(url));
                return true;
            }

            /**
             * 老内核语法补丁（方案 A）：拦下私信 JS，把 Chromium 83 解析不了的语法改写后再喂内核。
             * 仅在**内核过旧**时启用（内核够新就直接放过，不做无谓的搬运与改写）。
             */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
                                                                             android.webkit.WebResourceRequest req) {
                if (!mPatchEnabled || req == null) return null;
                String url = req.getUrl() == null ? null : req.getUrl().toString();
                if (url == null || !isPatchTarget(url)) return null;
                try {
                    String js = readPatched(url);
                    if (js == null) return null;
                    return new android.webkit.WebResourceResponse(
                            "application/javascript", "UTF-8",
                            new java.io.ByteArrayInputStream(js.getBytes("UTF-8")));
                } catch (Throwable t) {
                    AppLog.i("engine", "JS 补丁失败，放行原始请求: " + t);
                    return null;   // 改不动就别拦，宁可用原样（保持现有行为）
                }
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    AppLog.i("engine", "主文档加载失败 code=" + err.getErrorCode()
                            + " " + err.getDescription() + " url=" + req.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest req,
                                            android.webkit.WebResourceResponse resp) {
                if (req != null && req.isForMainFrame()) {
                    AppLog.i("engine", "主文档 HTTP " + resp.getStatusCode() + " url=" + req.getUrl());
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // 手表内存小，渲染进程被系统回收是常见死法。不接管的话系统会把整个 App 一起杀掉，
                // 用户只看到"闪退"，什么线索都没有。
                AppLog.i("engine", "渲染进程被回收 didCrash=" + (detail != null && detail.didCrash()));
                try {
                    if (view != null) view.destroy();
                } catch (Throwable ignored) {
                }
                mWebView = null;
                mReady = false;
                synchronized (ChatEngine.class) {
                    // ⚠️ 这里在匿名内部类里，this 是 WebViewClient 不是引擎 → 必须写 ChatEngine.this
                    if (sInstance == ChatEngine.this) sInstance = null;   // 下次 getInstance 重建
                }
                if (mListener != null) {
                    mListener.onEngineError("渲染进程被系统回收（手表内存不足），已重置引擎，请重进本页");
                }
                return true;   // 已处理，别让系统连 App 一起杀
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mReady = true;
                // 只在白名单域注入桥（审计 H4-c）：原来无条件注入，等于把 AndroidBridge
                // 送到任何被导航到的页面上。不是自家域就只记一笔，不注入。
                if (isAllowedNavUrl(url)) {
                    injectBridge();
                } else {
                    AppLog.i("engine", "非白名单域，跳过桥注入 host=" + hostOf(url));
                }
                // SPA 换路由会让同一个文档反复回调 onPageFinished；桥自带重入保护，但原生侧
                // 若照样跑完整流程，就会重复通知 onEngineReady → 各页重复拉数据、auth 连发多条。
                // 用桥生成的文档令牌判重：同文档只处理一次，真换页/刷新令牌必然变。
                mWebView.evaluateJavascript("window.__dywatch_doc||''", new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String raw) {
                        String token = raw == null ? "" : raw.replace("\"", "");
                        if (token.isEmpty()) return;   // 桥没起来（异常页/登录墙），不做无意义的通知
                        if (token.equals(mDocToken)) {
                            AppLog.i("engine", "同文档重复 onPageFinished，忽略");
                            return;
                        }
                        mDocToken = token;
                        AppLog.i("engine", "页面加载完成: " + url);
                        if (mListener != null) mListener.onEngineReady();
                        // 登录墙检测：页面出"扫码登录"= 会话没生效（cookie 丢失/过期），明确报错而不是装死
                        runJs("ChatBridge.checkAuth();");
                        // 未就绪期间暂存的动作补发（跨页续跑由桥 sessionStorage 自续，勿重复）
                        tryRunPending();
                    }
                });
            }
        });
        // 载入前把备份会话 cookie 回写 CookieStore（幂等，只补缺失键）
        com.dywatch.app.login.LoginManager.restoreToCookieManager(ctx);
        mWebView.loadUrl(homeUrl);
    }

    /**
     * 决定是否启用 JS 语法补丁：**只在内核确实过旧时开**。
     * 内核够新还去拦截改写，是白白增加一次下载与一次遍历，还可能引入偏差。
     */
    private void setupJsPatch() {
        try {
            String v = null;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                if (pi != null) v = pi.versionName;
            }
            LegacyKernel.Kernel k = LegacyKernel.from(v, "");
            boolean tooOld = k.known() && !k.supportsIm();
            mPatchEnabled = tooOld;
            if (tooOld) {
                File dir = new File(mCtx.getCacheDir(), "js_patch");
                mPatchCache = new JsPatchCache(dir);
                // 顺手清掉过期产物（2026-10-01）：旧补丁版本的文件永远不会再被读到，
                // 真机实测 v2+v3 两份共 ~13MB（同一个 4.9MB bundle 存了两遍）→ 白占手表空间
                int pruned = mPatchCache.pruneStale(PATCH_CACHE_MAX_BYTES);
                AppLog.i("engine", "内核过旧（Chromium " + k.major + " < "
                        + LegacyKernel.MIN_CHROME_FOR_IM + "）→ 启用私信 JS 语法补丁"
                        + (pruned > 0 ? "（清理过期缓存 " + pruned + " 个文件）" : ""));
            }
        } catch (Throwable t) {
            mPatchEnabled = false;   // 拿不到内核信息就不冒险改
            AppLog.i("engine", "JS 补丁初始化失败，按默认放行: " + t);
        }
    }

    /**
     * 引擎环境自述（2026-09-30 加）：手表上"私信总是失败"时，最先要回答的问题是
     * **这台设备到底有没有可用的 WebView、内核多老**——因为私信 100% 跑在 WebView 里，
     * 而引擎会把 UA 改成 PC 版，等于拿同一份现代前端 bundle 去喂可能很老的内核。
     */
    private void logWebViewEnv() {
        try {
            String pkg = "未知";
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
                pkg = (pi == null) ? "未安装/未启用" : (pi.packageName + " v" + pi.versionName);
            }
            String defUa = "";
            try {
                defUa = android.webkit.WebSettings.getDefaultUserAgent(mCtx);
            } catch (Throwable ignored) {
            }
            AppLog.i("engine", "WebView内核=" + pkg + " | 默认UA=" + defUa + " | 屏="
                    + mCtx.getResources().getDisplayMetrics().widthPixels + "x"
                    + mCtx.getResources().getDisplayMetrics().heightPixels
                    + "@" + mCtx.getResources().getDisplayMetrics().densityDpi + "dpi");
        } catch (Throwable t) {
            AppLog.i("engine", "环境自述失败: " + t);
        }
    }

    /**
     * 允许引擎停留/导航的域名（2026-10-02，审计 H4-a）。
     * 只放抖音自家域：主站 + 同族登录/风控/接口域。**不放任何第三方**——
     * 引擎里带着真实登录态，且挂着 JS 桥，它不该出现在别人家的页面上。
     */
    private static final String[] ALLOWED_HOST_SUFFIX = {
            "douyin.com",       // 主站与 sso/login 等同族子域
            "snssdk.com",       // 风控/接口域（页面跳转链路上出现过）
            "bytedance.com",    // 风控域
            "amemv.com",        // 抖音早期域名，部分重定向仍指向它
    };

    static boolean isAllowedNavUrl(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return false;
        for (String suffix : ALLOWED_HOST_SUFFIX) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }

    /** 只取 host（日志里不写完整 URL：query 可能含搜索词等用户内容） */
    static String hostOf(String url) {
        if (url == null) return "";
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.US);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 触摸盾：吃掉所有触摸，永不透传给引擎（审计 H4-b）。
     *
     * 为什么用"容器拦截"而不是给 WebView 设 setClickable(false)：
     * WebView 的 onTouchEvent 对多数事件都返回 true（它自己就是个滚动容器），
     * 靠 clickable 挡不住；而 onInterceptTouchEvent 返回 true 是 ViewGroup 契约里
     * 最硬的"到此为止"——子视图连 DOWN 都收不到。
     * 引擎的全部操作都走 JS 注入（element.click()），本来就不需要真手指，所以零功能损失。
     */
    private static final class TouchShield extends android.widget.FrameLayout {
        TouchShield(android.content.Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            return true;   // 拦下：不发给子视图（WebView）
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            return true;   // 自己消费：父容器认为这次手势有主了，不会再去找别的兄弟
        }
    }

    /** 注入 JS 桥（幂等）。注入前先放一个本文档专用的随机密钥，桥的每条事件都要带回它。 */
    private void injectBridge() {
        try {
            String key = Long.toHexString(new java.util.Random().nextLong())
                    + Long.toHexString(System.nanoTime());
            String js = readAsset("chat_bridge.js");
            mBridgeKey = key;
            mWebView.evaluateJavascript("window.__dywatch_key=" + org.json.JSONObject.quote(key) + ";\n" + js, null);
        } catch (Exception e) {
            AppLog.i("engine", "桥注入失败: " + e);
        }
    }

    /** 发送文本消息（经 JS 桥驱动页面输入并发送） */
    public void sendText(final String text) {
        runJs("ChatBridge.sendText(" + org.json.JSONObject.quote(text) + ");");
    }

    /** 拉取消息（结果经 onMessages 回调） */
    public void fetchMessages() {
        runJs("ChatBridge.fetchMessages();");
    }

    /** 拉取会话列表（结果经 onConversations 回调） */
    public void fetchConversations() {
        runJs("ChatBridge.fetchConversations();");
    }

    /** 登录态检查（结果经 onAuth 回调） */
    public void checkAuth() {
        runJs("ChatBridge.checkAuth();");
    }

    /** 进入指定会话（按会话名在页面会话列表中点击进入） */
    public void openConversation(String name) {
        runJs("ChatBridge.openConversation(" + org.json.JSONObject.quote(name == null ? "" : name) + ");");
    }

    /** 返回会话列表 */
    public void backToList() {
        runJs("ChatBridge.backToList();");
    }

    /**
     * 引擎归位：点赞/收藏/评论会把这唯一的全局引擎导航到 /video/*，若不送回 /chat，
     * 聊天页与会话列表页的 DOM 抓取会永久空转（实测：归位后 SPA 自动恢复会话列表）。
     * 导航发生在新页面加载完成时经 onEngineReady 回调，页面侧无需额外轮询。
     */
    public void ensureImHome() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mWebView == null || !mReady) return; // 未就绪=构造时就在 /chat，无需归位
                mWebView.evaluateJavascript("ChatBridge.ensureImHome();", null);
            }
        });
    }

    /** 页面销毁时解除监听（单例引擎继续存活） */
    public void setListenerDetached(Listener l) {
        if (mListener == l) mListener = null;
    }

    // ---- 引擎宿主窗口（真视口）----
    // 背景：引擎 WebView 从来没用加进窗口树 → 0×0 视口（实测 innerWidth/innerHeight/clientWidth
    // 全为 0）。很多页面行为因此不对：推荐流“可见才加载”直接不发请求、沉浸式规则把
    // 交互区藏掉、元素 rect 乱跳。解法：把它挂到当前页面内容层【底下】（index 0）。
    // ⚠️ 不能用 View.INVISIBLE：那会被 Chromium 报成 visibilityState=hidden，页面反而不加载；
    //    也不不能用 GONE（不参与测量＝回到 0×0）。靠宿主页不透明背景盖住它。

    private android.app.Activity mHost;

    /** 把引擎挂到该 Activity 的内容层底下（获得真实尺寸，用户看不见、也点不到） */
    public static void attachTo(android.app.Activity a) {
        final ChatEngine e = sInstance;
        if (e == null || a == null || a.isFinishing() || e.mWebView == null) return;
        final android.view.ViewGroup content =
                (android.view.ViewGroup) a.findViewById(android.R.id.content);
        if (content == null) return;
        if (e.mHost == a && e.mShield.getParent() == content) return;
        e.detachFromView();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        content.addView(e.mShield, 0, lp); // index 0 → 被页面根布局盖住（触摸也被触摸盾吃掉）
        e.mHost = a;
        AppLog.i("engine", "引擎已挂载获得真视口");
    }

    /** 页面 onPause/onDestroy 时调用：只在当前宿主就是它时才摘（防回前台时抢动） */
    public static void detachFrom(android.app.Activity a) {
        final ChatEngine e = sInstance;
        if (e == null || a == null || e.mHost != a) return;
        e.detachFromView();
        e.mHost = null;
    }

    private void detachFromView() {
        if (mShield != null && mShield.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) mShield.getParent()).removeView(mShield);
        }
    }

    /** 引擎当前页面 URL（主线程读，供归位判断） */
    public String currentUrl() {
        return mWebView == null ? "" : mWebView.getUrl();
    }

    /** 一次性动作：未就绪先排队，就绪后按序补发（跨页自续由桥 sessionStorage 负责） */
    private void runAction(String js) {
        mPendingJs.addLast(js);
        tryRunPending();
    }

    private void tryRunPending() {
        if (!mReady) return;
        while (!mPendingJs.isEmpty()) runJs(mPendingJs.pollFirst());
    }

    /** 点赞/取消点赞（复用引擎开视频页，点页面自带按钮；页面 SDK 承担全部风控） */
    public String likeVideo(String awemeId, boolean want, ActionListener cb) {
        String reqId = registerAction(cb);
        runAction("ChatBridge.likeVideo(" + org.json.JSONObject.quote(awemeId) + "," + want
                + "," + org.json.JSONObject.quote(reqId) + ");");
        return reqId;
    }

    /** 收藏/取消收藏 */
    public String collectVideo(String awemeId, boolean want, ActionListener cb) {
        String reqId = registerAction(cb);
        runAction("ChatBridge.collectVideo(" + org.json.JSONObject.quote(awemeId) + "," + want
                + "," + org.json.JSONObject.quote(reqId) + ");");
        return reqId;
    }

    /** 拉取视频评论（结果经 onComments 回调） */
    public void fetchComments(String awemeId) {
        runAction("ChatBridge.fetchComments(" + org.json.JSONObject.quote(awemeId) + ");");
    }

    /** 评论「加载更多」：引擎把评论区滚一页，新渲染出来的经 onCommentsMore 追加回来 */
    public void loadMoreComments(String awemeId) {
        runAction("ChatBridge.loadMoreComments(" + org.json.JSONObject.quote(awemeId) + ");");
    }

    /** 发表视频评论（结果经 cb 回调） */
    public String sendComment(String awemeId, String text, ActionListener cb) {
        String reqId = registerAction(cb);
        runAction("ChatBridge.sendComment(" + org.json.JSONObject.quote(awemeId) + ","
                + org.json.JSONObject.quote(text) + "," + org.json.JSONObject.quote(reqId) + ");");
        return reqId;
    }

    // ---- 老内核语法补丁（方案 A）----
    // 手表 WebView 是 Chromium 83，抖音私信微前端（pcim）用了 83 解析不了的语法，
    // 整块模块不执行 → 会话列表永远空。这里把 JS 拦下来改写后再喂内核。
    // 只在内核确实过旧时启用，且只处理私信相关 JS（不碰其它资源）。

    /** 是否启用补丁（内核过旧时才开） */
    private boolean mPatchEnabled;
    /** 补丁是否真的改写过至少一个文件（用于把"内核过旧"的锅摘掉） */
    private volatile boolean mPatchApplied;

    /** 语法补丁是否已生效（会话页据此不再把失败归咎于内核） */
    public boolean isPatchApplied() { return mPatchApplied; }

    private JsPatchCache mPatchCache;

    /**
     * 补丁缓存总量上限（2026-10-01）：当前版本的一套产物约 6.6MB（最大那个 bundle 4.9MB），
     * 留 24MB 够两三轮兜底，再多就是站方换 bundle 留下的死重量了。
     */
    private static final long PATCH_CACHE_MAX_BYTES = 24L * 1024 * 1024;

    /** 私信模块 JS 的 URL 特征（按目录匹配，不写死 hash——站方一更新 hash 就变） */
    private static boolean isPatchTarget(String url) {
        return url.contains("/pcim/static/js/") && url.endsWith(".js");
    }

    /** 取改写后的 JS：优先缓存；未命中则下载→改写→落盘 */
    private String readPatched(String url) throws Exception {
        if (mPatchCache != null) {
            String cached = mPatchCache.load(url);
            if (cached != null) {
                AppLog.i("engine", "JS 补丁命中缓存: " + tail(url));
                // ⚠️ 2026-10-01 修（用户反馈"提示说脚本解析失败，几秒后却加载出来了"）：
                //    缓存里存的就是**改写后**的 JS —— 命中缓存等于补丁正在生效，必须置位。
                //    老代码只在"下载+改写"那条路置 mPatchApplied，于是第二次以后进页面
                //    isPatchApplied() 恒为 false → 会话页把失败归咎"内核过旧、脚本解析失败"，
                //    而实际上补丁跑得好好的。一个漏赋值，造成一句假话。
                mPatchApplied = true;
                return cached;
            }
        }
        String raw = download(url);
        if (raw == null || raw.isEmpty()) {
            AppLog.i("engine", "JS 补丁：下载为空 " + tail(url));
            return null;
        }
        // 诊断：记下原始长度与前 60 字符，便于确认"拿到的到底是不是真文件"
        AppLog.i("engine", "JS 补丁原始 " + tail(url) + " 长度=" + raw.length()
                + " 头=" + raw.substring(0, Math.min(60, raw.length())).replace("\n", "\\n"));
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(raw);
        if (r.changed) mPatchApplied = true;
        AppLog.i("engine", "JS 补丁改写 " + tail(url) + "：私有 " + r.privateId
                + " / ??= " + r.logical + " / 类字段 " + r.classField);
        if (mPatchCache != null) mPatchCache.save(url, r.js);
        return r.js;
    }

    private static String download(String url) throws Exception {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", DouyinApi.UA);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code != 200) return null;
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String tail(String url) {
        if (url == null) return "";
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(i + 1) : url;
    }

    /** JS → Java 回调入口（由 chat_bridge.js 调用，经 JavascriptInterface）。
     *  ⚠️ 此回调在 WebView JavaBridge 线程，更新 UI 必须切主线程（真机踩过 CalledFromWrongThreadException）。 */
    @android.webkit.JavascriptInterface
    public void onBridgeEvent(final String json) {
        // 密钥校验（2026-10-02，代码审计 H4-c）：addJavascriptInterface 的对象在**所有框架**里都可见，
        // 页面里任何第三方 iframe 都能调 onBridgeEvent 伪造"点赞成功/评论已发"。
        // 桥在注入时被塞进一个只存在于**本文档**的随机密钥，事件必须带着它；
        // 跨域 iframe 读不到主文档的变量，于是伪造这条路被堵上。
        if (!hasValidKey(json)) {
            AppLog.i("engine", "桥事件密钥不符，已丢弃（可能来自页面内的第三方框架）");
            return;
        }
        // 脱敏后再落盘/进 logcat（审计 H3：原来整条 JSON 原样写，私信正文与 uid 全进去）
        AppLog.i("engine", "桥事件: " + BridgeLog.summarize(json));
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                dispatchBridgeEvent(json);
            }
        });
    }

    /** 事件里的 doc 字段是否等于本引擎本次注入的文档密钥 */
    private boolean hasValidKey(String json) {
        String key = mBridgeKey;
        if (key == null || key.isEmpty()) return false;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (!el.isJsonObject()) return false;
            com.google.gson.JsonElement d = el.getAsJsonObject().get("doc");
            return d != null && d.isJsonPrimitive() && key.equals(d.getAsString());
        } catch (Throwable t) {
            return false;
        }
    }

    private void dispatchBridgeEvent(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String type = o.optString("type");
            if ("messages".equals(type)) {
                List<ChatMessage> list = new ArrayList<>();
                org.json.JSONArray arr = o.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject it = arr.getJSONObject(i);
                        ChatMessage cm = new ChatMessage(
                                "out".equals(it.optString("dir")) ? ChatMessage.OUT : ChatMessage.IN,
                                it.optString("text"),
                                it.optLong("time"));
                        cm.timeText = it.optString("time");
                        list.add(cm);
                    }
                }
                if (mListener != null) mListener.onMessages(list);
            } else if ("conversations".equals(type)) {
                List<com.dywatch.app.chat.model.Conversation> list = new ArrayList<>();
                org.json.JSONArray arr = o.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject it = arr.getJSONObject(i);
                        list.add(new com.dywatch.app.chat.model.Conversation(
                                it.optString("key"),
                                it.optString("name"),
                                it.optString("lastMsg"),
                                it.optString("time")));
                    }
                }
                if (mListener != null) mListener.onConversations(list);
            } else if ("comments".equals(type) || "commentsMore".equals(type)) {
                List<com.dywatch.app.chat.model.Comment> list = new ArrayList<>();
                org.json.JSONArray arr = o.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject it = arr.getJSONObject(i);
                        list.add(new com.dywatch.app.chat.model.Comment(
                                it.optString("name"),
                                it.optString("text"),
                                it.optString("time"),
                                it.optString("likes")));
                    }
                }
                if (mListener != null) {
                    if ("comments".equals(type)) mListener.onComments(list);
                    else mListener.onCommentsMore(list, o.optBoolean("atEnd", false));
                }
            } else if ("auth".equals(type)) {
                boolean ok = o.optBoolean("ok", false);
                String msg = ok ? ("已登录 " + o.optString("user", "")) : o.optString("message", "");
                if (mListener != null) mListener.onAuth(ok, msg);
            } else if ("sent".equals(type)) {
                // 发送结果（仅记日志；页面消息流为准）
                AppLog.i("engine", "发送结果: ok=" + o.optBoolean("ok") + " " + o.optString("note", ""));
            } else if ("action".equals(type)) {
                String phase = o.optString("phase", "");
                // 过程事件（navigating=跳页、submitting=正在试提交钮）不是结果，
                // 只有无 phase 字段的才当终态上报（否则过程事件会被当成失败，把乐观更新误回滚）
                if (phase.length() == 0) {
                    boolean ok = o.optBoolean("ok", false);
                    String detail = o.optString("detail", o.optString("after", ""));
                    AppLog.i("engine", "互动结果: " + o.optString("action") + " ok=" + ok
                            + " " + o.optString("before", "") + "→" + o.optString("after", "") + " " + detail);
                    // 按 reqId 精确投递（审计 M17）：拿到就撤登记，一次性；
                    // 没有 reqId（或页面已撤销登记）就只记日志，绝不猜着投给"当前那一个"回调。
                    String reqId = o.optString("reqId", "");
                    ActionListener cb = reqId.isEmpty() ? null : mActionCallbacks.remove(reqId);
                    if (cb != null) {
                        cb.onActionResult(o.optString("action"), ok, detail);
                    } else {
                        AppLog.i("engine", "互动结果无接收方（reqId="
                                + (reqId.isEmpty() ? "缺失" : reqId) + "），仅记录");
                    }
                }
            } else if ("opened".equals(type) || "back".equals(type)) {
                AppLog.i("engine", "导航事件: " + type);
            } else if ("home".equals(type)) {
                AppLog.i("engine", o.optBoolean("ok") ? "引擎已在私信页" : "引擎归位：导航回 /chat");
            } else if ("error".equals(type)) {
                if (mListener != null) mListener.onEngineError(o.optString("message"));
            }
        } catch (Exception e) {
            if (mListener != null) mListener.onEngineError("桥事件解析失败: " + e);
        }
    }

    private void runJs(final String script) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mWebView == null) return;
                if (!mReady) {
                    // 等待 ≠ 失败：走等待回调（页面显示"正在启动通道…"+ 转圈），不再报错
                    if (mListener != null) mListener.onEngineWaiting("引擎尚未就绪");
                    return;
                }
                mWebView.evaluateJavascript(script, null);
            }
        });
    }

    private String readAsset(String name) throws Exception {
        InputStream is = mCtx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /**
     * 退出登录时调用（2026-10-01）：把单例引擎整个销毁，下次谁要谁重建。
     *
     * 为什么不能只清 cookie：**已经加载在内存里的页面还带着旧会话**（JS 侧状态、已建立的连接），
     * 只删 cookie 不重建，页面照旧能用 —— 用户会以为"退出登录没生效"。
     * 主线程执行：WebView.destroy() 必须在主线程，且要先把宿主视图摘下来（destroy() 内部做了）。
     */
    public static void resetForLogout() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    if (sInstance != null) {
                        sInstance.destroy();
                        com.dywatch.app.util.AppLog.i("engine", "退出登录：引擎已销毁，下次重建");
                    }
                } catch (Throwable t) {
                    com.dywatch.app.util.AppLog.i("engine", "退出登录销毁引擎失败: " + t);
                }
            }
        });
    }

    public void destroy() {
        mReady = false;
        mDocToken = "";
        mListener = null;
        detachFromView();
        mHost = null;
        synchronized (ChatEngine.class) {
            sInstance = null;
        }
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
    }

    /** 供 chat_bridge.js 通过 addJavascriptInterface 挂载的对象名 */
    public static final String BRIDGE_NAME = "AndroidBridge";
}
