package com.dywatch.app.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.StorageUtils;
import com.danikula.videocache.headers.HeaderInjector;
import com.dywatch.app.net.DouyinApi;

import java.io.File;
import java.util.Map;

public class ProxyVideoCacheManager {

    private static HttpProxyCacheServer sharedProxy;

    private ProxyVideoCacheManager() {
    }

    public static HttpProxyCacheServer getProxy(Context context) {
        return sharedProxy == null ? (sharedProxy = newProxy(context)) : sharedProxy;
    }

    private static HttpProxyCacheServer newProxy(Context context) {
        return new HttpProxyCacheServer.Builder(context)
                .maxCacheSize(512 * 1024 * 1024)       // 512MB for cache
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


    /**
     * 删除所有缓存文件
     * @return 返回缓存是否删除成功
     */
    public static boolean clearAllCache(Context context) {
        getProxy(context);
        return StorageUtils.deleteFiles(sharedProxy.getCacheRoot());
    }

    /**
     * 删除url对应默认缓存文件
     * @return 返回缓存是否删除成功
     */
    public static boolean clearDefaultCache(Context context, String url) {
        getProxy(context);
        File pathTmp = sharedProxy.getTempCacheFile(url);
        File path = sharedProxy.getCacheFile(url);
        return StorageUtils.deleteFile(pathTmp.getAbsolutePath()) &&
                StorageUtils.deleteFile(path.getAbsolutePath());

    }
}