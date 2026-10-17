package com.dywatch.app.cache;

import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.dywatch.app.util.AppLog;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.doikki.videoplayer.util.L;

/**
 * 抖音预加载工具，使用AndroidVideoCache实现
 */
public class PreloadManager {

    private static PreloadManager sPreloadManager;

    /**
     * 单线程池，按照添加顺序依次执行{@link PreloadTask}
     */
    private final ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    /**
     * 保存正在预加载的{@link PreloadTask}
     */
    private final LinkedHashMap<String, PreloadTask> mPreloadTasks = new LinkedHashMap<>();

    /**
     * 标识是否需要预加载
     */
    private boolean mIsStartPreload = true;

    /**
     * 宿主页面是否退到了后台（2026-10-02，代码审计 L6/L8）。
     *
     * 原来只有一个"滑动暂停/恢复"的语义：手指松开就恢复。而用户**按 Home 键走人**时，
     * 没人通知预加载停下 —— 已提交的任务会把队列里剩下的都下完（每条最多 1MB），
     * 用户看到的是"我没在看，流量和电还在掉"。现在宿主 onPause 整队停、onResume 再续。
     */
    private boolean mHostPaused;

    private final HttpProxyCacheServer mHttpProxyCacheServer;

    /**
     * 预加载的大小，每个视频预加载1M，这个参数可根据实际情况调整
     */
    public static final int PRELOAD_LENGTH = 1024 * 1024;

    /**
     * 走代理播放的最低门槛（2026-10-17）。
     *
     * 原来只有一个判据（1MB），于是出现这种浪费：预加载刚下到 900KB 时用户正好滑到这条，
     * isPreloaded() 返回 false → 直连原始地址 → **已经下好的 900KB 白费**，
     * 而且这一路完全没有缓存加速。
     *
     * 现在把两件事拆开：
     *   - "还要不要继续预加载" → 看 {@link #PRELOAD_LENGTH}（1MB）
     *   - "能不能走代理播放"   → 看这里（256KB），代理会从已有数据接着往下供流
     * 256KB 对 720p 大约够播两三秒，起播够用，剩下的边播边下。
     */
    public static final int READY_LENGTH = 256 * 1024;

    private PreloadManager(Context context) {
        // 代理构造失败时 getProxy 返回 null（不再抛异常），下面所有使用点都按"没有代理"处理
        mHttpProxyCacheServer = ProxyVideoCacheManager.getProxy(context);
    }

    public static PreloadManager getInstance(Context context) {
        if (sPreloadManager == null) {
            synchronized (PreloadManager.class) {
                if (sPreloadManager == null) {
                    sPreloadManager = new PreloadManager(context.getApplicationContext());
                }
            }
        }
        return sPreloadManager;
    }

    /**
     * 开始预加载
     *
     * @param rawUrl 原始视频地址
     */
    public void addPreloadTask(String rawUrl, int position) {
        // 代理没构造出来 / 已被判定不可用：没有缓存通道，预加载只会白耗流量
        if (mHttpProxyCacheServer == null || !ProxyVideoCacheManager.isProxyUsable()) return;
        if (isPreloaded(rawUrl)) return;
        PreloadTask task = new PreloadTask();
        task.mRawUrl = rawUrl;
        task.mPosition = position;
        task.mCacheServer = mHttpProxyCacheServer;
        L.i("addPreloadTask: " + position);
        mPreloadTasks.put(rawUrl, task);

        if (mIsStartPreload) {
            //开始预加载
            task.executeOn(mExecutorService);
        }
    }

    /**
     * 判断该播放地址是否已经预加载
     */
    private boolean isPreloaded(String rawUrl) {
        if (mHttpProxyCacheServer == null) return false;
        //先判断是否有缓存文件，如果已经存在缓存文件，并且其大小大于1KB，则表示已经预加载完成了
        File cacheFile = mHttpProxyCacheServer.getCacheFile(rawUrl);
        if (cacheFile.exists()) {
            if (cacheFile.length() >= 1024) {
                return true;
            } else {
                //这种情况一般是缓存出错，把缓存删掉，重新缓存
                cacheFile.delete();
                return false;
            }
        }
        //再判断是否有临时缓存文件，如果已经存在临时缓存文件，并且临时缓存文件超过了预加载大小，则表示已经预加载完成了
        File tempCacheFile = mHttpProxyCacheServer.getTempCacheFile(rawUrl);
        if (tempCacheFile.exists()) {
            return tempCacheFile.length() >= PRELOAD_LENGTH;
        }

        return false;
    }

    /**
     * 该地址**能不能直接走代理播放**（门槛比预加载低，见 {@link #READY_LENGTH}）。
     *
     * 完整缓存文件存在就算 ready —— 此时 getProxyUrl 会直接返回 file:// URI，
     * 哪怕是不到 256KB 的短视频也不该被永远打回直连。
     * 只有临时文件才按 256KB 门槛判断。
     */
    private boolean isReadyForPlayback(String rawUrl) {
        if (mHttpProxyCacheServer == null) return false;
        File cacheFile = mHttpProxyCacheServer.getCacheFile(rawUrl);
        if (cacheFile.exists()) return true;
        File tempCacheFile = mHttpProxyCacheServer.getTempCacheFile(rawUrl);
        return tempCacheFile.exists() && tempCacheFile.length() >= READY_LENGTH;
    }

