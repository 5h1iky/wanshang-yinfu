package com.dywatch.app.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次只允许一个后台任务在途的轻量闸门。
 *
 * <p>「我的」页历史条目第一次点击要先用 aweme/detail 换播放地址，手表上网络慢，
 * 用户很容易连点。原实现每次点击都新起线程，结果重复 fetch、重复 startActivity。
 * 这里用 CAS 保证只有第一次点击能拿到执行权，任务结束（成功/失败）必须 release。</p>
 */
public final class InFlightGuard {

    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /** @return true 表示本次拿到了执行权；false 表示已有任务在途。 */
    public boolean tryAcquire() {
        return inFlight.compareAndSet(false, true);
    }

    /** 任务结束必须调用（放在所有退出路径上）。重复 release 是幂等的。 */
    public void release() {
        inFlight.set(false);
    }

    public boolean isInFlight() {
        return inFlight.get();
    }
}
