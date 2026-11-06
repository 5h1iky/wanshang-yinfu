package com.dywatch.app.chat;

import android.app.Activity;

import com.dywatch.app.kernel.KernelSeam;
import com.dywatch.app.ui.Settings;
import com.dywatch.app.util.AppLog;

/**
 * 聊天通道工厂。
 *
 * 三条通道按优先级选：
 *   ① {@link ApiChatPort}（原生 HTTP，0.9.1 新增）：聊天全链路 API 化，最快最省内存；
 *   ② kernel 变体的 Gecko 通道（{@link FallbackChatPort}，内含内核→WebView 回落）；
 *   ③ {@link WebViewChatPort}（DOM 抓取，历史通道，永远可用）。
 *
 * ⚠️ 灰度策略（0.9.1）：原生通道**默认开启**，但提供一个设置项可以关掉
 *    （设置 → 聊天通道）。关掉立刻回到旧的 WebView 通道，不需要退版本。
 *    这是"新链路先给能关的开关"的保守做法：真机上万一某个账号的协议不一致，
 *    用户自己有退路，不用等下一版。
 */
public final class ChatPorts {

    private ChatPorts() {}

    public static ChatPort create(Activity activity, ChatEngine.Listener listener) {
        if (useNative(activity)) {
            AppLog.i("chat", "聊天通道：原生 API");
            return new ApiChatPort(activity, listener);
        }
        if (!KernelSeam.enabled()) {
            AppLog.i("chat", "聊天通道：WebView（DOM）");
            return new WebViewChatPort(activity, listener);
        }
        // kernel 构建：先给 Gecko 内核，运行时起不来/超时由 FallbackChatPort 自动切回 WebView。
        AppLog.i("chat", "聊天通道：Gecko 内核");
        return new FallbackChatPort(activity, listener);
    }

    private static boolean useNative(Activity activity) {
        try {
            return Settings.nativeChatEnabled(activity);
        } catch (Throwable t) {
            return true;   // 读设置失败也按默认（开）走，不能让聊天不可用
        }
    }
}
