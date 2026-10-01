package com.dywatch.app.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 提示状态机的纯逻辑单测（2026-10-01，用户反馈"三个提示里两个在否定加载成功"）。
 *
 * 锁死的是三条口径：
 *   ① 首屏预算内一律算"加载中"（真机实测数据 10~60s 才回来，早下结论必误报）；
 *   ② 加载中的文案**不许出现否定词**（失败 / 异常 / 过旧 / 不可用 / 卡在）——
 *      那些只在预算耗尽后的终态里说；
 *   ③ 预算边界不含糊（29.999s 算加载中，30.000s 才算超时）。
 */
public class LegacyKernelHintTest {

    private static final String[] NEGATIVE_WORDS = {
            "失败", "异常", "过旧", "不可用", "卡在", "错误"
    };

    @Test
    public void 预算内算加载中_到点才算超时() {
        assertTrue(LegacyKernel.stillLoading(0));
        assertTrue(LegacyKernel.stillLoading(1_000));
        assertTrue(LegacyKernel.stillLoading(LegacyKernel.FIRST_LOAD_BUDGET_MS - 1));
        assertFalse(LegacyKernel.stillLoading(LegacyKernel.FIRST_LOAD_BUDGET_MS));
        assertFalse(LegacyKernel.stillLoading(LegacyKernel.FIRST_LOAD_BUDGET_MS + 5_000));
    }

    @Test
    public void 首屏预算是九十秒() {
        // 产品口径：真机冷启动最慢观测 61s，取 ≈1.5 倍余量。
        // 历史：30s 那版又出现"先说卡住、两秒后加载好"；60s 那版数据正好在第 61 秒到，仍擦边。
        assertEquals(90_000L, LegacyKernel.FIRST_LOAD_BUDGET_MS);
    }

    @Test
    public void 引擎没就绪时的进度文案_不含否定词() {
        String s = LegacyKernel.progressHint(false, 1);
        assertTrue("应说明在启动通道：" + s, s.contains("正在启动通道"));
        assertNoNegativeWords(s);
    }

    @Test
    public void 首次拉取的进度文案_不含否定词() {
        String s = LegacyKernel.progressHint(true, 1);
        assertTrue("应说明在拉取：" + s, s.contains("正在拉取会话"));
        assertNoNegativeWords(s);
    }

    @Test
    public void 重试中的进度文案_带上次数且不含否定词() {
        String s = LegacyKernel.progressHint(true, 4);
        assertTrue("应带上重试次数：" + s, s.contains("4 次"));
        assertNoNegativeWords(s);
    }

    @Test
    public void attempts_为一时不显示重试字样() {
        assertFalse(LegacyKernel.progressHint(true, 1).contains("重试"));
        assertFalse(LegacyKernel.progressHint(true, 0).contains("重试"));
    }

    @Test
    public void 预算没到就不该判内核死路_由调用方把关() {
        // isKernelDeadEnd 只看内核与重试次数，**必须**由调用方先过 stillLoading；
        // 这条单测把这个约定写下来，防止以后有人直接拿它判定死路。
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        assertTrue(LegacyKernel.isKernelDeadEnd(old, 2));
        assertTrue("预算内不该据此下结论", LegacyKernel.stillLoading(5_000));
    }

    @Test
    public void 内核够新时永远不算死路() {
        LegacyKernel.Kernel modern = new LegacyKernel.Kernel(108, "108.0.5359.128", "com.google.android.webview");
        assertFalse(LegacyKernel.isKernelDeadEnd(modern, 99));
    }

    private static void assertNoNegativeWords(String s) {
        for (String w : NEGATIVE_WORDS) {
            assertFalse("加载中的文案不该出现「" + w + "」：" + s, s.contains(w));
        }
    }
}
