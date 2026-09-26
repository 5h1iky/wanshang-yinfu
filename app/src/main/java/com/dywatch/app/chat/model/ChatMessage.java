package com.dywatch.app.chat.model;

/** 聊天消息模型（M3） */
public class ChatMessage {

    public static final int IN = 0;
    public static final int OUT = 1;

    public final int direction;
    public final String text;
    public final long timeMs;
    /** 页面时间文案（如"刚刚"/"18:34"，DOM 抓取原样） */
    public String timeText = "";

    public ChatMessage(int direction, String text, long timeMs) {
        this.direction = direction;
        this.text = text == null ? "" : text;
        this.timeMs = timeMs;
    }
}
