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
    public boolean sendText(String text, final SendCallback cb) {
        // ⚠️ 2026-11-07 审计 H4：只有"引擎真的就绪"才可能发得出去。
        //    没就绪时返回 false，让 UI 不要显示乐观气泡（原来一律返回 void，UI 无条件追加）。
        if (mEngine == null || !mEngine.isReady()) return false;
        // 私信链路修复：WebView 通道也要有确定回调（桥的 sent / error / 20s 超时三选一），
        // 否则 ChatActivity 的发送锁只能靠 UI 超时兜底，状态会一直 pending。
        mEngine.sendText(text, cb == null ? null : new ChatEngine.SendListener() {
            @Override
            public void onSendResult(boolean ok, String detail, String text2) {
                cb.onSendResult(ok, detail, text2);
            }
        });
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
