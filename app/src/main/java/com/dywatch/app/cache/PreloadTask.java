package com.dywatch.app.cache;

import com.danikula.videocache.HttpProxyCacheServer;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import xyz.doikki.videoplayer.util.L;

/**
 * 原理：主动去请求VideoCache生成的代理地址，触发VideoCache缓存机制
 * 缓存到 PreloadManager.PRELOAD_LENGTH 的数据之后停止请求，完成预加载
 * 播放器去播放VideoCache生成的代理地址的时候，VideoCache会直接返回缓存数据，
 * 从而提升播放速度
 */
public class PreloadTask implements Runnable {

    /**
     * 原始地址
     */
    public String mRawUrl;

    /**
     * 列表中的位置
     */
    public int mPosition;

    /**
     * VideoCache服务器
     */
    public HttpProxyCacheServer mCacheServer;

    /**
     * 是否被取消（2026-10-02：加 volatile —— 写它的是主线程的 cancel()，
     * 读它的是预加载线程的 run()/start()，没有可见性保证时"取消"可能迟迟不生效）
     */
    private volatile boolean mIsCanceled;

    /**
     * 是否正在预加载（同上：executeOn 在主线程判，run 在工作线程清）
     */
    private volatile boolean mIsExecuted;

    /**
     * 预加载失败过的地址（"小黑屋"，不再重试）。
     *
     * ⚠️ 2026-10-02（代码审计 M7 同类）：这是个**静态列表**，原来只增不减 ——
     * 进程活得越久、遇到失败的 URL 越多，它就越大（每个 URL 一两百字节，外加常驻字符串）。
     * 而且它是进程级的、清不掉。加个上限，只保留最近失败的这批：
     * 目的只是"别对同一个坏地址反复重试"，不需要记住历史上所有的失败。
     */
    private final static List<String> blackList = new ArrayList<>();
    private static final int BLACKLIST_MAX = 300;

    @Override
    public void run() {
        if (!mIsCanceled) {
            start();
        }
        mIsExecuted = false;
        mIsCanceled = false;
    }

    /**
     * 开始预加载
     */
    private void start() {
        // 如果在小黑屋里不加载
        synchronized (blackList) {
            if (blackList.contains(mRawUrl)) return;
        }
        L.i("预加载开始：" + mPosition);
        HttpURLConnection connection = null;
        try {
            //获取HttpProxyCacheServer的代理地址
            String proxyUrl = mCacheServer.getProxyUrl(mRawUrl);
            URL url = new URL(proxyUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            InputStream in = new BufferedInputStream(connection.getInputStream());
            int length;
            int read = -1;
            byte[] bytes = new byte[8 * 1024];
            while ((length = in.read(bytes)) != -1) {
                read += length;
                //预加载完成或者取消预加载
                if (mIsCanceled || read >= PreloadManager.PRELOAD_LENGTH) {
                    if (mIsCanceled) {
                        L.i("预加载取消：" + mPosition + " 读取数据：" + read + " Byte");
                    } else {
                        L.i("预加载成功：" + mPosition + " 读取数据：" + read + " Byte");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            L.i("预加载异常：" + mPosition + " 异常信息："+ e.getMessage());
            // 关入小黑屋（带上限：见 blackList 注释）
            synchronized (blackList) {
                if (!blackList.contains(mRawUrl)) {
                    if (blackList.size() >= BLACKLIST_MAX) blackList.remove(0);
                    blackList.add(mRawUrl);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            L.i("预加载结束: " + mPosition);
        }
    }

    /**
     * 将预加载任务提交到线程池，准备执行
     */
    public void executeOn(ExecutorService executorService) {
        if (mIsExecuted) return;
        mIsExecuted = true;
        executorService.submit(this);
    }

    /**
     * 取消预加载任务
     */
    public void cancel() {
        if (mIsExecuted) {
            mIsCanceled = true;
        }
    }
}
