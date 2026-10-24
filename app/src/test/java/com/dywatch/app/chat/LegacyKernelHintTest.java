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
    public void 首屏预算是一百八十秒() {
        // 产品口径（2026-10-25 改）：手表两次冷启动实测 —— ①165s 成功（会话 7 条）；
        // ②293s+ 挂死。根因是抖音 pcim 微前端包要到 2.5~3 分钟才开始下载。
        // 90s 那版一到点就弹"内核过旧、无法加载"，与事实相反 → 放到 180s。
        assertEquals(180_000L, LegacyKernel.FIRST_LOAD_BUDGET_MS);
        assertTrue("165s 那次成功必须落在预算内", LegacyKernel.stillLoading(165_000L));
    }

    @Test
    public void 补丁生效时永远不算内核死路() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        // 补丁没生效 → 老口径仍然成立（重试够了才判死）
        assertTrue(LegacyKernel.isKernelDeadEnd(old, 2, false));
        // 补丁生效 → 内核不背锅（SyntaxError 已打到 0，剩下的只是慢）
        assertFalse(LegacyKernel.isKernelDeadEnd(old, 999, true));
    }

    @Test
    public void 内核提示只说慢不说无法加载() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        for (String s : new String[]{
                LegacyKernel.kernelHint(old),
                LegacyKernel.kernelHint(old, 165_000L),
                LegacyKernel.kernelHint(null),
                LegacyKernel.kernelHint(new LegacyKernel.Kernel(-1, "", ""))}) {
            assertFalse("终态提示不许下'无法加载/不可用'的结论：" + s,
                    s.contains("无法加载") || s.contains("不可用"));
            assertTrue("应给出可选动作：" + s, s.contains("继续等待"));
        }
    }

    @Test
    public void 终态提示带上已等秒数() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        String s = LegacyKernel.kernelHint(old, 165_000L);
        assertTrue("应带上已等秒数：" + s, s.contains("165s"));
        // elapsed <= 0 时不带秒数（老调用点不必改）
        assertFalse(LegacyKernel.kernelHint(old, 0).contains("已等"));
    }

    @Test
    public void 进度文案带上已等秒数且不含否定词() {
        String s = LegacyKernel.progressHint(true, 3, 42_000L);
        assertTrue("应带上已等秒数：" + s, s.contains("42s"));
        assertTrue("仍应说明在拉取：" + s, s.contains("正在拉取会话"));
        assertNoNegativeWords(s);
        // 不足 1 秒不显示（避免首帧出现"已等 0s"）
        assertFalse(LegacyKernel.progressHint(true, 1, 800L).contains("已等"));
        // 老签名不带秒数，行为不变
        assertFalse(LegacyKernel.progressHint(true, 1).contains("已等"));
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

    @Test
    public void 决策函数_预算内一律给加载态() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        LegacyKernel.Hint h = LegacyKernel.decideHint(old, true, true, 3, true, 45_000L);
        assertTrue("预算内必须是加载态", h.loading);
        assertTrue("应带秒数：" + h.text, h.text.contains("45s"));
        assertTrue("应说在拉取：" + h.text, h.text.contains("正在拉取"));
        assertNoNegativeWords(h.text);
    }

    @Test
    public void 决策函数_补丁生效时预算后给卡点而不是内核过旧() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        // 补丁生效 → isKernelDeadEnd 恒 false → 走 stuckHint（"卡在…"），不再甩锅内核
        LegacyKernel.Hint h = LegacyKernel.decideHint(old, true, true, 9, true, 190_000L);
        assertFalse("预算耗尽后应停下转圈", h.loading);
        assertFalse("不许说无法加载：" + h.text, h.text.contains("无法加载"));
        assertTrue("应说明卡在哪一步：" + h.text, h.text.contains("卡在"));
    }

    @Test
    public void 决策函数_补丁未生效且内核过旧时只说慢() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        LegacyKernel.Hint h = LegacyKernel.decideHint(old, true, true, 8, false, 190_000L);
        assertFalse(h.loading);
        assertTrue("应走 kernelHint 的慢口径：" + h.text, h.text.contains("加载很慢"));
        assertTrue("应带已等秒数：" + h.text, h.text.contains("190s"));
        assertFalse("不许说无法加载：" + h.text, h.text.contains("无法加载"));
    }

    @Test
    public void 决策函数_未登录时的终态先说登录态() {
        LegacyKernel.Kernel modern = new LegacyKernel.Kernel(108, "108.0.5359.128", "com.google.android.webview");
        LegacyKernel.Hint h = LegacyKernel.decideHint(modern, false, false, 1, false, 190_000L);
        assertFalse(h.loading);
        assertTrue("应先说登录态：" + h.text, h.text.contains("登录态"));
    }

    @Test
    public void 断网时先说网络而不是骗用户去重登() {
        LegacyKernel.Kernel old = new LegacyKernel.Kernel(83, "83.0.4103.120", "com.android.webview");
        // 真机现场：断网 180s 后给了「卡在登录态，请回主屏重新登录」——用户明明登录着，只是没网
        LegacyKernel.Hint term = LegacyKernel.decideHint(old, false, false, 9, true, 190_000L, false);
        assertFalse(term.loading);
        assertTrue("应报网络：" + term.text, term.text.contains("网络未连接"));
        assertFalse("不该引导重新登录：" + term.text, term.text.contains("重新登录"));

        LegacyKernel.Hint load = LegacyKernel.decideHint(old, false, false, 9, true, 45_000L, false);
        assertTrue("预算内仍是加载态", load.loading);
        assertTrue("应说在等网络：" + load.text, load.text.contains("等待网络"));
        assertTrue("仍带秒数：" + load.text, load.text.contains("45s"));
    }

    @Test
    public void 有网时行为与原来一致() {
        LegacyKernel.Kernel modern = new LegacyKernel.Kernel(108, "108.0.5359.128", "com.google.android.webview");
        LegacyKernel.Hint h = LegacyKernel.decideHint(modern, true, true, 1, false, 45_000L, true);
        assertTrue(h.loading);
        assertTrue("有网时应说在拉取：" + h.text, h.text.contains("正在拉取"));
    }

    private static void assertNoNegativeWords(String s) {
        for (String w : NEGATIVE_WORDS) {
            assertFalse("加载中的文案不该出现「" + w + "」：" + s, s.contains(w));
        }
    }
}
