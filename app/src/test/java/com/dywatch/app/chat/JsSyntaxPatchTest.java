package com.dywatch.app.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JsSyntaxPatch 单测（方案 A 的语法补丁）。
 * 纪律：新断言先让它红一次（确认能证伪），这里用"替换前非法 / 替换后合法"的对照来表达。
 */
public class JsSyntaxPatchTest {

    // ---- ① 私有标识符 ----

    @Test
    public void privateField_renamed() {
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch("class C{ #p = 1; m(){ return this.#p; } }");
        assertEquals(2, r.privateId);
        assertTrue(r.js.contains("__dy_p"));
        assertFalse("原私有标识符应已消失", r.js.contains("#p"));
    }

    @Test
    public void privateMethod_renamed() {
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch("class C{ #m(){ return 1; } }");
        assertEquals(1, r.privateId);
        assertTrue(r.js.contains("__dy_m"));
    }

    /** ⚠️ 关键防误伤：字符串与 CSS 颜色里的 # 必须原样保留 */
    @Test
    public void hashInString_untouched() {
        String src = "var a = \"#ff0000\"; var b = ' #fff '; var c = `#abc123`;";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("字符串/CSS 里的 # 不该被改", 0, r.privateId);
        assertEquals(src, r.js);
    }

    @Test
    public void hashInCssSelector_untouched() {
        String src = "var s = \"a{color:#fff}\"; var t = \"#1890ff\";";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.privateId);
        assertEquals(src, r.js);
    }

    @Test
    public void hashInComment_untouched() {
        String src = "/* #notAField */ var x = 1; // #neither";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.privateId);
    }

    // ---- ② 逻辑赋值 ??= ----

    @Test
    public void logicalAssign_expanded() {
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch("let a; a ??= 5;");
        assertEquals(1, r.logical);
        assertTrue(r.js.contains("a = a ??"));
    }

    @Test
    public void logicalAssign_member() {
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch("this._proxy ??= {};");
        assertEquals(1, r.logical);
        assertTrue(r.js.contains("this._proxy = this._proxy ?? "));
    }

    @Test
    public void logicalAssign_inString_untouched() {
        String src = "var s = \"a ??= b\";";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.logical);
        assertEquals(src, r.js);
    }

    @Test
    public void doubleQuestionNotAssign_untouched() {
        // a ?? b（空值合并，Chrome 80+ 支持）不能被误当成 ??=
        String src = "var x = a ?? b;";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.logical);
        assertEquals(src, r.js);
    }

    // ---- ③ 类字段无初始化 ----

    @Test
    public void classFieldNoInit_givenUndefined() {
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch("class C{ value; next; }");
        assertEquals(2, r.classField);
        assertTrue(r.js.contains("value = undefined"));
        assertTrue(r.js.contains("next = undefined"));
    }

    @Test
    public void classFieldWithInit_untouched() {
        String src = "class C{ value = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("有初始化的类字段 Chrome 72+ 已支持，不该改", 0, r.classField);
        assertEquals(src, r.js);
    }

    /** ⚠️ 真机踩出来的 bug：switch 里的 `break;` 曾被误判成类字段，把文件改崩 */
    @Test
    public void switchBreak_notTreatedAsField() {
        String src = "switch(x){ case 1: f(); break; default: g(); }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("switch/break 不是类字段，不该改", 0, r.classField);
        assertEquals(src, r.js);
    }

    /** 函数体里的裸标识符语句也不是类字段 */
    @Test
    public void functionBodyBareIdentifier_notField() {
        String src = "function f(){ abc; return 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.classField);
        assertEquals(src, r.js);
    }

    /** 类体里确实该改（正例，防止上面两条把规则改死） */
    @Test
    public void classBodyField_stillPatched() {
        String src = "class C{ value; next; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(2, r.classField);
    }

    /** ⚠️ 防误伤：方法体里的 `x;` 表达式语句不能被当成类字段 */
    @Test
    public void statementNotField_safety() {
        String src = "function f(){ abc; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("函数体裸标识符不该被当成类字段", 0, r.classField);
        assertEquals(src, r.js);
    }

    // ---- 组合与健壮性 ----

    // ---- 转义处理（真实 bundle 踩出来的 bug）----

    /**
     * ⚠️ 真机致命 bug：字符串以双反斜杠结尾 `"a\\"` 时，
     * 旧的 `prev != '\\'` 判断会误认为引号被转义 → 字符串状态永不结束
     * → 其后所有内容被当成字符串吞掉 → 4187.js 的 431 处私有标识符一处都找不到。
     */
    @Test
    public void stringEndingWithDoubleBackslash_doesNotSwallowRest() {
        String src = "var s = \"a\\\\\"; class C{ #p = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("双反斜杠结尾的字符串后仍要能识别私有字段", 1, r.privateId);
        assertTrue(r.js.contains("__dy_p"));
    }

    @Test
    public void escapedQuoteInsideString_handled() {
        String src = "var s = \"a\\\"b\"; class C{ #p = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(1, r.privateId);
    }

    /**
     * ⚠️ 真机致命 bug 2：URL 里的 `//` 被当成行注释 → 超长单行的 bundle 从该点起
     * 全部被吞掉（4187.js 的 431 处私有标识符因此一处都找不到）。
     */
    @Test
    public void urlDoubleSlash_notComment() {
        String src = "var u = \"http://x.com\"; class C{ #p = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals("URL 里的 // 不该被当注释，否则后面全被吞", 1, r.privateId);
        assertTrue(r.js.contains("__dy_p"));
    }

    @Test
    public void realComment_stillSkipped() {
        // 真注释里的内容仍应被跳过（不入字符串状态）
        String src = "var a=1; // #notPriv\n class C{ #p = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(1, r.privateId);
    }

    @Test
    public void division_notComment() {
        String src = "var a=b/c; class C{ #p = 1; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(1, r.privateId);
    }

    @Test
    public void backslashBeforeHash_notPrivate() {
        // \ 后紧跟 # 不是私有标识符（形如 "\\#x" 在字符串里）
        String src = "var s = \"\\\\#notPriv\";";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(0, r.privateId);
    }

    @Test
    public void noTarget_unchangedAndFlagsFalse() {
        String src = "var a = 1; function f(){ return a; }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(src, r.js);
        assertFalse(r.changed);
    }

    @Test
    public void nullAndEmpty_safe() {
        assertEquals("", JsSyntaxPatch.patch(null).js);
        assertEquals("", JsSyntaxPatch.patch("").js);
    }

    @Test
    public void combined_allThreeKinds() {
        String src = "class C{ #p; m(){ this._x ??= 1; return this.#p; } }";
        JsSyntaxPatch.Result r = JsSyntaxPatch.patch(src);
        assertEquals(2, r.privateId);      // #p 声明 + this.#p
        assertEquals(1, r.logical);
        assertEquals(1, r.classField);     // #p 无初始化
        assertTrue(r.js.contains("__dy_p"));
        assertTrue(r.js.contains("this._x = this._x ?? "));
    }

    @Test
    public void idempotent_secondPassNoop() {
        String src = "class C{ #p = 1; m(){ return this.#p; } }";
        JsSyntaxPatch.Result r1 = JsSyntaxPatch.patch(src);
        JsSyntaxPatch.Result r2 = JsSyntaxPatch.patch(r1.js);
        assertEquals("第二次不该再改（幂等）", 0, r2.privateId + r2.logical + r2.classField);
    }
}
