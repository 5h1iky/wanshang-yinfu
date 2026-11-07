package com.dywatch.app.util;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 「我的」页历史点击 in-flight 防重的回归测试（审计 M8 后续）。 */
public class InFlightGuardTest {

    @Test
    public void onlyOneAcquirerWins_untilReleased() {
        InFlightGuard guard = new InFlightGuard();
        assertTrue(guard.tryAcquire());
        assertFalse("任务在途时第二次点击必须被挡掉", guard.tryAcquire());
        assertTrue(guard.isInFlight());

        guard.release();
        assertFalse(guard.isInFlight());
        assertTrue("任务结束后必须能再次点击", guard.tryAcquire());
        guard.release();
    }

    @Test
    public void concurrentClicks_onlyOneTaskStarts() throws Exception {
        final InFlightGuard guard = new InFlightGuard();
        final int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger acquired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (guard.tryAcquire()) acquired.incrementAndGet();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("并发点击测试超时", done.await(5, TimeUnit.SECONDS));
            assertEquals("同一时刻只能有一个任务拿到执行权", 1, acquired.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
