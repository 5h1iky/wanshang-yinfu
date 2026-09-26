package com.dywatch.app.chat.model;

/** 视频评论条目（DOM 抓取：昵称/文本/时间地点/点赞数） */
public class Comment {
    public final String name;
    public final String text;
    public final String time;
    public final String likes;

    public Comment(String name, String text, String time, String likes) {
        this.name = name == null ? "" : name;
        this.text = text == null ? "" : text;
        this.time = time == null ? "" : time;
        this.likes = likes == null ? "" : likes;
    }
}
