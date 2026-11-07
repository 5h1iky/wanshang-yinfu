package com.danikula.videocache;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 缓存读线程不能永久等待：源一直不产出数据时，read() 必须在总超时后抛
 * ProxyCacheException（上层据此回退直连），不能把播放器永远卡在缓冲。
 */
public class ProxyCacheTimeoutTest {

    @Test
    public void 源不产出数据时按总超时失败而不是永久等待() {
        ProxyCache proxyCache = new ProxyCache(new SlowSource(), new NeverFillingCache(), 200);
        long start = System.currentTimeMillis();
        try {
            proxyCache.read(new byte[10], 0, 10);
            fail("should throw ProxyCacheException after max wait");
        } catch (ProxyCacheException expected) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("超时应当有界，实际耗时 " + elapsed + "ms", elapsed < 3000);
            assertTrue("错误信息应包含超时: " + expected.getMessage(),
                    expected.getMessage().contains("Timeout"));
        } finally {
            proxyCache.shutdown();
        }
    }

    private static final class NeverFillingCache implements Cache {
        @Override public long available() { return 0; }
        @Override public int read(byte[] buffer, long offset, int length) { return -1; }
        @Override public void append(byte[] data, int length) { }
        @Override public void close() { }
        @Override public void complete() { }
        @Override public boolean isCompleted() { return false; }
    }

    private static final class SlowSource implements Source {
        @Override public void open(long offset) { }
        @Override public long length() { return 1000; }
        @Override public int read(byte[] buffer) throws ProxyCacheException {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxyCacheException("interrupted", e);
            }
            return 1;
        }
        @Override public void close() { }
    }
}
