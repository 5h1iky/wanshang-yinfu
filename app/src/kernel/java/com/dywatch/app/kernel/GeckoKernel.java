package com.dywatch.app.kernel;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;

import com.dywatch.app.login.LoginManager;
import com.dywatch.app.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内核构建（kernel flavor）的 GeckoView 引擎：**在本进程里跑真浏览器内核**，不装第二个 App。
 *
 * 为什么这样能跑、而「下载内核包再跑」不行：GeckoView 必须 fork+exec 内容子进程，
 * 而 SELinux 只允许执行 **APK 的 lib 目录**（apk_data_file），不允许执行 App 私有目录
 * （app_data_file：实测 `execute_no_trans` 被拒，dlopen 却放行）。所以内核必须**随 APK 安装**。
 *
 * 0.9.0 的分工（小步路径第 1 步，见 调研\15）：
 *   - **点赞/收藏** → 走本引擎（Gecko 渲染 + 页面自带按钮 + 复用同一份 chat_bridge.js）
 *   - 私信/聊天 → 仍走系统 WebView；桥整体迁移（K3）留到内核版下一篇
 *
 * 扩展：必须是**内置扩展**（`ensureBuiltIn` + assets），未签名 XPI 走 install() 会被拒/永久挂起。
 * 构建期由 gradle 的 syncKernelExt 把 `assets/chat_bridge.js` 拷进扩展目录，保证只有一份真源。
 */
public final class GeckoKernel {

    private static final String TAG = "kernel";
    private static final String CHAT_URL = "https://www.douyin.com/chat";
    private static final String HOME_URL = "https://www.douyin.com/";
    /** 内置扩展在 assets 里的位置（gradle 的 syncKernelExt 生成到 assets/kext/） */
    private static final String EXT_LOCATION = "resource://android/assets/kext/";
    private static final String EXT_ID = "kernel@dywatch.local";
    private static final String NATIVE_APP = "browser";

    private static GeckoKernel sInst;

    private final Context mApp;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Map<String, KernelSeam.Callback> mPending = new HashMap<>();

    private GeckoRuntime mRuntime;
    private GeckoSession mSession;
    private GeckoView mView;
    private volatile WebExtension mExt;
    /** content script 的端口（下发指令） */
    private volatile WebExtension.Port mContentPort;
    /** background 的端口（灌 cookie） */
    private volatile WebExtension.Port mBgPort;

    private volatile boolean mStarted;
    private volatile boolean mCookiesDone;
    private int mSeq;

    private GeckoKernel(Context ctx) {
        mApp = ctx.getApplicationContext();
    }

    /** 入口（由 {@link KernelSeam} 反射调用）。返回 null = 未就绪，调用方回落 WebView。 */
    public static synchronized String action(Context ctx, String kind, String awemeId,
                                             boolean want, KernelSeam.Callback cb) {
        if (sInst == null) sInst = new GeckoKernel(ctx);
        return sInst.submit(ctx, kind, awemeId, want, cb);
    }

    /**
     * 调试开关：把内核那层 GeckoView 显示出来/收回去（由 {@link KernelSeam#debugVisible} 反射调用）。
     *
     * 为什么要这个：内核页和 App 自己的界面是叠在一起的两层，平时内核层是全透明的，用户看不到
     * 里面到底渲染成什么样。排查"页面空白/登录墙/风控降级"时，肉眼看一眼比十行日志管用。
     */
    public static synchronized void setDebugVisible(Context ctx, boolean visible) {
        if (sInst == null) sInst = new GeckoKernel(ctx);
        sInst.applyDebugVisible(ctx, visible);
    }

