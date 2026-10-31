package com.dywatch.app.chat;

import android.app.Activity;

/**
 * 聊天通道接缝（0.9.0 双构建）。
 *
 * 两个变体共用同一套聊天页面（会话列表 / 聊天页），底层通道不同：
 *   - webview 变体：{@link ChatEngine}（隐藏 WebView + JS 桥，现有路径，一行不改）；
 *   - kernel 变体：{@code KernelChatPort}（GeckoView + WebExtension，反射装配）。
 *
 * 这样 UI 只依赖这一个接口，不必在 Activity 里到处写 if (kernel)。
 */
public interface ChatPort {

    /** 把引擎归位到私信页（会被上一次互动留在视频页） */
    void ensureImHome();

    /** 页面回到前台时把结果监听者重新绑到当前页（内核线需要，WebView 线无需处理） */
    void rebind();

    /** 页面可见/恢复时的通道附着（WebView 线需要，内核线无需处理） */
    void attach(Activity activity);

    /** 页面不可见时的通道解绑（WebView 线需要，内核线保持后台页面） */
    void detach(Activity activity);

    void fetchConversations();

    void fetchMessages();

    void openConversation(String name);

    void sendText(String text);

    /** 是否已应用语法补丁：WebView 线用于提示状态机；内核线恒 true（不需要补丁） */
    boolean isPatchApplied();

    /** 网络恢复后重载私信页 */
    void reloadIm();

    /** 页面销毁时解除监听（引擎本身可能复用，不能连带销毁） */
    void setListenerDetached(ChatEngine.Listener listener);
}
