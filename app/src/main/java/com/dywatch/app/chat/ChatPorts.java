package com.dywatch.app.chat;

import android.app.Activity;

import com.dywatch.app.kernel.KernelSeam;

/**
 * 聊天通道工厂：kernel 变体优先用 Gecko 内核，不可用时回落原有 WebView 引擎。
 *
 * ⚠️ 0.9.0 的用户价值就在这里：kernel 版必须真的用内核跑聊天，否则 58MB 白装。
 * 但回落必须保留——内核扩展没起来时，用户至少还能聊天。
 */
public final class ChatPorts {

    private ChatPorts() {}

    public static ChatPort create(Activity activity, ChatEngine.Listener listener) {
        ChatPort kernel = KernelSeam.chatPort(activity, listener);
        if (kernel != null) return kernel;
        return new WebViewChatPort(activity, listener);
    }

}