    private void applyDebugVisible(Context ctx, boolean visible) {
        // 显示时若内核还没起来，就顺手把它启动（用户不该为了看内核先去点一次赞）
        if (visible && !mStarted) {
            ensureStarted(ctx);
        }
        if (mView == null) {
            AppLog.i(TAG, "内核视图还没创建（先点一次赞让它启动，或等 2 秒）");
            return;
        }
        ViewGroup root = null;
        try {
            if (ctx instanceof Activity) {
                root = ((Activity) ctx).findViewById(android.R.id.content);
            }
        } catch (Throwable ignored) {
        }
        if (visible) {
            mView.setAlpha(1f);
            mView.setClickable(true);
            mView.setFocusable(true);
            if (mView.getParent() == null && root != null) {
                root.addView(mView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            mView.bringToFront();
            // 显示出来后**不强制跳转**：内核里当前是什么页面就显示什么（用户可能正停在视频页/私信页）。
            // 另外把会话标记为"活跃 + 有焦点"：GeckoView 在不可见/被遮挡时会把页面当后台标签，
            // 媒体与部分请求会被节流（实测：后台时视频页停在"视频数据加载中"）。
            try {
                mSession.setActive(true);
                mSession.setFocused(true);
            } catch (Throwable ignored) {
            }
            AppLog.i(TAG, "内核页面已显示（setActive=true）");
        } else {
            mView.setAlpha(0f);
            mView.setClickable(false);
            mView.setFocusable(false);
            try {
                mSession.setFocused(false);
                mSession.setActive(false);
            } catch (Throwable ignored) {
            }
            if (root != null && mView.getParent() == root) {
                root.removeView(mView);
                root.addView(mView, 0, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            AppLog.i(TAG, "内核页面已收回（透明、最底层、setActive=false）");
        }
    }

    private synchronized String submit(Context ctx, String kind, String awemeId,
                                       boolean want, KernelSeam.Callback cb) {
        if (!ensureStarted(ctx) || mContentPort == null) {
            AppLog.i(TAG, "内核未就绪（" + state() + "），本次回落 WebView");
            return null;
        }
        String reqId = "k" + (++mSeq);
        mPending.put(reqId, cb);
        String fn;
        if ("auth".equals(kind)) {
            fn = "checkAuth";
        } else if ("collect".equals(kind)) {
            fn = "collectVideo";
        } else {
            fn = "likeVideo";
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("cmd", "invoke");
            msg.put("fn", fn);
            msg.put("reqId", reqId);
            // ⚠️ 用字符串传参：直接 put 数组对象在 Android 的 org.json 上会抛
            //    "Array has incompatible type: class [Ljava.lang.String;"（实测踩过）
            msg.put("argsJson", new JSONArray().put(awemeId).put(want).put(reqId).toString());
            mContentPort.postMessage(msg);
            AppLog.i(TAG, "内核下发 " + fn + " aweme=" + awemeId + " want=" + want + " req=" + reqId);
            return reqId;
        } catch (Throwable t) {
            mPending.remove(reqId);
            AppLog.i(TAG, "内核下发失败: " + t);
            return null;
        }
    }

    /** 内核聊天验证入口：只做读列表/读消息/开会话/发送，结果打日志，不影响现有 WebView 聊天。 */
    public static synchronized void chat(Context ctx, String kind, String name, String text) {
        if (sInst == null) sInst = new GeckoKernel(ctx);
        sInst.chatSubmit(ctx, kind, name, text);
    }

    private void chatSubmit(Context ctx, String kind, String name, String text) {
        if (!ensureStarted(ctx) || mContentPort == null) {
            AppLog.i(TAG, "聊天探测：内核未就绪（" + state() + "）");
            return;
        }
        String fn;
        String args;
        try {
            if ("chatFetch".equals(kind)) {
                if (mBgPort == null) {
                    AppLog.i(TAG, "聊天网络探测：background 端口未就绪");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "chatFetch");
                mBgPort.postMessage(cmd);
                AppLog.i(TAG, "聊天网络探测：由 background 发只读请求");
                return;
            }
            if ("chatReload".equals(kind)) {
                mUi.post(new Runnable() {
                    @Override public void run() { mSession.reload(); }
                });
                AppLog.i(TAG, "聊天探测：重载 Gecko 私信页");
                return;
            }
            if ("chatNet".equals(kind)) {
                fn = "chatNet";
                args = "[]";
            } else if ("chatProbe".equals(kind)) {
                fn = "probeChat";
                args = "[]";
            } else if ("chatOpen".equals(kind)) {
                fn = "openConversation";
                args = new JSONArray().put(name).toString();
            } else if ("chatMessages".equals(kind)) {
                fn = "fetchMessages";
                args = "[]";
            } else if ("chatSend".equals(kind)) {
                fn = "sendText";
                args = new JSONArray().put(text).toString();
            } else {
                fn = "fetchConversations";
                args = "[]";
            }
            JSONObject msg = new JSONObject();
            msg.put("cmd", "invoke");
            msg.put("fn", fn);
            msg.put("argsJson", args);
            mContentPort.postMessage(msg);
            AppLog.i(TAG, "聊天探测下发 " + fn + " name=" + name);
        } catch (Throwable t) {
            AppLog.i(TAG, "聊天探测下发失败: " + t);
        }
    }

    private String state() {
        if (!mStarted) return "未启动";
        if (mExt == null) return "等扩展";
        if (!mCookiesDone) return "等 cookie";
        if (mContentPort == null) return "等 content";
        return "就绪";
    }

    // ---------- 启动（只做一次） ----------

    private synchronized boolean ensureStarted(Context ctx) {
        if (mStarted) return true;
        mStarted = true;
        try {
            mRuntime = GeckoRuntime.create(mApp);
            // ⚠️ 必须伪装桌面 UA：桥的 DOM 选择器是按**桌面版**抖音页校准的（WebView 线同样如此）。
            //    Gecko 默认移动 UA 会把 /video/<id> 重定向到 m.douyin.com/share/video/...，
            //    那里根本没有 [data-e2e=video-player-digg] 这类按钮（实测踩过）。
            //    gv100 的 GeckoSession 没有 setUserAgentOverride，只能走 Settings.Builder。
            mSession = new GeckoSession(new org.mozilla.geckoview.GeckoSessionSettings.Builder()
                    .userAgentOverride(com.dywatch.app.net.DouyinApi.UA)
                    .build());
            mSession.open(mRuntime);

            mView = new GeckoView(mApp);
            mView.setSession(mSession);
            attachHidden(ctx);

            if (mRuntime.getWebExtensionController() == null) {
                AppLog.i(TAG, "getWebExtensionController() == null（引擎没起来）");
                return true;
            }
            // 内置扩展：ensureBuiltIn 幂等（重启不会叠份），且**不需要签名**
            mRuntime.getWebExtensionController().ensureBuiltIn(EXT_LOCATION, EXT_ID).then(
                    new GeckoResult.OnValueListener<WebExtension, Void>() {
                        @Override
                        public GeckoResult<Void> onValue(WebExtension ext) {
                            mExt = ext;
                            AppLog.i(TAG, "扩展就绪 id=" + (ext == null ? "null" : ext.id));
                            bindDelegate(ext);
                            return null;
                        }
                    },
                    new GeckoResult.OnExceptionListener<Void>() {
                        @Override
                        public GeckoResult<Void> onException(Throwable e) {
                            AppLog.i(TAG, "扩展安装失败: " + e);
                            return null;
                        }
                    });

            // ⚠️ 不能干等扩展回调再开页面：background 要有页面/会话才真正跑起来，
            //    否则会变成"等 bgReady → 等页面 → 等 bgReady"的死锁（实测停在"扩展包"就没了）。
            mUi.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mSession != null) mSession.loadUri(CHAT_URL);
                }
            }, 1200);
            startHeartbeat();
            return true;
        } catch (Throwable t) {
            AppLog.i(TAG, "内核启动失败: " + t);
            mStarted = false;
            return false;
        }
    }

