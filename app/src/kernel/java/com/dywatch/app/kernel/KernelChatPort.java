package com.dywatch.app.kernel;

import android.app.Activity;

import com.dywatch.app.chat.ChatEngine;
import com.dywatch.app.chat.ChatPort;

/**
 * kernel 变体的聊天通道：把会话列表 / 聊天页的调用转到 GeckoView + WebExtension 桥。
 *
 * 只存在于 `src/kernel` 源集；由 {@link KernelSeam#chatPort} 反射装配，webview 变体看不到这个类。
 */
public final class KernelChatPort implements ChatPort {

    private final Activity mActivity;
    private final ChatEngine.Listener mListener;

    public KernelChatPort(Activity activity, ChatEngine.Listener listener) {
        mActivity = activity;
        mListener = listener;
        GeckoKernel.chatStart(activity, listener);
    }

    @Override
    public void ensureImHome() {
        GeckoKernel.chatStart(mActivity, mListener);
    }

    @Override
    public void rebind() {
        // 从聊天页返回列表时，静态监听者还指着旧 Activity，必须重绑当前页。
        GeckoKernel.chatStart(mActivity, mListener);
    }

    @Override
    public void attach(Activity activity) {
        // 内核页面由 GeckoKernel 自己管理生命周期，这里无需额外操作。
    }

    @Override
    public void detach(Activity activity) {
        // 私信需要后台收消息，页面不可见时不销毁内核页面。
    }

    @Override
    public void fetchConversations() {
        GeckoKernel.chat(mActivity, "chatList", null, null);
    }

    @Override
    public void fetchMessages() {
        GeckoKernel.chat(mActivity, "chatMessages", null, null);
    }

    @Override
    public void openConversation(String name) {
        GeckoKernel.chat(mActivity, "chatOpen", name, null);
    }

    @Override
    public boolean sendText(String text, SendCallback cb) {
        // ⚠️ 2026-11-07 审计 H4：内核没就绪时不要假装发出去了。
        //    GeckoKernel.chat 会自己排队，但从用户视角"现在这条到底发没发"必须能答。
        if (!GeckoKernel.isChatReady()) return false;
        GeckoKernel.chat(mActivity, "chatSend", null, text);
        // 内核的 `sent` 也是"编辑器是否被清空"级证据，不是服务端确认 → 不回调 cb，由 UI 超时兜底。
        return true;
    }

    @Override
    public boolean isPatchApplied() {
        // 内核里桥是扩展注入的，不依赖 WebView 的语法补丁
        return true;
    }

    @Override
    public void reloadIm() {
        GeckoKernel.chat(mActivity, "chatReload", null, null);
    }

    @Override
    public void setListenerDetached(ChatEngine.Listener listener) {
        GeckoKernel.clearChatListener(listener);
        // 页面销毁前把内核视图停到主界面，别让它跟着已销毁的窗口一起被挂起
        GeckoKernel.parkToHost();
    }
}
