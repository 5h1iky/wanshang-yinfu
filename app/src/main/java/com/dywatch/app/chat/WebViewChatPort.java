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
    public void sendText(String text) {
        mEngine.sendText(text);
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
