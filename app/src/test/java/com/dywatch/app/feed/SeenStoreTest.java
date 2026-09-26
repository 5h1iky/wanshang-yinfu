package com.dywatch.app.feed;

// SeenStore 的 LRU 纯逻辑单测（不碰 Context）。
// 旧实现是"满 1200 就 set.clear()"，等于账本一次性作废、用户立刻遭遇集体回炉。

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SeenStoreTest {

    @Test
    public void evictsOnlyTheOldest() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        SeenStore.applyMark(l, Arrays.asList("e"), 4);
        assertEquals(Arrays.asList("b", "c", "d", "e"), l);
    }

    @Test
    public void reMarkMovesToNewestEnd() {
        List<String> l = new ArrayList<>(Arrays.asList("a", "b", "c"));
        SeenStore.applyMark(l, Arrays.asList("a"), 3);
        assertEquals(Arrays.asList("b", "c", "a"), l);

        // 刚重看过的不该被下一批挤掉——否则等于白记
        SeenStore.applyMark(l, Arrays.asList("x", "y"), 3);
        assertTrue("最近看过的不能被淘汰", l.contains("a"));
    }

    @Test
    public void skipsBlankIds() {
        List<String> l = new ArrayList<>(Arrays.asList("a"));
        SeenStore.applyMark(l, Arrays.asList("", null, "b"), 5);
        assertEquals(Arrays.asList("a", "b"), l);
    }

    @Test
    public void batchKeepsNewestWithinCap() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < 50; i++) l.add("id" + i);
        SeenStore.applyMark(l, Arrays.asList("new1", "new2"), 20);
        assertEquals(20, l.size());
        assertEquals("new2", l.get(l.size() - 1));
        assertTrue("被淘汰的只能是最旧的", !l.contains("id0") && l.contains("id49"));
    }
}
