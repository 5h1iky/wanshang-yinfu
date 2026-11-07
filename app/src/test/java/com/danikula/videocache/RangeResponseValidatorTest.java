package com.danikula.videocache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 长视频缓存污染回归测试（纯 JVM）：
 * 服务器忽略 Range 回 200、206 的 Content-Range 起点不对，都必须拒绝；
 * 只有真正从请求偏移开始的 206 才能接受。
 */
public class RangeResponseValidatorTest {

    @Test
    public void offset大于0时200必须拒绝() {
        assertFalse(RangeResponseValidator.isAcceptable(200, null, 1));
        assertFalse(RangeResponseValidator.isAcceptable(200, "bytes 0-99/100", 100));
    }

    @Test
    public void offset为0时200是正常整段响应() {
        assertTrue(RangeResponseValidator.isAcceptable(200, null, 0));
        assertTrue(RangeResponseValidator.isAcceptable(200, "bytes 0-99/100", 0));
    }

    @Test
    public void contentRange起点不匹配必须拒绝() {
        assertFalse(RangeResponseValidator.isAcceptable(206, "bytes 0-99/1000", 100));
        assertFalse(RangeResponseValidator.isAcceptable(206, "bytes 200-299/1000", 100));
        assertFalse(RangeResponseValidator.isAcceptable(206, "bytes */1000", 100));
    }

    @Test
    public void 正确的206必须接受() {
        assertTrue(RangeResponseValidator.isAcceptable(206, "bytes 100-199/1000", 100));
        assertTrue(RangeResponseValidator.isAcceptable(206, "bytes 0-99/1000", 0));
    }

    @Test
    public void 缺失contentRange的206不能当作有效分段() {
        assertFalse(RangeResponseValidator.isAcceptable(206, null, 100));
    }

    @Test
    public void 能解析总长度与起始偏移() {
        assertEquals(100, RangeResponseValidator.parseStart("bytes 100-199/1000"));
        assertEquals(1000, RangeResponseValidator.parseTotalLength("bytes 100-199/1000"));
        assertEquals(-1, RangeResponseValidator.parseTotalLength("bytes 100-199/*"));
        assertEquals(1000, RangeResponseValidator.resolveTotalLength(206, "bytes 100-199/1000", 100, 100));
        assertEquals(1000, RangeResponseValidator.resolveTotalLength(206, "bytes 100-199/*", 900, 100));
        assertEquals(1000, RangeResponseValidator.resolveTotalLength(200, null, 1000, 0));
    }
}
