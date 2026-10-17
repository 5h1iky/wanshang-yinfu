package com.dywatch.app.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.headers.HeaderInjector;
import com.dywatch.app.net.DouyinApi;
import com.dywatch.app.util.AppLog;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * 连续多少条"走代理播放失败"才判定这台设备上的代理不可用（2026-10-17）。
     *
     * 一条坏缓存只该影响那一条：先删缓存 + 改直连重播（见 FeedActivity.fallbackToDirectPlay）。
     * 连续两条都失败，才说明问题不在单条缓存上——这时把整个代理关掉、后续一律直连，
     * 避免用户滑到一条失败一次、反复看到"这个视频打不开"。
     */
    private static final int PROXY_FAILURE_LIMIT = 2;

    /** 代理实例；构造失败时为 null。 */
    private static volatile HttpProxyCacheServer sharedProxy;

    /** 代理是否已被判定不可用（进程内）。置位后不再回头，用户冷启动会重新尝试。 */
    private static volatile boolean sProxyBroken;

    /** 「连续失败」计数：一次代理播放成功就清零。 */
    private static final AtomicInteger sProxyFailureStreak = new AtomicInteger();

    private ProxyVideoCacheManager() {
    }

    /**
     * 取代理实例。
     *
     * 构造失败（端口占用、缓存目录不可写、系统限制明文回环等）时**不再把异常抛给宿主**：
     * 记一次日志、置 broken、返回 null，调用方一律按"没有代理"走直连。
     * 这是 2026-10-17 修掉的一个真问题：原来构造失败会直接从 FeedActivity.onCreate 崩出去。
     */
    public static synchronized HttpProxyCacheServer getProxy(Context context) {
        if (sharedProxy == null && !sProxyBroken) {
            try {
                sharedProxy = newProxy(context);
            } catch (Throwable t) {
                markProxyBroken("构造失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }
        return sharedProxy;
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

    /** 播放/预加载是否还能走代理 */
    public static boolean isProxyUsable() {
        return !sProxyBroken;
    }

    /**
     * 把代理标记为不可用。之后播放与预加载一律直连；
     * 缓存容量统计、清缓存、删单条缓存仍然可用（它们不经过代理播放）。
     */
    public static void markProxyBroken(String why) {
        if (!sProxyBroken) {
            sProxyBroken = true;
            AppLog.i("cache", "代理判定不可用，后续直连：" + why);
        }
    }

    /**
     * 记录一次"走代理播放失败"（由 FeedActivity 在删坏缓存、改直连时调用）。
     * 连续达到 {@link #PROXY_FAILURE_LIMIT} 条才全局关闭代理，避免一条坏缓存误伤整个缓存。
     */
    public static void noteProxyPlaybackFailure() {
        int n = sProxyFailureStreak.incrementAndGet();
        AppLog.i("cache", "代理播放失败计数 " + n + "/" + PROXY_FAILURE_LIMIT);
        if (n >= PROXY_FAILURE_LIMIT) {
            markProxyBroken("连续 " + n + " 条代理播放失败");
        }
    }

    /** 记录一次"走代理播放成功"：失败计数清零。 */
    public static void noteProxyPlaybackSuccess() {
        sProxyFailureStreak.set(0);
    }

    /**
     * 删掉某一条的缓存（正式文件 + 临时文件）。
     *
     * 用于「代理播放失败 → 直连重播」：既然播不出来，这份缓存就是坏的 ——
     * 不删掉的话，下次 {@code getPlayUrl} 看到它已过可播门槛，还会把播放送回代理。
     */
    public static void evict(Context context, String rawUrl) {
        try {
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return;
            File f = proxy.getCacheFile(rawUrl);
            if (f.exists()) f.delete();
            File t = proxy.getTempCacheFile(rawUrl);
            if (t.exists()) t.delete();
        } catch (Throwable ignored) {
            // 删不掉不致命：最坏是这次重播仍走代理、仍失败，用户滑一下就好
        }
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
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return 0;
            File[] files = proxy.getCacheRoot().listFiles();
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
            HttpProxyCacheServer proxy = getProxy(context);
            if (proxy == null) return true;
            File root = proxy.getCacheRoot();
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
