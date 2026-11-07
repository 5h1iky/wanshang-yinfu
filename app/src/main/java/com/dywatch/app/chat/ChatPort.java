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

    /**
     * 打开会话（稳定身份优先，2026-11-07 审计）。
     *
     * @param key  会话的稳定定位键（{@link com.dywatch.app.chat.model.Conversation#key}）：
     *             原生 API 通道就是 conversation_id；DOM 通道是页面内定位键（可能只是行号）。
     * @param name 会话显示名，仅用于展示 / 老通道兼容。**禁止**用名称前缀匹配来选会话。
     *
     * 默认实现只把 name 传给老通道（WebView / kernel 的桥只认名字）。
     * ApiChatPort 会覆盖它，用 key 精确查 conversation_id，name 只做唯一精确兜底。
     */
    default void openConversation(String key, String name) {
        openConversation(name);
    }

    /**
     * 当前通道的“空会话列表”是否可信。
     *
     * 原生 API 的 init 成功返回 0 条 = 真实 0 会话，应直接显示空态；
     * DOM / kernel 通道的 0 条可能只是页面还没渲染完，必须继续走等待/重试诊断。
     */
    default boolean isConvListAuthoritative() {
        return false;
    }

    /**
     * 发一条文本。
     *
     * @param cb 可为 null。非 null 时通道在**服务端给出结果**后回调；
     *           通道不支持结果回传（WebView/kernel）时不会调它，UI 按超时兜底。
     * @return **false = 这次根本没发出去**（通道未就绪/未登录/目标缺失），调用方不要显示本地气泡；
     *         true = 已交给通道，结果再看 {@code cb}。
     *
     * ⚠️ 2026-11-07 代码审计 H4：原来是 void，UI 无条件追加"我"的气泡 ——
     *    未登录/引擎未就绪时用户看到消息已发出，其实服务端根本没收到。现在必须能区分。
     */
    boolean sendText(String text, SendCallback cb);

    /** 是否已应用语法补丁：WebView 线用于提示状态机；内核线恒 true（不需要补丁） */
    boolean isPatchApplied();

    /** 网络恢复后重载私信页 */
    void reloadIm();

    /** 页面销毁时解除监听（引擎本身可能复用，不能连带销毁） */
    void setListenerDetached(ChatEngine.Listener listener);

    /**
     * 发送结果（2026-11-07 审计 H4 新增，默认空实现）。
     *
     * {@link #sendText} 返回 true 只代表"已交给通道"；真正的服务端结果从这里回来。
     * UI 在 ok=false 时要把乐观气泡标成失败（或撤回并回填输入框），不能继续显示成已发出。
     * 老通道（WebView / kernel）暂不实现 —— 默认空实现保证它们不改也能编过。
     */
    interface SendCallback {
        /**
         * @param ok     true = 服务端已接受；false = 已尝试但失败
         * @param detail 失败原因（给用户看的一句话；成功时为空）
         * @param text   这次发送的原文（UI 用来定位是哪条气泡）
         */
        void onSendResult(boolean ok, String detail, String text);
    }
}
