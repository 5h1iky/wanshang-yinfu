package com.dywatch.app.chat.model;

/** 会话列表条目（会话列表页用；字段按 DOM 勘探结果对齐） */
public class Conversation {
    /** 页面内唯一定位键（DOM 路径或会话 id，点击后由 JS 桥据此点开对应会话） */
    public final String key;
    public final String name;
    public final String lastMsg;
    /** 相对时间文案（如"昨天"），DOM 里就是文案，不强转时间戳 */
    public final String timeText;

    public Conversation(String key, String name, String lastMsg, String timeText) {
        this.key = key;
        this.name = name;
        this.lastMsg = lastMsg;
        this.timeText = timeText;
    }
}
