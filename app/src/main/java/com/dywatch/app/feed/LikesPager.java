package com.dywatch.app.feed;

import com.dywatch.app.net.PageResult;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「喜欢」列表的翻页状态机（纯 JVM 逻辑，可单测）。
 *
 * <p>2026-11-07 审计 P1-4：旧实现用 {@code cursor += list.size()}，把本地已展示条数
 * 当成抖音的服务端 max_cursor。只要解析阶段过滤了不可播/图文/重复项，本地条数就小于
 * 服务端消费条数，下一页必然重页或漏页；同时 {@code hasMore = !list.isEmpty()} 在
 * 服务端一直返回同一批内容时会无限请求。</p>
 *
 * <p>现在只认 {@link PageResult#cursor()} / {@link PageResult#hasMore()}：
 * 游标不前进、页为空、服务端说没有更多，任一条成立就停止；另外用 awemeId 做跨页去重，
 * 重复条目不会再交给播放页。</p>
 */
public final class LikesPager implements Serializable {

    private static final long serialVersionUID = 1L;

    private long cursor;
    private boolean hasMore;
    private final Set<String> seenAwemeIds = new HashSet<>();

    public LikesPager(long cursor, boolean hasMore) {
        this.cursor = hasMore ? cursor : PageResult.END;
        this.hasMore = hasMore && cursor >= 0;
    }

    /** 把页面上已经展示过的条目放进去重集合（进入播放页时由 MineActivity 调用）。 */
    public void seed(Collection<FeedVideo> alreadyShown) {
        if (alreadyShown == null) return;
        for (FeedVideo video : alreadyShown) {
            if (video == null) continue;
            String id = video.awemeId == null ? "" : video.awemeId;
            if (!id.isEmpty()) seenAwemeIds.add(id);
        }
    }

    public long cursor() {
        return cursor;
    }

    public boolean hasMore() {
        return hasMore;
    }

    /**
     * 消费一页服务端结果，返回本页里此前没见过的条目。
     *
     * <p>停止条件（任何一条都会让 hasMore=false、cursor=-1）：
     * 服务端说没有更多、下一页游标为负、游标没有前进、本页为空。
     * 一页全是重复项但游标确实前进时仍保持 hasMore=true，交给 FeedActivity
     * 的“连续重复页”上限去补拉，避免误杀后面真正的新内容。</p>
     */
    public List<FeedVideo> accept(PageResult<FeedVideo> page) {
        List<FeedVideo> fresh = new ArrayList<>();
        if (page == null) {
            hasMore = false;
            cursor = PageResult.END;
            return fresh;
        }
        for (FeedVideo video : page.items()) {
            if (video == null) continue;
            String id = video.awemeId == null ? "" : video.awemeId;
            if (!id.isEmpty() && !seenAwemeIds.add(id)) continue;
            fresh.add(video);
        }

        long previous = cursor;
        long next = page.cursor();
        boolean progressed = page.hasMore() && next >= 0 && next != previous;
        boolean pageNotEmpty = !page.items().isEmpty();
        hasMore = progressed && pageNotEmpty;
        cursor = hasMore ? next : PageResult.END;
        return fresh;
    }
}
