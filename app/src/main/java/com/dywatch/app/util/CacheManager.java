package com.dywatch.app.util;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * 应用缓存盘点/清理。
 *
 * 体积大头通常不是 APK：webview 变体 APK ~7MB，但旧 kernel 变体用 GeckoView
 * 浏览网页后会留下 cache/qgahcyt6.default（HTTP cache）和 files/mozilla 下的
 * profile，几百 MB 很常见。webview 变体根本不再使用 Gecko，所以这部分属于
 * 可安全清理的历史缓存；当前 WebView / 封面图缓存则各自单独统计。
 */
public final class CacheManager {

    private CacheManager() { }

    public static File geckoCacheDir(Context c) {
        return new File(c.getCacheDir(), "qgahcyt6.default");
    }

    public static File geckoProfileDir(Context c) {
        return new File(c.getFilesDir(), "mozilla/qgahcyt6.default");
    }

    public static File webViewCacheDir(Context c) {
        return new File(c.getCacheDir(), "WebView");
    }

    /** 旧内核 / 系统 WebView 的网页缓存大小（不含封面图、视频缓存）。 */
    public static long browserCacheSize(Context c) {
        return size(geckoCacheDir(c)) + size(webViewCacheDir(c));
    }

    /** 只清网页/旧内核 HTTP cache，不碰登录 profile、Cookie 与视频缓存。 */
    public static long clearBrowserCache(Context c) {
        long before = browserCacheSize(c);
        delete(geckoCacheDir(c));
        delete(webViewCacheDir(c));
        delete(new File(c.getCacheDir(), "gecko_temp"));
        return before;
    }

    /** 清理旧 kernel 变体遗留的 Gecko 缓存；webview 变体启动时调用。 */
    public static long clearLegacyGeckoCache(Context c) {
        long before = size(geckoCacheDir(c)) + size(geckoProfileDir(c));
        delete(geckoCacheDir(c));
        delete(geckoProfileDir(c));
        return before;
    }

    public static long size(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) total += size(child);
        return total;
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) delete(child);
            }
        }
        // 删不掉（被 WebView/内核占用）就留给系统清理，不抛异常。
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f));
    }
}
