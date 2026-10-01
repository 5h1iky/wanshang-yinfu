package com.dywatch.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * 日志文件裁剪的单测（2026-10-01，用户问"日志会不会一直存着、应用不会变大吗"）。
 *
 * 老实现只追加、永不清理；这里锁死"到线就砍、留下的是完整的行、文件确实变小"。
 * 纯 java.io，不碰 Android（AppLog.trim 是可测的静态方法）。
 */
public class AppLogTrimTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File writeLines(int lines) throws Exception {
        File f = folder.newFile("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("12:00:00 [tag] 第 " + i + " 行日志，带一点中文和一条长尾巴 ")
              .append("abcdefghijklmnopqrstuvwxyz0123456789\n");
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(sb.toString().getBytes(UTF8));
        }
        return f;
    }

    @Test
    public void 小于上限时不动文件() throws Exception {
        File f = writeLines(10);
        long before = f.length();
        assertFalse("没超线不该裁", AppLog.trim(f, 1024 * 1024));
        assertEquals(before, f.length());
    }

    @Test
    public void 超线后文件变小且不超过保留量太多() throws Exception {
        File f = writeLines(4000);
        long before = f.length();
        assertTrue("超线应裁剪", AppLog.trim(f, 4096));
        long after = f.length();
        assertTrue("文件应变小：" + before + " → " + after, after < before);
        assertTrue("应裁到保留量附近，实际 " + after, after <= 4096 + 200);
    }

    @Test
    public void 裁完留下的都是完整行() throws Exception {
        File f = writeLines(4000);
        AppLog.trim(f, 2048);
        String text = new String(Files.readAllBytes(f.toPath()), UTF8);
        assertFalse("开头不该有半行残缺", text.startsWith(" ") || text.startsWith("行"));
        assertTrue("每行都该以换行结束", text.endsWith("\n"));
        int lines = text.split("\n").length;
        assertTrue("应该留下若干完整行，实际 " + lines, lines > 1);
    }

    @Test
    public void 留下的是最后那段而不是开头() throws Exception {
        File f = writeLines(4000);
        AppLog.trim(f, 2048);
        String text = new String(Files.readAllBytes(f.toPath()), UTF8);
        assertTrue("应保留末尾（最新）的日志", text.contains("第 3999 行"));
        assertFalse("不该保留最老的日志", text.contains("第 0 行"));
    }

    @Test
    public void 中文不会被切成乱码() throws Exception {
        File f = writeLines(4000);
        AppLog.trim(f, 2048);
        String text = new String(Files.readAllBytes(f.toPath()), UTF8);
        // 按行对齐的裁剪不应产生替换字符（U+FFFD 是 UTF-8 截断的典型症状）
        assertFalse("不该出现乱码替换符", text.contains("\uFFFD"));
    }

    @Test
    public void 不存在的文件安全返回() {
        assertFalse(AppLog.trim(new File(folder.getRoot(), "nope.log"), 100));
        assertFalse(AppLog.trim(null, 100));
    }

    @Test
    public void 保留量为零时不裁剪_防止把日志清空() throws Exception {
        File f = writeLines(50);
        long before = f.length();
        assertFalse(AppLog.trim(f, 0));
        assertEquals(before, f.length());
    }
}
