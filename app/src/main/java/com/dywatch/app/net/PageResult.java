package com.dywatch.app.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次分页请求的不可变结果：条目、下一页游标、是否还有更多。
 *
 * <p>存在的理由（2026-11-07 审计 P1-3/P1-4）：DouyinApi 原来把
 * {@code lastSearchHasMore/lastSearchOffset/lastSearchId} 这类字段挂在实例上，
 * 页面用同一个 mApi 并发请求时，先到的响应会被后到的响应覆盖，UI 就会拿 A 的
 * search_id/offset 去接 B 的列表，快速切 tab 或连续搜索时会串页。
 * 现在每个请求只返回自己的 PageResult，调用方不再回读共享字段。</p>
 *
 * <p>不可变保证：构造时复制输入列表并包成 unmodifiableList；{@link #items()}
 * 返回的列表不能再增删。{@code hasMore=false} 时游标统一归一为 -1，避免调用方
 * 误把旧游标当成下一页；{@code hasMore=true} 但游标为负同样按没有更多处理，
 * 从源头杜绝“服务端一直说还有、游标却不前进”的死循环。</p>
 */
public final class PageResult<T> {

    /** 没有下一页时的统一游标。 */
    public static final long END = -1L;

    private final List<T> items;
    private final long cursor;
    private final boolean hasMore;
    private final String searchId;

    public PageResult(List<T> items, long cursor, boolean hasMore) {
        this(items, cursor, hasMore, "");
    }

    public PageResult(List<T> items, long cursor, boolean hasMore, String searchId) {
        List<T> copy = new ArrayList<>();
        if (items != null) {
            for (T item : items) {
                if (item != null) copy.add(item);
            }
        }
        this.items = Collections.unmodifiableList(copy);
        this.hasMore = hasMore && cursor >= 0;
        this.cursor = this.hasMore ? cursor : END;
        this.searchId = searchId == null ? "" : searchId;
    }

    /** 本页条目；只读。 */
    public List<T> items() {
        return items;
    }

    /** 下一页请求应使用的游标；没有下一页时为 -1。 */
    public long cursor() {
        return cursor;
    }

    /** 服务端是否明确表示还有下一页（且游标有效）。 */
    public boolean hasMore() {
        return hasMore;
    }

    /** 搜索接口的 search_id/impr_id；非搜索分页为空字符串。 */
    public String searchId() {
        return searchId;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.<T>emptyList(), END, false, "");
    }
}
