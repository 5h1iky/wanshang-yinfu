package com.dywatch.app.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 快捷回复文案纯逻辑单测（软件内问题 ③b，2026-10-01）。
 *
 * 锁死的是产品口径而不是实现细节：
 *   · 默认值必须是老版本聊天页那套（升级用户观感不变）
 *   · 留空 = 隐藏该按钮（所以不需要"数量"设置）
 *   · 槽位数固定 4（布局里就是 4 个按钮），越界输入不能让页面崩
 */
public class QuickReplyTextsTest {

    @Test
    public void 默认文案就是老聊天页那套() {
        assertArrayEquals(new String[]{"好", "在忙", "稍等", "😂"}, QuickReplyTexts.defaults());
    }

    @Test
    public void 默认值返回副本_改它不影响下一次() {
        String[] a = QuickReplyTexts.defaults();
        a[0] = "改坏了";
        assertEquals("好", QuickReplyTexts.defaults()[0]);
    }

    @Test
    public void 槽位键是四个独立键() {
        assertEquals("qr_1", QuickReplyTexts.key(1));
        assertEquals("qr_4", QuickReplyTexts.key(4));
    }

    @Test
    public void 清洗_去首尾空白与换行() {
        assertEquals("在忙", QuickReplyTexts.sanitize("  在忙 \n"));
        assertEquals("稍等", QuickReplyTexts.sanitize("稍\n等"));
        assertEquals("", QuickReplyTexts.sanitize(null));
        assertEquals("", QuickReplyTexts.sanitize("   "));
    }

    @Test
    public void 清洗_超长截断到上限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("字");
        assertEquals(QuickReplyTexts.MAX_LEN, QuickReplyTexts.sanitize(sb.toString()).length());
    }

    @Test
    public void 补齐到四个槽位_少的补空() {
        String[] out = QuickReplyTexts.normalize(new String[]{"好"});
        assertEquals(4, out.length);
        assertEquals("好", out[0]);
        assertEquals("", out[1]);
        assertEquals("", out[3]);
    }

    @Test
    public void 多余的槽位被丢掉_不会越界() {
        String[] out = QuickReplyTexts.normalize(new String[]{"a", "b", "c", "d", "e", "f"});
        assertEquals(4, out.length);
        assertEquals("d", out[3]);
    }

    @Test
    public void null_数组也能得到四个空槽() {
        String[] out = QuickReplyTexts.normalize(null);
        assertEquals(4, out.length);
        for (String s : out) assertEquals("", s);
    }

    @Test
    public void 留空即隐藏_空判据认空白串() {
        assertTrue(QuickReplyTexts.isEmpty(""));
        assertTrue(QuickReplyTexts.isEmpty("   "));
        assertTrue(QuickReplyTexts.isEmpty(null));
        assertFalse(QuickReplyTexts.isEmpty("好"));
    }

    @Test
    public void 摘要在全空时给出说明而不是空串() {
        assertEquals("好 / 在忙", QuickReplyTexts.summary(new String[]{"好", "在忙", "", "  "}));
        assertEquals("（全部留空 = 不显示快捷条）", QuickReplyTexts.summary(new String[]{"", "", "", ""}));
    }

    @Test
    public void 计数只数有内容的槽位() {
        assertEquals(2, QuickReplyTexts.countNonEmpty(new String[]{"好", "", "稍等", "  "}));
        assertEquals(0, QuickReplyTexts.countNonEmpty(null));
    }

    @Test
    public void 按槽位取默认值_越界给空串() {
        assertEquals("好", QuickReplyTexts.defaultOf(1));
        assertEquals("😂", QuickReplyTexts.defaultOf(4));
        assertEquals("", QuickReplyTexts.defaultOf(0));
        assertEquals("", QuickReplyTexts.defaultOf(5));
    }
}
