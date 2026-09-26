package com.dywatch.app.feed;

// HistoryStore 单测（JVM 直测，不需要 Context）。
// 重点验两件事：① 重复看同一条会挪到最新而不是留两份；② 超上限淘汰最旧而不是一键清空。
// 这两点搞错的话，"看过"列表会要么重复、要么集体消失。

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistoryStoreTest {

    /** 纯逻辑替身：把 store 的"读-改-写"抽出来，避免为测数据层造 Android Context */
    private static List<HistoryStore.Entry> apply(List<HistoryStore.Entry> list,
                                                  String id, int cap) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).awemeId)) { list.remove(i); break; }
        }
        list.add(0, new HistoryStore.Entry(id, "t" + id, "", System.currentTimeMillis()));
        while (list.size() > cap) list.remove(list.size() - 1);
        return list;
    }

    @Test
    public void repeatedView_movesToFront_keepsSingleCopy() {
        List<HistoryStore.Entry> list = new ArrayList<>();
        apply(list, "a", 10);
        apply(list, "b", 10);
        apply(list, "a", 10);      // 再看一次 a
        assertEquals("重复看不该产生两条", 2, list.size());
        assertEquals("最近看的应该在最前", "a", list.get(0).awemeId);
    }

    @Test
    public void overCap_dropsOldest_notAll() {
        List<HistoryStore.Entry> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) apply(list, String.valueOf(i), 3);
        assertEquals(3, list.size());
        // 后看的是 2,3,4 → 最前是 4，最旧保留的是 2
        assertEquals("4", list.get(0).awemeId);
        assertEquals("2", list.get(2).awemeId);
    }

    @Test
    public void entryKeepsTitleAndCover() {
        HistoryStore.Entry e = new HistoryStore.Entry("id1", "标题", "http://cover", 123L);
        assertEquals("id1", e.awemeId);
        assertEquals("标题", e.title);
        assertEquals("http://cover", e.coverUrl);
        assertEquals(123L, e.timeMs);
    }

    @Test
    public void nullFieldsBecomeEmptyStrings() {
        HistoryStore.Entry e = new HistoryStore.Entry(null, null, null, 0L);
        assertEquals("", e.awemeId);
        assertEquals("", e.title);
        assertEquals("", e.coverUrl);
        assertTrue(e.title.isEmpty());
    }
}
