package com.dywatch.app.chat;

import android.app.Activity;

/**
 * WebView 变体的聊天通道：只是 {@link ChatEngine} 的薄适配，行为与 0.9.0 之前完全一致。
 */
final class WebViewChatPort implements ChatPort {

    private final ChatEngine mEngine;
    private final ChatEngine.Listener mListener;

    WebViewChatPort(Activity activity, ChatEngine.Listener listener) {
        mListener = listener;
        mEngine = ChatEngine.getInstance(activity, listener);
    }

    @Override
    public void ensureImHome() {
        mEngine.ensureImHome();
    }

    @Override
    public void rebind() {
        // WebView 线由 ChatEngine.attachTo/detachFrom 管理，这里无需额外动作。
    }

    @Override
    public void attach(Activity activity) {
        ChatEngine.attachTo(activity);
    }

    @Override
    public void detach(Activity activity) {
        ChatEngine.detachFrom(activity);
    }

    @Override
    public void fetchConversations() {
        mEngine.fetchConversations();
    }

    @Override
    public void fetchMessages() {
        mEngine.fetchMessages();
    }

    @Override
    public void openConversation(String name) {
        mEngine.openConversation(name);
    }

    @Override
    public boolean sendText(String text, SendCallback cb) {
        // ⚠️ 2026-11-07 审计 H4：只有"引擎真的就绪"才可能发得出去。
        //    没就绪时返回 false，让 UI 不要显示乐观气泡（原来一律返回 void，UI 无条件追加）。
        if (mEngine == null || !mEngine.isReady()) return false;
        mEngine.sendText(text);
        // WebView 通道的 `sent` 结果目前只进日志（桥的 ok 判据是"编辑器被清空"，不是服务端确认），
        // 拿它当"发送成功"会骗人，所以这里**不回调** cb，由 UI 的超时兜底。
        return true;
    }

    @Override
    public boolean isPatchApplied() {
        return mEngine.isPatchApplied();
    }

    @Override
    public void reloadIm() {
        mEngine.reloadIm();
    }

    @Override
    public void setListenerDetached(ChatEngine.Listener listener) {
        mEngine.setListenerDetached(listener);
    }
}
