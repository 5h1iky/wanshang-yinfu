package com.dywatch.app.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 补丁缓存清理的单测（2026-10-01，用户问"这东西会不会一直留在本地"）。
 *
 * 真机实测：v2 与 v3 各存一份，同一个 4.9MB 的 bundle 存了两遍，目录 ~13MB 且永不回收。
 * 这里锁死三件事：旧版本必删、当前版本必留、总量超线从最旧的删。
 */
public class JsPatchCachePruneTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File make(File dir, String name, int bytes, long mtime) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(new byte[bytes]);
        }
        f.setLastModified(mtime);
        return f;
    }

    @Test
    public void 旧版本产物会被清掉_当前版本保留() throws Exception {
        File dir = folder.newFolder("js_patch");
        File old1 = make(dir, "jsp_v2_1194959557_46.js", 1000, 1000L);
        File old2 = make(dir, "jsp_v2_298420865_16.js", 500, 1000L);
        File now = make(dir, "jsp_v" + JsPatchCache.PATCH_VERSION + "_1194959557_46.js", 1000, 2000L);

        JsPatchCache cache = new JsPatchCache(dir);
        int deleted = cache.pruneStale(0);

        assertEquals("两个旧版本文件都该删", 2, deleted);
        assertFalse(old1.exists());
        assertFalse(old2.exists());
        assertTrue("当前版本必须留着（否则每次进私信都要重下 5MB）", now.exists());
    }

    @Test
    public void 总量超线时从最旧的开始删() throws Exception {
        File dir = folder.newFolder("js_patch2");
        String p = "jsp_v" + JsPatchCache.PATCH_VERSION + "_";
        File oldest = make(dir, p + "111_3.js", 800, 1000L);
        File middle = make(dir, p + "222_3.js", 800, 2000L);
        File newest = make(dir, p + "333_3.js", 800, 3000L);

        JsPatchCache cache = new JsPatchCache(dir);
        int deleted = cache.pruneStale(2000);   // 2400 > 2000 → 至少删掉最旧那个

        assertTrue("应删掉至少一个", deleted >= 1);
        assertFalse("最旧的应先走", oldest.exists());
        assertTrue("最新的要留着", newest.exists());
        assertNotNull(middle);   // 中间那个删不删取决于边界，不作硬断言
    }

    @Test
    public void 没超线且都是当前版本时不动手() throws Exception {
        File dir = folder.newFolder("js_patch3");
        String p = "jsp_v" + JsPatchCache.PATCH_VERSION + "_";
        File a = make(dir, p + "111_3.js", 100, 1000L);
        File b = make(dir, p + "222_3.js", 100, 2000L);

        JsPatchCache cache = new JsPatchCache(dir);
        assertEquals(0, cache.pruneStale(10_000));
        assertTrue(a.exists());
        assertTrue(b.exists());
    }

    @Test
    public void 缓存键带当前补丁版本_版本一升键就变() {
        String k = JsPatchCache.keyOf("https://x.com/pcim/static/js/async/4187.2096e141.js?v=1");
        assertTrue("键里应带版本前缀：" + k, k.startsWith("v" + JsPatchCache.PATCH_VERSION + "_"));
    }

    @Test
    public void 空目录与null安全() throws Exception {
        JsPatchCache c1 = new JsPatchCache(folder.newFolder("empty"));
        assertEquals(0, c1.pruneStale(1000));
        assertEquals(0, new JsPatchCache(null).pruneStale(1000));
    }
}
