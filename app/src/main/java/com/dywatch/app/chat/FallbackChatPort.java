package com.dywatch.app.chat;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.dywatch.app.chat.model.ChatMessage;
import com.dywatch.app.chat.model.Comment;
import com.dywatch.app.chat.model.Conversation;
import com.dywatch.app.kernel.KernelSeam;
import com.dywatch.app.util.AppLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * kernel 变体的聊天通道包装：优先内核，内核启动失败/超时后自动切回系统 WebView。
 *
 * 为什么需要这一层：
 *   - {@link KernelChatPort} 只证明“类存在”，不能证明 GeckoView 运行时真的起来了；
 *   - 如果扩展/页面起不来，单靠 KernelChatPort 会让聊天页一直转圈；
 *   - 用户装 kernel 版是为了更稳，不是为了让聊天不可用，所以失败必须能回到旧通道。
 *
 * 切换只发生一次：一旦进入 WebView 通道，本次页面生命周期内不再尝试内核，避免
 * 两边来回抢同一个聊天页。下次重新进页面会重新创建 FallbackChatPort，再给内核一次机会。
 */
final class FallbackChatPort implements ChatPort {

    /** 手表上 Gecko 冷启动比手机慢，给 35 秒；超过这个时间还没 ready 才回落。 */
    private static final long KERNEL_READY_TIMEOUT_MS = 35_000L;

    /**
     * 光“引擎 ready”不算数：2026-10-31 手表实测，内核扩展能 ready、页面也能加载，
     * 但会话列表要 50 秒左右才出来（弱 CPU 上 React 首屏 + IM 拉取都很慢）。
     * 如果只按 ready 判活，会在数据出来前误判；这里给“真的拿到会话数据”留 75 秒。
     */
    private static final long KERNEL_DATA_TIMEOUT_MS = 75_000L;

    private final Activity mActivity;
    private final ChatEngine.Listener mOuter;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private ChatPort mKernel;
    private ChatPort mWeb;
    private boolean mUsingKernel;
    private boolean mKernelReady;
    private boolean mAttached;
    private boolean mDetached;
    private boolean mKernelGotData;
    private boolean mNeedWebFetch;

    /**
     * 会话/消息缓存：内核页在 Activity 销毁后会被 Gecko 挂起，content script 的轮询停摆，
     * 再进聊天拿不到数据（2026-10-31 手表实测）。缓存让第二次进入先秒显上次内容，
     * 再去后台刷新；刷新若 5 秒无回包，自动重载内核页把 content script 拉活。
     */
    private static List<Conversation> sConvCache;
    private static final Map<String, List<ChatMessage>> sMsgCache = new HashMap<>();
    private String mCurrentConv;
    private boolean mConvResponseThisRound;
    private boolean mReviveScheduled;

    private final Runnable mRevive = new Runnable() {
        @Override
        public void run() {
            mReviveScheduled = false;
            if (!mUsingKernel || mDetached || mConvResponseThisRound) return;
            AppLog.i("kernel", "内核页无响应，后台重载一次（列表继续用缓存）");
            try {
                active().reloadIm();
            } catch (Throwable t) {
                AppLog.i("kernel", "后台重载内核页失败: " + t);
            }
        }
    };

    private final Runnable mTimeout = new Runnable() {
        @Override
        public void run() {
            if (mUsingKernel && !mKernelReady && mAttached && !mDetached
                    && !mActivity.isFinishing()) {
                switchToWebView("内核启动超时（" + (KERNEL_READY_TIMEOUT_MS / 1000) + "s）");
            }
        }
    };

    /** 内核 ready 了但一直拿不到会话数据：说明这一路实际上不可用，切回 WebView。 */
    private final Runnable mDataTimeout = new Runnable() {
        @Override
        public void run() {
            if (mUsingKernel && !mKernelGotData && mAttached && !mDetached
                    && !mActivity.isFinishing()) {
                switchToWebView("内核 " + (KERNEL_DATA_TIMEOUT_MS / 1000) + "s 未拿到会话数据");
            }
        }
    };

    FallbackChatPort(Activity activity, ChatEngine.Listener listener) {
        mActivity = activity;
        mOuter = listener;
        mKernel = KernelSeam.chatPort(activity, mKernelCallbacks);
        mUsingKernel = mKernel != null;
        if (!mUsingKernel) {
            // 反射失败：直接走 WebView，不让用户看到“内核构建却聊不了天”。
            mWeb = new WebViewChatPort(activity, mWebCallbacks);
        }
    }

    private ChatPort active() {
        return mUsingKernel ? mKernel : mWeb;
    }

    @Override
    public void ensureImHome() {
        active().ensureImHome();
        scheduleKernelTimeout();
    }

    @Override
    public void rebind() {
        active().rebind();
        scheduleKernelTimeout();
    }

    @Override
    public void attach(Activity activity) {
        mAttached = true;
        active().attach(activity);
        scheduleKernelTimeout();
    }

