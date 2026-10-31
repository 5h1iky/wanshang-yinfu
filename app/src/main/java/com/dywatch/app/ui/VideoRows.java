package com.dywatch.app.ui;

import com.dywatch.app.feed.FeedVideo;

/** 视频列表统一的文字与封面映射，不让各页标题、作者、赞数格式漂移。 */
public final class VideoRows {
    private VideoRows() { }
    public static MediaRowAdapter.Item of(FeedVideo video, int index) {
        String title = video.title.isEmpty() ? "（无标题）" : video.title;
        StringBuilder meta = new StringBuilder();
        if (!video.authorName.isEmpty()) meta.append(video.authorName);
        if (video.diggCount > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(FeedVideo.formatCount(video.diggCount)).append(" 赞");
        }
        // 列表只负责选片：封面 + 标题 + 作者/赞数，不再写“点按进入全屏视频流”
        // 这种操作说明（HTML 草案里也没有这一行，三行会把小屏卡片撑高）。
        return new MediaRowAdapter.Item(title, meta.toString(), "",
                video.coverUrl, index, MediaRowAdapter.KIND_VIDEO);
    }
}
