package com.dywatch.app.chat;

// 桥事件脱敏回归测试（2026-10-02，代码审计 H3）。
//
// 这一条测试的意义不是"函数算得对"，而是**锁死一句承诺**：
// app.log 与 logcat 里不会出现用户的私信正文、评论正文、会话昵称、账号 uid。
// 桥的字段以后再加新的，只要没人改白名单，正文就不会漏出去。

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BridgeLogTest {

    @Test
    public void 私信正文不进日志() {
        String json = "{\"type\":\"messages\",\"doc\":\"k\",\"items\":["
                + "{\"dir\":\"in\",\"text\":\"今晚八点老地方见\",\"time\":\"18:00\"},"
                + "{\"dir\":\"out\",\"text\":\"好的\",\"time\":\"18:01\"}]}";
        String s = BridgeLog.summarize(json);
        assertFalse("私信正文泄漏: " + s, s.contains("今晚八点老地方见"));
        assertFalse("私信正文泄漏: " + s, s.contains("好的"));
        assertTrue("应保留条数用于诊断: " + s, s.contains("条数=2"));
    }

    @Test
    public void 会话昵称与最近消息不进日志() {
        String json = "{\"type\":\"conversations\",\"doc\":\"k\",\"rows\":5,\"tries\":2,\"items\":["
                + "{\"key\":\"0\",\"name\":\"张三丰\",\"lastMsg\":\"钱已经转你了\",\"time\":\"昨天\"}]}";
        String s = BridgeLog.summarize(json);
        assertFalse("昵称泄漏: " + s, s.contains("张三丰"));
        assertFalse("最近消息泄漏: " + s, s.contains("钱已经转你了"));
        assertTrue(s.contains("rows=5"));
        assertTrue(s.contains("tries=2"));
    }

    @Test
    public void 评论正文与昵称不进日志() {
        String json = "{\"type\":\"comments\",\"doc\":\"k\",\"total\":18,\"atEnd\":false,\"items\":["
                + "{\"name\":\"李四\",\"text\":\"这个视频拍得真好\",\"time\":\"1小时前\",\"likes\":\"3\"}]}";
        String s = BridgeLog.summarize(json);
        assertFalse("评论昵称泄漏: " + s, s.contains("李四"));
        assertFalse("评论正文泄漏: " + s, s.contains("这个视频拍得真好"));
        assertTrue(s.contains("新增=1"));
        assertTrue(s.contains("累计=18"));
    }

    @Test
    public void 登录昵称与uid不进日志() {
        String json = "{\"type\":\"auth\",\"doc\":\"k\",\"ok\":true,\"user\":\"我的抖音昵称\",\"uid\":\"123456789\"}";
        String s = BridgeLog.summarize(json);
        assertFalse("昵称泄漏: " + s, s.contains("我的抖音昵称"));
        assertFalse("uid 泄漏: " + s, s.contains("123456789"));
        assertTrue(s.contains("ok=true"));
        assertTrue("应能看出有没有昵称: " + s, s.contains("有昵称=true"));
    }

    @Test
    public void 发评论的injected字段不进日志() {
        // phase=submitting 这条过程事件里的 injected 就是用户刚打的评论正文
        String json = "{\"type\":\"action\",\"doc\":\"k\",\"action\":\"sendComment\","
                + "\"phase\":\"submitting\",\"cands\":3,\"injected\":\"我要发的评论内容\"}";
        String s = BridgeLog.summarize(json);
        assertFalse("injected 正文泄漏: " + s, s.contains("我要发的评论内容"));
        assertTrue(s.contains("phase=submitting"));
    }

    @Test
    public void detail里的用户正文被抹掉但判词保留() {
        String s = BridgeLog.safeText("提交未生效：编辑器仍有内容「这是用户打的字」，已试 3 个候选钮");
        assertFalse("正文泄漏: " + s, s.contains("这是用户打的字"));
        assertTrue("判词应保留: " + s, s.contains("提交未生效"));
    }

    @Test
    public void state里的编辑器正文被抹掉() {
        String s = BridgeLog.safeText("文本未进入编辑器（state=用户的评论草稿）");
        assertFalse("正文泄漏: " + s, s.contains("用户的评论草稿"));
        assertTrue(s.contains("文本未进入编辑器"));
    }

    @Test
    public void 纯判词的detail原样保留() {
        // 这些是排查时真正要看的信息，不能被上面那两条规则误伤
        assertTrue(BridgeLog.safeText("状态已翻转").contains("状态已翻转"));
        assertTrue(BridgeLog.safeText("多次点击后状态仍未变").contains("多次点击后状态仍未变"));
        assertTrue(BridgeLog.safeText("未找到按钮 video-player-digg").contains("video-player-digg"));
    }

    @Test
    public void 计数文本原样保留异常形状则抹掉() {
        assertTrue(BridgeLog.safeCount("4.0万→4.1万").contains("4.0万"));
        // 正常情况下 count 只可能是数字；出现别的字符就说明字段被挪用了，宁可不打印
        assertFalse(BridgeLog.safeCount("张三丰说了句话").contains("张三丰"));
    }

    @Test
    public void 非JSON不原样落盘() {
        String s = BridgeLog.summarize("私信正文但是坏 JSON");
        assertFalse("非 JSON 输入不该原样输出: " + s, s.contains("私信正文但是坏 JSON"));
        assertTrue(s.contains("非 JSON"));
    }

    @Test
    public void 超长detail被截断() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append('x');
        String s = BridgeLog.safeText(sb.toString());
        assertTrue("应截断: " + s.length(), s.length() <= 81);
    }
}
