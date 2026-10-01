package com.dywatch.app.chat;

// 私信 JS 补丁的**磁盘缓存**（方案 A 配套）。
//
// 为什么要缓存：被改写的两个 bundle 合计约 5.9MB，在手表（32 位 A53、堆上限 256MB）上
// 每次进聊天都重做一遍字符串改写既慢又费内存。改一次落盘，之后直接读。
//
// 设计：
//   - 键 = URL 的稳定哈希（不含 query，避免签名参数变化导致缓存永不命中）
//   - 命中即返回；未命中返回 null，由调用方改写后 save()
//   - 全部在应用私有目录，随卸载清理；写入失败一律静默（缓存不是正确性依赖）
//   - 纯 Java（无 Android 依赖）→ 可 JVM 单测

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class JsPatchCache {

    /**
     * 补丁逻辑版本号：**改写规则一变就必须 +1**。
     * 否则旧的（可能是错的）改写产物会一直命中缓存，排查时会误以为"补丁无效"，
     * 而清理缓存又只能用 pm clear（会连登录态一起清掉，代价太大）。
     * 历史：v1 的类字段规则误伤 switch 的 break;，产物被改坏；v2 修掉。
     */
    public static final int PATCH_VERSION = 3;

    private final File mDir;

    public JsPatchCache(File dir) {
        mDir = dir;
        if (mDir != null && !mDir.exists()) mDir.mkdirs();
    }

    /** 缓存键：补丁版本 + 文件名哈希（不含 query，避免签名参数变化导致永不命中） */
    public static String keyOf(String url) {
        if (url == null) return "v" + PATCH_VERSION + "_null";
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        // 只取末段文件名（含版本号 hash），足够区分
        int slash = s.lastIndexOf('/');
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        return "v" + PATCH_VERSION + "_" + Math.abs(name.hashCode()) + "_" + name.length();
    }

    /** 读缓存；未命中/异常返回 null */
    public String load(String url) {
        if (mDir == null) return null;
        File f = fileFor(url);
        if (f == null || !f.exists()) return null;
        try {
            return readAll(f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写缓存；失败静默（缓存不是正确性依赖） */
    public void save(String url, String js) {
        if (mDir == null || js == null) return;
        File f = fileFor(url);
        if (f == null) return;
        try {
            OutputStream os = new FileOutputStream(f);
            os.write(js.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {
        }
    }

    /** 清掉全部缓存（设置里可挂"清理缓存"；也便于补丁逻辑升级后强制重算） */
    public void clear() {
        if (mDir == null) return;
        File[] fs = mDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile()) {
                try { f.delete(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 清理过期产物（2026-10-01，用户问"这东西会不会一直留在本地、应用不会变大吗"——会的）。
     *
     * 两个来源：
     *  ① **旧补丁版本**：文件名里带 `PATCH_VERSION`，版本一升，旧文件再也不会被读到，
     *     却一直占地方。真机实测：v2 与 v3 各存一份，同一个 4.9MB 的 bundle 存了两遍，
     *     整个目录 ~13MB（手表存储本就紧张）。
     *  ② **失控增长**：站方一换 bundle，旧文件就成了死重量。
     * 做法：先删非当前版本的文件；再看总量，超上限就从最旧的开始删。
     * 纯 java.io（可 JVM 单测）；失败一律静默——缓存不是正确性依赖。
     *
     * @param maxTotalBytes 保留上限（字节）；<=0 表示只清旧版本、不设总量上限
     * @return 删掉的文件数
     */
    public int pruneStale(long maxTotalBytes) {
        if (mDir == null) return 0;
        File[] fs = mDir.listFiles();
        if (fs == null) return 0;
        int deleted = 0;
        final String keepPrefix = "jsp_v" + PATCH_VERSION + "_";
        java.util.List<File> alive = new java.util.ArrayList<>();
        for (File f : fs) {
            if (!f.isFile()) continue;
            if (!f.getName().startsWith(keepPrefix)) {
                if (delete(f)) deleted++;
            } else {
                alive.add(f);
            }
        }
        if (maxTotalBytes > 0) {
            long total = 0;
            for (File f : alive) total += f.length();
            if (total > maxTotalBytes) {
                // 最旧的先走（lastModified 升序）
                java.util.Collections.sort(alive, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (File f : alive) {
                    if (total <= maxTotalBytes) break;
                    long len = f.length();
                    if (delete(f)) {
                        total -= len;
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }

    private static boolean delete(File f) {
        try {
            return f.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private File fileFor(String url) {
        if (mDir == null) return null;
        return new File(mDir, "jsp_" + keyOf(url) + ".js");
    }

    private static String readAll(File f) throws Exception {
        InputStream is = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
