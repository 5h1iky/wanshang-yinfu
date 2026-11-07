package com.dywatch.app.feed;

/**
 * 播放页的生命周期代次（纯 Java，便于 JVM 单测）。
 *
 * <p>用法：异步任务/延时任务启动前 {@link #capture()} 一个代次，回调时用
 * {@link #isValid(int)} 校验；页面销毁时 {@link #destroy()} 会让所有旧代次立即失效。
 * 这样即使 runOnUiThread / Handler 消息在 onDestroy 之后才执行，也不会再碰已经
 * 销毁的 Activity 或播放器。</p>
 *
 * <p>所有方法都可以跨线程调用：generation 与 destroyed 都是 volatile。</p>
 */
public final class PlaybackLifecycle {

    private volatile int generation;
    private volatile boolean destroyed;

    /** 捕获当前代次。 */
    public int capture() {
        return generation;
    }

    /** 该代次是否仍然有效。 */
    public boolean isValid(int token) {
        return !destroyed && token == generation;
    }

    /** 主动让当前及之前的所有代次失效（例如开始一次全新的加载/页面切换）。 */
    public int invalidate() {
        generation++;
        return generation;
    }

    /** 页面销毁：永久失效（本对象随后不再复用）。 */
    public void destroy() {
        destroyed = true;
        generation++;
    }

    public boolean isDestroyed() {
        return destroyed;
    }
}
