package com.dywatch.app.net;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 分页结果不可变/并发隔离的回归测试（2026-11-07 审计 P1-3）。 */
public class PageResultTest {

    @Test
    public void items_areDefensivelyCopiedAndUnmodifiable() {
        List<String> source = new ArrayList<>(Arrays.asList("a", "b"));
        PageResult<String> page = new PageResult<>(source, 30, true, "sid-1");

        // 调用方之后改原列表，不能影响已经交给 UI 的 page。
        source.add("c");
        source.set(0, "changed");

        assertEquals(Arrays.asList("a", "b"), page.items());
        assertEquals(30, page.cursor());
        assertTrue(page.hasMore());
        assertEquals("sid-1", page.searchId());
        try {
            page.items().add("x");
            fail("PageResult.items() 必须是只读列表");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void hasMoreFalse_normalizesCursorToEnd() {
        PageResult<String> page = new PageResult<>(Collections.singletonList("a"), 99, false);
        assertFalse(page.hasMore());
        assertEquals(PageResult.END, page.cursor());
    }

    @Test
    public void hasMoreTrueWithNegativeCursor_isTreatedAsEnd() {
        PageResult<String> page = new PageResult<>(Collections.singletonList("a"), -5, true);
        assertFalse("无有效下一页游标时不能继续请求", page.hasMore());
        assertEquals(PageResult.END, page.cursor());
    }

    /**
     * 并发发起多个“搜索请求”，每个请求的 items/cursor/searchId 必须各归各的。
     * 这条测试锁定的是旧实现的根因：共享 lastSearch* 字段会让后返回的请求覆盖先返回的，
     * 现在每个请求构造自己的 PageResult，不应存在任何交叉。
     */
    @Test
    public void concurrentPages_doNotCrossTalk() throws Exception {
        final int count = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<PageResult<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    // 稍作让出，放大并发交错的机会。
                    Thread.sleep(n % 3);
                    return new PageResult<>(
                            Collections.singletonList("item-" + n),
                            1000 + n,
                            true,
                            "sid-" + n);
                }));
            }
            for (int i = 0; i < count; i++) {
                PageResult<String> page = futures.get(i).get(5, TimeUnit.SECONDS);
                assertEquals(Collections.singletonList("item-" + i), page.items());
                assertEquals(1000 + i, page.cursor());
                assertEquals("sid-" + i, page.searchId());
                assertTrue(page.hasMore());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
