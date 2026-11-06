package com.dywatch.app.chat;

// 聊天链路的进程内缓存清理口（2026-11-07 代码审计 H2）。
//
// 背景：{@link FallbackChatPort} 的会话/消息缓存是 **static**，进程活多久留多久，
// 而退出登录时没有任何入口清它。后果是"退出登录 → 重新登录另一个账号 → 进聊天"时，
// 界面上会先秒显出**上一个账号**的会话列表（隐私事故 + 观感事故）。
//
// 为什么要单独一个类：FallbackChatPort 是包内私有，LoginManager 在 login 包里访问不到；
// 也不想为了清缓存把 FallbackChatPort 提成 public（它应当保持实现细节）。

public final class ChatCache {

    private ChatCache() {}

    /** 清空所有进程内聊天缓存（退出登录时调） */
    public static void clearAll() {
        FallbackChatPort.clearCaches();
        com.dywatch.app.util.AppLog.i("chat", "已清除进程内聊天缓存");
    }
}
