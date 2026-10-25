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

    private synchronized String submit(Context ctx, String kind, String awemeId,
                                       boolean want, KernelSeam.Callback cb) {
        if (!ensureStarted(ctx) || mContentPort == null) {
            AppLog.i(TAG, "内核未就绪（" + state() + "），本次回落 WebView");
            return null;
        }
        String reqId = "k" + (++mSeq);
        mPending.put(reqId, cb);
        String fn = "like".equals(kind) ? "likeVideo" : "collectVideo";
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
            case "contentReady":
                mContentPort = from;
                AppLog.i(TAG, "内核 content 就绪 " + msg.optString("url"));
                break;
            case "cookiesDone":
                mCookiesDone = true;
                AppLog.i(TAG, "内核 cookie 注入完成 n=" + msg.optInt("n") + " → 重载私信页");
                loadChat();
                break;
            case "action":
                result(msg);
                break;
            default:
                break;
        }
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
            List<Map<String, Object>> cookies = CookieJar.fromHeader(LoginManager.getCookies(mApp));
            JSONObject msg = new JSONObject();
            msg.put("cmd", "setCookies");
            msg.put("cookies", new JSONArray(cookies));
            mBgPort.postMessage(msg);
            AppLog.i(TAG, "内核注入 cookie " + cookies.size() + " 条");
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
