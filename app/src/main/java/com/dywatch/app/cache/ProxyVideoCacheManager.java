package com.dywatch.app.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.headers.HeaderInjector;
import com.dywatch.app.net.DouyinApi;

import java.io.File;
import java.util.Map;

public class ProxyVideoCacheManager {

    /**
     * 视频缓存上限（2026-10-02，代码审计 M7：512MB → 128MB）。
     *
     * 先说清楚上一版审计里说错的地方：这个库**确实**会淘汰（LruDiskUsage 按 lastModified
     * 从旧到新删，到总量 ≤ 上限为止），所以不是"只涨不降"。真正的问题是**上限本身**：
     *   ① 手表整机存储本来就只有几 GB，给视频缓存划 512MB 是手机的量级；
     *   ② 淘汰只在"一次播放会话结束 / 下载完成 / 命中已缓存 URL"时才跑，
     *      而 FileCache.append 不看上限 —— 下载中可以远远冲过 512MB 再一口气删；
     *   ③ 淘汰是**异步单线程**、失败还被静默吞掉（库里的 Logger 默认全关）。
     * 128MB 对"预加载 + 回看最近几条"完全够用（单条约 1MB），且离危险区更远。
     */
    private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;

    private static HttpProxyCacheServer sharedProxy;

    private ProxyVideoCacheManager() {
    }

    public static HttpProxyCacheServer getProxy(Context context) {
        return sharedProxy == null ? (sharedProxy = newProxy(context)) : sharedProxy;
    }

    private static HttpProxyCacheServer newProxy(Context context) {
        return new HttpProxyCacheServer.Builder(context)
                .maxCacheSize(MAX_CACHE_BYTES)
                // 预加载走代理时，源站请求由代理自己发，播放器上的 setUrl 头到不了那里——
                // 所以 Referer 必须在代理这一层注入，否则预加载过的条目仍会 403。
                .headerInjector(new HeaderInjector() {
                    @Override
                    public Map<String, String> addHeaders(String url) {
                        return DouyinApi.playHeaders();
                    }
                })
                //缓存路径，不设置默认在sd_card/Android/data/[app_package_name]/cache中
//                .cacheDirectory()
                .build();
    }

    /** 人类可读容量（设置页那行用；手表上看到"128.0 MB"比"134217728"有用） */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "0 MB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** 当前视频缓存占用（字节）。库里没有这个 API，只能自己遍历缓存目录求和 */
    public static long cacheSize(Context context) {
        try {
            File[] files = getProxy(context).getCacheRoot().listFiles();
            if (files == null) return 0;
            long total = 0;
            for (File f : files) {
                if (f.isFile()) total += f.length();
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 清空视频缓存（2026-10-02，代码审计 M7）。
     *
     * 实现取舍（真机实测踩到的）：预加载只读 1MB 就停，所以缓存目录里**绝大多数是
     * `xxx.download` 临时文件**（只有整条下完才会改名成最终文件）。第一版我为了"别删
     * 正在下载中的文件"跳过了全部 .download —— 真机上点"清空视频缓存"的实测结果是
     * 容量纹丝不动（3.3MB → 3.3MB），等于这个按钮是假的。
     *
     * 现在全删。最坏情况是什么？正在播的那条，它的下载线程已经持有文件句柄，
     * 文件被 unlink 后仍然能继续读/写（Linux 语义），只是这一条的缓存没了 ——
     * 正是这一行提示里写明的"正在播放的那条会重下"。用户按的就是"清空"，就按清空做。
     *
     * 注意：`AndroidVideoCache.db` 里的行（URL → mime/长度索引）这个库**没有任何删除 API**，
     * 只能留着。它只存 URL 字符串，体积很小，不影响可用空间。
     */
    public static boolean clearAllCache(Context context) {
        try {
            File root = getProxy(context).getCacheRoot();
            File[] files = root.listFiles();
            if (files == null) return true;
            boolean allOk = true;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!f.delete()) allOk = false;
            }
            return allOk;
        } catch (Throwable t) {
            return false;
        }
    }
}
