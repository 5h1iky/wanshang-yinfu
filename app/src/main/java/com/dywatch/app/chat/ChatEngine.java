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
    }

    /** 一次性互动动作（点赞/收藏）结果回调 */
    public interface ActionListener {
        void onActionResult(String action, boolean ok, String detail);
    }

    /** 推荐流 id 回调（数据源=PC 精选页，用户 2026-09-26 定案） */
    public interface FeedListener {
        void onFeedIds(java.util.List<String> ids);
    }

    private FeedListener mFeedListener;

    public void setFeedListener(FeedListener l) {
        mFeedListener = l;
    }

    /**
     * 拉一页推荐 id（引擎不在精选页时会先跳页、经 sessionStorage自续）。
     * @param scroll true=先滚到底触发加载更多（翻页）
     */
    public void fetchRecommendFeed(boolean scroll) {
        runAction("ChatBridge.fetchRecommendFeed(" + scroll + ");");
    }

    private ActionListener mActionListener;

    public void setActionListener(ActionListener l) {
        mActionListener = l;
    }

    // ⚠️ 私信入口是 /chat（实测 200+SPA）；/messages 是 404 死路由（调研报告过时勿信）
    private static final String MESSAGES_URL = "https://www.douyin.com/chat";

    /** 推荐流页（PC 精选页；卡片带 data-aweme-id，实测 50 张） */
    public static final String FEED_URL = "https://www.douyin.com/jingxuan";

    /** 单例：会话列表页/会话页共用一个页面引擎（手表性能：WebView 全局只留一个） */
    private static ChatEngine sInstance;

    public static synchronized ChatEngine getInstance(android.content.Context ctx, Listener listener) {
        return getInstance(ctx, listener, null);
    }

    /**
     * @param initialUrl 仅首次创建时生效（引擎只有一个，后续页面靠 fetchRecommendFeed/ensureImHome 切页）；
     *                   传 null = 默认私信页。Feed 页传 FEED_URL 可避开“先加载 /chat 再跳走”的浪费。
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
    private boolean mReady;
    /** 桥在文档里生成的令牌；同一个文档的重复 onPageFinished 靠它判重 */
    private String mDocToken = "";
    /** 未就绪期间暂存的一次性动作（就绪后补发一次；跨页续跑由桥 sessionStorage 负责） */
    private volatile String mPendingJs;

    @SuppressLint("SetJavaScriptEnabled")
    private ChatEngine(android.content.Context ctx, Listener listener, String homeUrl) {
        mCtx = ctx;
        mListener = listener;
        mWebView = new WebView(ctx);
        mWebView.addJavascriptInterface(this, BRIDGE_NAME); // JS → Java 回调
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(DouyinApi.UA);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mReady = true;
                injectBridge();
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

    /** 注入 JS 桥（幂等） */
    private void injectBridge() {
        try {
            String js = readAsset("chat_bridge.js");
            mWebView.evaluateJavascript(js, null);
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

    /** 把引擎挂到该 Activity 的内容层底下（获得真实尺寸，用户看不见、点不到） */
    public static void attachTo(android.app.Activity a) {
        final ChatEngine e = sInstance;
        if (e == null || a == null || a.isFinishing() || e.mWebView == null) return;
        final android.view.ViewGroup content =
                (android.view.ViewGroup) a.findViewById(android.R.id.content);
        if (content == null) return;
        if (e.mHost == a && e.mWebView.getParent() == content) return;
        e.detachFromView();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        content.addView(e.mWebView, 0, lp); // index 0 → 被页面根布局盖住
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
        if (mWebView != null && mWebView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) mWebView.getParent()).removeView(mWebView);
        }
    }

    /** 引擎当前页面 URL（主线程读，供归位判断） */
    public String currentUrl() {
        return mWebView == null ? "" : mWebView.getUrl();
    }

    /** 一次性动作：未就绪先暂存，就绪后补发（只补一次，跨页自续由桥负责） */
    private void runAction(String js) {
        mPendingJs = js;
        tryRunPending();
    }

    private void tryRunPending() {
        String js = mPendingJs;
        if (js == null || !mReady) return;
        mPendingJs = null;
        runJs(js);
    }

    /** 点赞/取消点赞（复用引擎开视频页，点页面自带按钮；页面 SDK 承担全部风控） */
    public void likeVideo(String awemeId, boolean want) {
        runAction("ChatBridge.likeVideo(" + org.json.JSONObject.quote(awemeId) + "," + want + ");");
    }

    /** 收藏/取消收藏 */
    public void collectVideo(String awemeId, boolean want) {
        runAction("ChatBridge.collectVideo(" + org.json.JSONObject.quote(awemeId) + "," + want + ");");
    }

    /** 拉取视频评论（结果经 onComments 回调） */
    public void fetchComments(String awemeId) {
        runAction("ChatBridge.fetchComments(" + org.json.JSONObject.quote(awemeId) + ");");
    }

    /** 评论「加载更多」：引擎把评论区滚一页，新渲染出来的经 onCommentsMore 追加回来 */
    public void loadMoreComments(String awemeId) {
        runAction("ChatBridge.loadMoreComments(" + org.json.JSONObject.quote(awemeId) + ");");
    }

    /** 发表视频评论 */
    public void sendComment(String awemeId, String text) {
        runAction("ChatBridge.sendComment(" + org.json.JSONObject.quote(awemeId) + ","
                + org.json.JSONObject.quote(text) + ");");
    }

    /** JS → Java 回调入口（由 chat_bridge.js 调用，经 JavascriptInterface）。
     *  ⚠️ 此回调在 WebView JavaBridge 线程，更新 UI 必须切主线程（真机踩过 CalledFromWrongThreadException）。 */
    @android.webkit.JavascriptInterface
    public void onBridgeEvent(final String json) {
        AppLog.i("engine", "桥事件: " + json);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                dispatchBridgeEvent(json);
            }
        });
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
                    if (mActionListener != null) {
                        mActionListener.onActionResult(o.optString("action"), ok, detail);
                    }
                }
            } else if ("feed".equals(type)) {
                java.util.List<String> ids = new ArrayList<>();
                org.json.JSONArray arr = o.optJSONArray("ids");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) ids.add(arr.optString(i));
                }
                AppLog.i("engine", "推荐流 id " + ids.size() + " 条（页高=" + o.optInt("docH") + "）");
                if (mFeedListener != null) mFeedListener.onFeedIds(ids);
            } else if ("opened".equals(type) || "back".equals(type)) {
                AppLog.i("engine", "导航事件: " + json);
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
                    if (mListener != null) mListener.onEngineError("通道未就绪");
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
