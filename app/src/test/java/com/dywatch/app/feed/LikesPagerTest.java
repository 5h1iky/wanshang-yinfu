package com.dywatch.app.feed;

import com.dywatch.app.net.PageResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 「喜欢」列表服务端游标推进 + 跨页去重 + 防死循环的回归测试（审计 P1-4）。 */
public class LikesPagerTest {

    private static FeedVideo video(String id) {
        return new FeedVideo("t-" + id, "", "", id, "", 0, 0, 0);
    }

    @Test
    public void advancesByServerCursor_andDeduplicatesAcrossPages() {
        LikesPager pager = new LikesPager(0, true);
        pager.seed(Collections.singletonList(video("a")));

        List<FeedVideo> first = pager.accept(new PageResult<>(
                Arrays.asList(video("a"), video("b"), video("c")), 100, true));
        assertEquals("已展示过的 a 必须去重", 2, first.size());
        assertEquals("b", first.get(0).awemeId);
        assertEquals("c", first.get(1).awemeId);
        assertEquals("游标必须用服务端 max_cursor，而不是本地 list.size()", 100, pager.cursor());
        assertTrue(pager.hasMore());

        List<FeedVideo> second = pager.accept(new PageResult<>(
                Arrays.asList(video("b"), video("c"), video("d")), 200, true));
        assertEquals("跨页重复的 b/c 必须去掉", 1, second.size());
        assertEquals("d", second.get(0).awemeId);
        assertEquals(200, pager.cursor());
        assertTrue(pager.hasMore());
    }

    @Test
    public void stopsWhenCursorDoesNotAdvance() {
        LikesPager pager = new LikesPager(100, true);
        pager.accept(new PageResult<>(Collections.singletonList(video("x")), 100, true));
        assertFalse("has_more 恒 true 但游标不前进时必须停", pager.hasMore());
        assertEquals(PageResult.END, pager.cursor());
    }

    @Test
    public void stopsOnEmptyPageEvenIfServerSaysMore() {
        LikesPager pager = new LikesPager(0, true);
        pager.accept(new PageResult<FeedVideo>(Collections.<FeedVideo>emptyList(), 300, true));
        assertFalse("空页不能无限补拉", pager.hasMore());
        assertEquals(PageResult.END, pager.cursor());
    }

    @Test
    public void stopsWhenServerSaysNoMore() {
        LikesPager pager = new LikesPager(0, true);
        pager.accept(new PageResult<>(Collections.singletonList(video("a")), 500, false));
        assertFalse(pager.hasMore());
        assertEquals(PageResult.END, pager.cursor());
    }

    @Test
    public void duplicateOnlyPageWithAdvancedCursor_keepsLoading() {
        // 一页全是重复项但服务端游标确实前进了，不能直接判死；
        // 交给 FeedActivity 的连续重复页上限兜底，后面可能还有新内容。
        LikesPager pager = new LikesPager(0, true);
        pager.seed(Collections.singletonList(video("a")));
        List<FeedVideo> fresh = pager.accept(new PageResult<>(
                Collections.singletonList(video("a")), 400, true));
        assertTrue(fresh.isEmpty());
        assertTrue(pager.hasMore());
        assertEquals(400, pager.cursor());
    }
}
