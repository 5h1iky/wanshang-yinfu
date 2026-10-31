package com.dywatch.app.chat.model;

/** 视频评论条目（DOM 抓取：昵称/文本/时间地点/点赞数） */
public class Comment implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public String cid = "";
    public String secUid = "";
    public String avatarUrl = "";
    public String replyToName = "";
    public String replyToCid = "";
    public long replyCount;
    /** 一级评论响应中的首条回复预览；无预览时为空。 */
    public java.util.ArrayList<Comment> previewReplies = new java.util.ArrayList<>();
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