    @Override
    public void detach(Activity activity) {
        mAttached = false;
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mDataTimeout);
        mMain.removeCallbacks(mRevive);
        mReviveScheduled = false;
        active().detach(activity);
    }

    @Override
    public void fetchConversations() {
        if (mUsingKernel && sConvCache != null && !sConvCache.isEmpty()) {
            // 先秒显上次的列表，别让用户对着转圈等内核重新拉
            mOuter.onConversations(sConvCache);
            mConvResponseThisRound = false;
            if (!mReviveScheduled) {
                mReviveScheduled = true;
                mMain.postDelayed(mRevive, 5_000L);
            }
        }
        active().fetchConversations();
        scheduleDataTimeout();
    }

    @Override
    public void fetchMessages() {
        if (mUsingKernel && mCurrentConv != null) {
            List<ChatMessage> cached = sMsgCache.get(mCurrentConv);
            if (cached != null && !cached.isEmpty()) mOuter.onMessages(cached);
        }
        active().fetchMessages();
    }

    @Override
    public void openConversation(String name) {
        mCurrentConv = name;
        if (mUsingKernel && name != null) {
            List<ChatMessage> cached = sMsgCache.get(name);
            if (cached != null && !cached.isEmpty()) mOuter.onMessages(cached);
        }
        active().openConversation(name);
    }

    @Override
    public void sendText(String text) {
        active().sendText(text);
    }

    @Override
    public boolean isPatchApplied() {
        return active().isPatchApplied();
    }

    @Override
    public void reloadIm() {
        active().reloadIm();
    }

    @Override
    public void setListenerDetached(ChatEngine.Listener listener) {
        mDetached = true;
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mDataTimeout);
        if (mUsingKernel && mKernel != null) {
            mKernel.setListenerDetached(mKernelCallbacks);
        } else if (mWeb != null) {
            mWeb.setListenerDetached(mWebCallbacks);
        }
    }

    private void scheduleKernelTimeout() {
        mMain.removeCallbacks(mTimeout);
        if (mUsingKernel && !mKernelReady && mAttached && !mDetached) {
            mMain.postDelayed(mTimeout, KERNEL_READY_TIMEOUT_MS);
        }
    }

    private void scheduleDataTimeout() {
        if (!mUsingKernel || mKernelGotData || !mAttached || mDetached) return;
        mMain.removeCallbacks(mDataTimeout);
        mMain.postDelayed(mDataTimeout, KERNEL_DATA_TIMEOUT_MS);
    }

    /**
     * 切到 WebView。只在主线程调用（超时 Handler / Gecko 的 UI 回调都在主线程）。
     * 切之前先解除内核监听，避免旧内核的迟到结果串到新通道。
     */
    private void switchToWebView(String why) {
        if (!mUsingKernel || mDetached) return;
        mUsingKernel = false;
        mKernelReady = false;
        mNeedWebFetch = true;
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mDataTimeout);

        AppLog.i("kernel", "聊天内核不可用，回落 WebView：" + why);
        ChatPort old = mKernel;
        mKernel = null;
        if (old != null) {
            try {
                old.setListenerDetached(mKernelCallbacks);
            } catch (Throwable t) {
                AppLog.i("kernel", "解除内核聊天监听失败: " + t);
            }
        }

        mOuter.onEngineWaiting("内核聊天未就绪，已切回系统 WebView…");
        mWeb = new WebViewChatPort(mActivity, mWebCallbacks);
        if (mAttached) mWeb.attach(mActivity);
        mWeb.ensureImHome();
    }

    private final ChatEngine.Listener mKernelCallbacks = new ChatEngine.Listener() {
        @Override
        public void onEngineReady() {
            if (!mUsingKernel) return;
            mKernelReady = true;
            mMain.removeCallbacks(mTimeout);
            mOuter.onEngineReady();
        }

        @Override
        public void onMessages(List<ChatMessage> list) {
            if (!mUsingKernel) return;
            if (list != null && !list.isEmpty() && mCurrentConv != null) {
                sMsgCache.put(mCurrentConv, list);
            }
            mOuter.onMessages(list);
        }

        @Override
        public void onConversations(List<Conversation> list) {
            if (!mUsingKernel) return;
            mConvResponseThisRound = true;
            mMain.removeCallbacks(mRevive);
            mReviveScheduled = false;
            if (list != null && !list.isEmpty()) {
                mKernelGotData = true;
                sConvCache = list;
                mMain.removeCallbacks(mDataTimeout);
            }
            mOuter.onConversations(list);
        }

        @Override
        public void onComments(List<Comment> list) {
            if (mUsingKernel) mOuter.onComments(list);
        }

        @Override
        public void onCommentsMore(List<Comment> list, boolean atEnd) {
            if (mUsingKernel) mOuter.onCommentsMore(list, atEnd);
        }

        @Override
        public void onAuth(boolean ok, String message) {
            if (mUsingKernel) mOuter.onAuth(ok, message);
        }

        @Override
        public void onEngineError(String err) {
            if (!mUsingKernel) return;
            AppLog.i("kernel", "内核聊天错误，准备回落 WebView: " + err);
            switchToWebView("内核错误: " + err);
        }

        @Override
        public void onEngineWaiting(String why) {
            if (mUsingKernel) mOuter.onEngineWaiting(why);
        }
    };

    private final ChatEngine.Listener mWebCallbacks = new ChatEngine.Listener() {
        @Override
        public void onEngineReady() {
            if (mUsingKernel) return;
            if (mNeedWebFetch) {
                mNeedWebFetch = false;
                mWeb.fetchConversations();
            }
            mOuter.onEngineReady();
        }

        @Override
        public void onMessages(List<ChatMessage> list) {
            if (!mUsingKernel) mOuter.onMessages(list);
        }

        @Override
        public void onConversations(List<Conversation> list) {
            if (!mUsingKernel) mOuter.onConversations(list);
        }

        @Override
        public void onComments(List<Comment> list) {
            if (!mUsingKernel) mOuter.onComments(list);
        }

        @Override
        public void onCommentsMore(List<Comment> list, boolean atEnd) {
            if (!mUsingKernel) mOuter.onCommentsMore(list, atEnd);
        }

        @Override
        public void onAuth(boolean ok, String message) {
            if (!mUsingKernel) mOuter.onAuth(ok, message);
        }

        @Override
        public void onEngineError(String err) {
            if (!mUsingKernel) mOuter.onEngineError(err);
        }

        @Override
        public void onEngineWaiting(String why) {
            if (!mUsingKernel) mOuter.onEngineWaiting(why);
        }
    };
}
