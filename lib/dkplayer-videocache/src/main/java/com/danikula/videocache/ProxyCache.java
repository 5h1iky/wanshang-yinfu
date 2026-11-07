package com.danikula.videocache;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Proxy for {@link Source} with caching support ({@link Cache}).
 * <p/>
 * Can be used only for sources with persistent data (that doesn't change with time).
 * Method {@link #read(byte[], long, int)} will be blocked while fetching data from source.
 * Useful for streaming something with caching e.g. streaming video/audio etc.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class ProxyCache {

    /**
     * 2026-11-07：原来只允许 1 次失败，且没有总等待上限——源站/缓存线程卡住时
     * 播放器会永远停在缓冲。现在允许 3 次连续失败（有数据进展就重新计数），
     * 并且每次 read 请求最多等待 {@link #DEFAULT_MAX_WAIT_MS}。
     */
    private static final int MAX_READ_SOURCE_ATTEMPTS = 3;
    private static final long DEFAULT_MAX_WAIT_MS = 30_000L;
    private static final long WAIT_INTERVAL_MS = 1_000L;

    private final Source source;
    private final Cache cache;
    private final long maxWaitMs;
    private final Object wc = new Object();
    private final Object stopLock = new Object();
    private final AtomicInteger readSourceErrorsCount;
    private volatile Thread sourceReaderThread;
    private volatile boolean stopped;
    private volatile int percentsAvailable = -1;

    public ProxyCache(Source source, Cache cache) {
        this(source, cache, DEFAULT_MAX_WAIT_MS);
    }

    /** 包内可见：单测可用短超时验证「不会永久等待」。 */
    ProxyCache(Source source, Cache cache, long maxWaitMs) {
        this.source = checkNotNull(source);
        this.cache = checkNotNull(cache);
        this.maxWaitMs = maxWaitMs > 0 ? maxWaitMs : DEFAULT_MAX_WAIT_MS;
        this.readSourceErrorsCount = new AtomicInteger();
    }

    public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        ProxyCacheUtils.assertBuffer(buffer, offset, length);

        final long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (!cache.isCompleted() && cache.available() < (offset + length) && !stopped) {
            if (remainingWaitMs(deadlineNanos) <= 0) {
                abortSourceReader();
                throw new ProxyCacheException("Timeout waiting for source data after " + maxWaitMs
                        + "ms (offset=" + offset + ", length=" + length + ")");
            }
            readSourceAsync();
            waitForSourceData(deadlineNanos);
            checkReadSourceErrorsCount();
        }
        int read = cache.read(buffer, offset, length);
        if (cache.isCompleted() && percentsAvailable != 100) {
            percentsAvailable = 100;
            onCachePercentsAvailableChanged(100);
        }
        return read;
    }

    private long remainingWaitMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private void checkReadSourceErrorsCount() throws ProxyCacheException {
        int errorsCount = readSourceErrorsCount.get();
        if (errorsCount >= MAX_READ_SOURCE_ATTEMPTS) {
            readSourceErrorsCount.set(0);
            throw new ProxyCacheException("Error reading source " + errorsCount + " times");
        }
    }

    public void shutdown() {
        synchronized (stopLock) {
            Logger.debug("Shutdown proxy for " + source);
            try {
                stopped = true;
                if (sourceReaderThread != null) {
                    sourceReaderThread.interrupt();
                }
                cache.close();
            } catch (ProxyCacheException e) {
                onError(e);
            }
        }
    }

    private synchronized void readSourceAsync() throws ProxyCacheException {
        boolean readingInProgress = sourceReaderThread != null && sourceReaderThread.getState() != Thread.State.TERMINATED;
        if (!stopped && !cache.isCompleted() && !readingInProgress) {
            sourceReaderThread = new Thread(new SourceReaderRunnable(), "Source reader for " + source);
            sourceReaderThread.start();
        }
    }

    private void waitForSourceData(long deadlineNanos) throws ProxyCacheException {
        long remaining = remainingWaitMs(deadlineNanos);
        if (remaining <= 0) return;
        synchronized (wc) {
            try {
                wc.wait(Math.min(WAIT_INTERVAL_MS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxyCacheException("Waiting source data is interrupted!", e);
            }
        }
    }

    /** 总等待超时后叫停仍在跑的源读取线程，避免它继续占用连接/文件句柄。 */
    private void abortSourceReader() {
        Thread reader = sourceReaderThread;
        if (reader != null) reader.interrupt();
    }

    private void notifyNewCacheDataAvailable(long cacheAvailable, long sourceAvailable) {
        onCacheAvailable(cacheAvailable, sourceAvailable);

        synchronized (wc) {
            wc.notifyAll();
        }
    }

    protected void onCacheAvailable(long cacheAvailable, long sourceLength) {
        boolean zeroLengthSource = sourceLength == 0;
        int percents = zeroLengthSource ? 100 : (int) ((float) cacheAvailable / sourceLength * 100);
        boolean percentsChanged = percents != percentsAvailable;
        boolean sourceLengthKnown = sourceLength >= 0;
        if (sourceLengthKnown && percentsChanged) {
            onCachePercentsAvailableChanged(percents);
        }
        percentsAvailable = percents;
    }

    protected void onCachePercentsAvailableChanged(int percentsAvailable) {
    }

    private void readSource() {
        long sourceAvailable = -1;
        long offset = 0;
        try {
            offset = cache.available();
            source.open(offset);
            sourceAvailable = source.length();
            byte[] buffer = new byte[ProxyCacheUtils.DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = source.read(buffer)) != -1) {
                synchronized (stopLock) {
                    if (isStopped()) {
                        return;
                    }
                    cache.append(buffer, readBytes);
                }
                // 有真实数据进展 = 之前的失败不再算「连续失败」；否则一次瞬时抖动
                // 会让计数永远停在 3，稍微慢一点的源站再也无法恢复。
                readSourceErrorsCount.set(0);
                offset += readBytes;
                notifyNewCacheDataAvailable(offset, sourceAvailable);
            }
            tryComplete();
            onSourceRead();
        } catch (Throwable e) {
            readSourceErrorsCount.incrementAndGet();
            onError(e);
        } finally {
            closeSource();
            notifyNewCacheDataAvailable(offset, sourceAvailable);
        }
    }

    private void onSourceRead() {
        // guaranteed notify listeners after source read and cache completed
        percentsAvailable = 100;
        onCachePercentsAvailableChanged(percentsAvailable);
    }

    private void tryComplete() throws ProxyCacheException {
        synchronized (stopLock) {
            if (!isStopped() && cache.available() == source.length()) {
                cache.complete();
            }
        }
    }

    private boolean isStopped() {
        return Thread.currentThread().isInterrupted() || stopped;
    }

    private void closeSource() {
        try {
            source.close();
        } catch (ProxyCacheException e) {
            onError(new ProxyCacheException("Error closing source " + source, e));
        }
    }

    protected final void onError(final Throwable e) {
        boolean interruption = e instanceof InterruptedProxyCacheException;
        if (interruption) {
            Logger.debug("ProxyCache is interrupted");
        } else {
            Logger.error("ProxyCache error");
        }
    }

    private class SourceReaderRunnable implements Runnable {

        @Override
        public void run() {
            readSource();
        }
    }
}
