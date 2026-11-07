package com.dywatch.app.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 播放页生命周期代次的纯逻辑回归：
 * 旧网络/延时回调在销毁或重新开始一轮加载后必须失效。
 */
public class PlaybackLifecycleTest {

    @Test
    public void 捕获的代次在失效前有效() {
        PlaybackLifecycle lifecycle = new PlaybackLifecycle();
        int token = lifecycle.capture();
        assertTrue(lifecycle.isValid(token));
    }

    @Test
    public void invalidate之后旧代次全部失效() {
        PlaybackLifecycle lifecycle = new PlaybackLifecycle();
        int oldToken = lifecycle.capture();
        int newToken = lifecycle.invalidate();
        assertFalse(lifecycle.isValid(oldToken));
        assertTrue(lifecycle.isValid(newToken));
    }

    @Test
    public void destroy之后连最新代次也失效() {
        PlaybackLifecycle lifecycle = new PlaybackLifecycle();
        int token = lifecycle.invalidate();
        lifecycle.destroy();
        assertTrue(lifecycle.isDestroyed());
        assertFalse(lifecycle.isValid(token));
    }
}
