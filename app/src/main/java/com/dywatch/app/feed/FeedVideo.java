package com.dywatch.app.feed;

/**
 * Feed 视频模型。
 * 字段对齐抖音推荐流 aweme_list：desc → title，aweme_id → awemeId，
 * video.play_addr（url_list 优先选跳转式候选）→ playUrl，video.cover → coverUrl，
 * statistics → 赞/评/藏计数（数据位设计参考 DKVideoPlayer demo 的 TiktokBean.likeCount）。
 * liked/collected 为本地互动状态（乐观更新）。
 */
public class FeedVideo implements java.io.Serializable {

    /** 「我的」页把列表传给播放页用；加个显式 id 免得反序列化告警 */
    private static final long serialVersionUID = 1L;

    public final String title;
    public final String playUrl;
    public final String coverUrl;
    public final String awemeId;
    public final String authorName;
    /** 作者 sec_uid（进作者主页要用；不是 final——解析后置填，避免改构造函数签名） */
    public String authorSecUid = "";
    public long diggCount;
    public long commentCount;
    public long collectCount;
    /** 本地互动状态（乐观更新；服务端确认后保持，失败回滚） */
    public boolean liked;
    public boolean collected;

    public FeedVideo(String title, String playUrl, String coverUrl) {
        this(title, playUrl, coverUrl, "", "", 0, 0, 0);
    }

    public FeedVideo(String title, String playUrl, String coverUrl, String awemeId,
                     String authorName, long diggCount, long commentCount, long collectCount) {
        this.title = title == null ? "" : title;
        this.playUrl = playUrl == null ? "" : playUrl;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.awemeId = awemeId == null ? "" : awemeId;
        this.authorName = authorName == null ? "" : authorName;
        this.diggCount = diggCount;
        this.commentCount = commentCount;
        this.collectCount = collectCount;
    }

    /** 计数显示格式化：12345 → 1.2万 */
    public static String formatCount(long n) {
        if (n >= 100000000) return String.format(java.util.Locale.US, "%.1f亿", n / 100000000.0);
        if (n >= 10000) return String.format(java.util.Locale.US, "%.1f万", n / 10000.0);
        return String.valueOf(n);
    }
}