    private void bindDelegate(WebExtension ext) {
        if (ext == null) return;
        ext.setMessageDelegate(new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onPortMessage(Object message, WebExtension.Port p) {
                        handle(message, p);
                    }
                    // ⚠️ gv100 的 PortDelegate 没有 onPortDisconnect（实测编译报"不会覆盖"），
                    //    端口失效只能靠：① 每次整页导航后 content 会重发 contentReady 覆盖；
                    //    ② 下发时 try/catch，失败即返回 null 回落 WebView。
                });
            }

            @Override
            public GeckoResult<Object> onMessage(String nativeApp, Object message,
                                                 WebExtension.MessageSender sender) {
                handle(message, null);
                return null;
            }
        }, NATIVE_APP);
    }

    /** 每 5 秒报一次内核状态（排查用，只进 AppLog） */
    private void startHeartbeat() {
        mUi.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mStarted) {
                    AppLog.i(TAG, "心跳 " + state() + " ext=" + (mExt != null)
                            + " bg=" + (mBgPort != null) + " content=" + (mContentPort != null));
                }
                mUi.postDelayed(this, 5000);
            }
        }, 5000);
    }

    /** 引擎视图挂到内容层最底下：不可见、不抢点击，但**参与布局**（0×0 会让页面不渲染） */
    private void attachHidden(Context ctx) {
        try {
            if (!(ctx instanceof Activity)) return;
            Activity a = (Activity) ctx;
            ViewGroup root = a.findViewById(android.R.id.content);
            if (root == null || mView.getParent() != null) return;
            mView.setAlpha(0f);
            mView.setClickable(false);
            mView.setFocusable(false);
            root.addView(mView, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            AppLog.i(TAG, "挂载隐藏视图失败（不影响 DOM 能力）: " + t);
        }
    }

    // ---------- 消息 ----------

    private void handle(Object raw, WebExtension.Port from) {
        JSONObject msg = asJson(raw);
        if (msg == null) return;
        String t = msg.optString("type", "");
        switch (t) {
            case "bgReady":
                mBgPort = from;
                postCookies();
                break;
            case "pageInfo":
                AppLog.i(TAG, "内核页面体征 " + msg.optString("url")
                        + " nodes=" + msg.optInt("nodes") + " textLen=" + msg.optInt("textLen")
                        + " 登录墙=" + msg.optBoolean("loginWall") + " 有点赞按钮=" + msg.optBoolean("digg")
                        + " cookieCount=" + msg.optInt("cookieCount") + " hasSid=" + msg.optBoolean("hasSid")
                        + " sidLen=" + msg.optInt("sidLen") + " 登录按钮=" + msg.optBoolean("loginBtn")
                        + " 头像=" + msg.optBoolean("avatar") + " 首屏文字=" + msg.optString("head"));
                break;
            case "contentReady":
                mContentPort = from;
                AppLog.i(TAG, "内核 content 就绪 " + msg.optString("url")
                        + " nodes=" + msg.optInt("nodes") + " textLen=" + msg.optInt("textLen")
                        + " 登录墙=" + msg.optBoolean("loginWall") + " 有点赞按钮=" + msg.optBoolean("digg"));
                break;
            case "cookiesDone":
                mCookiesDone = true;
                AppLog.i(TAG, "内核 cookie 完成 n=" + msg.optInt("n") + " ok=" + msg.optInt("ok") + " fail=" + msg.optInt("fail") + " sessionid=" + msg.optBoolean("sessionid") + " 跳过=" + msg.optBoolean("skipped") + " err=" + msg.optString("firstErr"));
                loadChat();
                break;
            case "rawSelf":
                AppLog.i(TAG, "profile/self 原文 status=" + msg.optInt("status") + " len=" + msg.optInt("len")
                        + " head=" + msg.optString("head"));
                break;
            case "auth":
                // 注意用 opt*：Android 的 JSONObject.get() 会抛受检 JSONException
                AppLog.i(TAG, "内核登录态 ok=" + msg.opt("ok") + " user=" + msg.optString("user")
                        + " msg=" + msg.optString("message"));
                break;
            case "action":
                result(msg);
                break;
            case "conversations":
                AppLog.i(TAG, "内核会话 rows=" + msg.optInt("rows") + " tries=" + msg.optInt("tries")
                        + " count=" + msg.optJSONArray("items").length() + " data=" + msg.optString("items"));
                break;
            case "chatProbe":
                AppLog.i(TAG, "内核聊天DOM " + msg.toString());
                break;
            case "opened":
                AppLog.i(TAG, "内核开会话 ok=" + msg.optBoolean("ok") + " name=" + msg.optString("name"));
                break;
            case "messages":
                AppLog.i(TAG, "内核消息 count=" + msg.optJSONArray("items").length()
                        + " data=" + msg.optString("items"));
                break;
            case "sent":
                AppLog.i(TAG, "内核发送 ok=" + msg.optBoolean("ok") + " note=" + msg.optString("note"));
                break;
            case "error":
                AppLog.i(TAG, "内核桥错误 " + msg.optString("message"));
                break;
            case "dyHook":
                AppLog.i(TAG, "内核页面观测 " + summarizeHook(msg.optJSONObject("hook")));
                break;
            default:
                AppLog.i(TAG, "内核桥消息 " + t + " " + msg.toString());
                break;
        }
    }

    /** 把页面观测压成一行：完整 JSON 太长，真机 logcat 会截断且淹掉重点。 */
    private static String summarizeHook(JSONObject h) {
        if (h == null) return "null";
        StringBuilder sb = new StringBuilder();
        JSONArray ws = h.optJSONArray("ws");
        sb.append("ws=");
        if (ws != null) {
            for (int i = 0; i < ws.length() && i < 4; i++) {
                JSONObject o = ws.optJSONObject(i);
                if (o == null) continue;
                sb.append(hostOf(o.optString("u"))).append(':').append(o.optString("s")).append(' ');
            }
        }
        JSONArray xhr = h.optJSONArray("xhr");
        sb.append("| xhr=");
        if (xhr != null) {
            int from = Math.max(0, xhr.length() - 6);
            for (int i = from; i < xhr.length(); i++) {
                JSONObject o = xhr.optJSONObject(i);
                if (o == null) continue;
                String head = o.optString("head");
                sb.append(hostOf(o.optString("u"))).append(" st=").append(o.optInt("st"))
                        .append(" len=").append(o.optInt("len"))
                        .append(" ").append(tagOf(head)).append("; ");
            }
        }
        JSONArray fe = h.optJSONArray("fetch");
        sb.append("| fetch=");
        if (fe != null) {
            int from = Math.max(0, fe.length() - 3);
            for (int i = from; i < fe.length(); i++) {
                JSONObject o = fe.optJSONObject(i);
                if (o == null) continue;
                sb.append(hostOf(o.optString("u"))).append(" st=").append(o.optInt("st"))
                        .append(" ").append(tagOf(o.optString("head"))).append("; ");
            }
        }
        JSONArray err = h.optJSONArray("err");
        sb.append("| err=").append(err == null ? 0 : err.length());
        return sb.toString();
    }

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? "?" : h;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 只留判定用的关键词，避免把整段响应写进日志。 */
    private static String tagOf(String head) {
        if (head == null) return "";
        if (head.contains("用户未登录")) return "未登录";
        if (head.contains("blocked")) return "blocked";
        if (head.contains("NetworkError")) return "网络错误";
        if (head.contains("status_code\":0")) return "ok";
        return head.length() > 30 ? head.substring(0, 30).replace('\n', ' ') : head;
    }

    private static JSONObject asJson(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof Map) return new JSONObject((Map<?, ?>) raw);
        if (raw instanceof String) {
            try {
                return new JSONObject((String) raw);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void postCookies() {
        if (mBgPort == null) return;
        try {
            // ⚠️ 2026-10-25 真机：用 LoginManager 存的那份头去灌 cookie，内核页仍是"会话无效"。
            // 原因：那份头是登录时抓的 `CookieManager.getCookie(url)`，**默认不含 httpOnly**，
            // 而抖音的会话 cookie 恰恰是 httpOnly。这里直接向系统要（getCookie(url, true) 包含 httpOnly），
            // 拿不到才退回存档头。
            String live = null;
            try {
                live = android.webkit.CookieManager.getInstance()
                        .getCookie("https://www.douyin.com");
            } catch (Throwable ignored) {
            }
            String header = (live != null && !live.isEmpty()) ? live : LoginManager.getCookies(mApp);
            List<Map<String, Object>> cookies = CookieJar.fromHeader(header);
            // 只打名字（不打值）：确认会话类 cookie 在不在，判断"是不是 httpOnly 丢了"
            StringBuilder names = new StringBuilder();
            boolean hasSession = false;
            for (Map<String, Object> c : cookies) {
                String n = String.valueOf(c.get("name"));
                names.append(n).append(',');
                if (n.startsWith("sessionid") || n.startsWith("sid_tt") || n.startsWith("sid_guard")) {
                    hasSession = true;
                }
            }
            AppLog.i(TAG, "cookie 名单(" + cookies.size() + ")=" + names
                    + " 含会话cookie=" + hasSession);
            JSONObject msg = new JSONObject();
            msg.put("cmd", "setCookies");
            msg.put("cookies", new JSONArray(cookies));
            mBgPort.postMessage(msg);
            AppLog.i(TAG, "内核注入 cookie " + cookies.size() + " 条（来源="
                    + (live != null && !live.isEmpty() ? "系统CookieManager含httpOnly" : "存档头") + "）");
        } catch (Throwable t) {
            AppLog.i(TAG, "cookie 注入失败: " + t);
            mCookiesDone = true;
            loadChat();
        }
    }

    private void loadChat() {
        mUi.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mSession.loadUri(CHAT_URL);
                } catch (Throwable t) {
                    AppLog.i(TAG, "载入私信页失败: " + t);
                }
            }
        });
    }

    private void result(JSONObject msg) {
        if (msg.has("phase") && !msg.isNull("phase")) return;   // navigating 等中间态不投递
        String reqId = msg.optString("reqId", "");
        boolean ok = msg.optBoolean("ok", false);
        String detail = msg.optString("detail", "");
        AppLog.i(TAG, "内核结果 req=" + reqId + " ok=" + ok + " detail=" + detail);
        KernelSeam.Callback cb;
        synchronized (this) {
            cb = reqId.isEmpty() ? null : mPending.remove(reqId);
        }
        if (cb != null) cb.onResult(ok, detail);
    }
}