    /**
     * 暂停预加载
     * 根据是否反向滑动取消在position之下或之上的PreloadTask
     *
     * @param position 当前滑到的位置
     * @param isReverseScroll 列表是否反向滑动
     */
    public void pausePreload(int position, boolean isReverseScroll) {
        L.d("pausePreload：" + position + " isReverseScroll: " + isReverseScroll);
        mIsStartPreload = false;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (isReverseScroll) {
                if (task.mPosition >= position) {
                    task.cancel();
                }
            } else {
                if (task.mPosition <= position) {
                    task.cancel();
                }
            }
        }
    }

    /**
     * 恢复预加载
     * 根据是否反向滑动开始在position之下或之上的PreloadTask
     *
     * @param position        当前滑到的位置
     * @param isReverseScroll 列表是否反向滑动
     */
    public void resumePreload(int position, boolean isReverseScroll) {
        L.d("resumePreload：" + position + " isReverseScroll: " + isReverseScroll);
        // 后台时不许被"滑动恢复"把预加载又拉起来（审计 L6/L8）
        if (mHostPaused) return;
        if (mHttpProxyCacheServer == null || !ProxyVideoCacheManager.isProxyUsable()) return;
        mIsStartPreload = true;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (isReverseScroll) {
                if (task.mPosition < position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            } else {
                if (task.mPosition > position) {
                    if (!isPreloaded(task.mRawUrl)) {
                        task.executeOn(mExecutorService);
                    }
                }
            }
        }
    }

    /**
     * 通过原始地址取消预加载
     *
     * @param rawUrl 原始地址
     */
    public void removePreloadTask(String rawUrl) {
        PreloadTask task = mPreloadTasks.get(rawUrl);
        if (task != null) {
            task.cancel();
            mPreloadTasks.remove(rawUrl);
        }
    }

    /**
     * 取消所有的预加载
     */
    public void removeAllPreloadTask() {
        Iterator<Map.Entry<String, PreloadTask>> iterator = mPreloadTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PreloadTask> next = iterator.next();
            PreloadTask task = next.getValue();
            task.cancel();
            iterator.remove();
        }
    }

    /**
     * 整队暂停（宿主 onPause）：取消在跑的任务，并拦住后续提交。
     * 任务本身留在表里，回到前台由 {@link #resumeAll()} 续上（executeOn 自带去重）。
     */
    public void pauseAll() {
        mHostPaused = true;
        mIsStartPreload = false;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            next.getValue().cancel();
        }
    }

    /**
     * 整队恢复（宿主 onResume）：只补那些"确实还没预加载好"的。
     * 为什么不是简单地把标志位打开：那样只有下一次滑动才会重启预加载，
     * 用户从后台回来不滑动的话，"下一条"就一直是没预热的。
     */
    public void resumeAll() {
        mHostPaused = false;
        mIsStartPreload = true;
        if (mHttpProxyCacheServer == null || !ProxyVideoCacheManager.isProxyUsable()) return;
        for (Map.Entry<String, PreloadTask> next : mPreloadTasks.entrySet()) {
            PreloadTask task = next.getValue();
            if (!isPreloaded(task.mRawUrl)) task.executeOn(mExecutorService);
        }
    }

    /**
     * 取这条视频实际该用的播放地址 —— **降级链路的入口**。
     *
     * 依次判断：
     *   ① 代理不可用 / 没构造出来 → 直连；
     *   ② 缓存还没攒到可播门槛（256KB）→ 直连，并取消同一条的预加载
     *      （本条都直连了，后台再走代理下一份只是双份流量）；
     *   ③ 都 OK → 返回代理地址（命中缓存，起播最快；完整缓存会直接给 file://）;
     *   ④ 连"取代理地址"这一步都抛异常 → 判定代理不可用，本次直连。
     *
     * 这一层保证的是"**播放永远不会因为缓存层的问题而失败**"—— 最差也有直连兜底。
     */
    public String getPlayUrl(String rawUrl) {
        if (mHttpProxyCacheServer == null || !ProxyVideoCacheManager.isProxyUsable()) {
            return rawUrl;
        }
        if (!isReadyForPlayback(rawUrl)) {
            // 只取消这一条：走代理播放时故意不取消（预加载和播放读同一份缓存，
            // 取消会把已经在下、马上能供播放器用的连接提前掐断）。
            PreloadTask task = mPreloadTasks.get(rawUrl);
            if (task != null) task.cancel();
            AppLog.i("cache", "缓存未达可播门槛（<256KB），本条直连");
            return rawUrl;
        }
        try {
            return mHttpProxyCacheServer.getProxyUrl(rawUrl);
        } catch (Throwable e) {
            ProxyVideoCacheManager.markProxyBroken("取代理地址失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return rawUrl;
        }
    }
}
