package com.dywatch.app.sign;

// a_bogus 签名器：用 Rhino（Java 实现的 JS 引擎）执行 ylcangel/douyin_sign 的算法实现（Apache-2.0，SPDX 见各 JS 头部）。
// 纯 Java、不依赖 Android API —— 可在 JVM 单元测试直接验证（SignerTest）。
// 用法：加载 assets/sign/{utils.js,sm3.js,vm_decode.js} 三个源码后调 makeABogus(query)。
// 注意：签名对象必须是最终发送的原始 query 串，签完再追加 &a_bogus=（M0.5① 实测教训）。

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.util.List;

public final class Signer {

    /** 浏览器环境打桩（算法采集 window/navigator/screen 的值参与计算，数值与工具链实测一致） */
    private static final String ENV_PRELUDE =
            "var window = this; var self = this;\n"
                    + "var console = { log: function(){}, info: function(){}, warn: function(){}, error: function(){} };\n"
                    + "var navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36', "
                    + "platform: 'Win32' };\n"
                    + "var innerWidth = 2048; var innerHeight = 960;\n"
                    + "var outerWidth = 2554; var outerHeight = 1386;\n"
                    + "var screen = { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440 };\n";


    /**
     * ES6 补丁：**老版 Rhino（1.7.7.x）没有 Map / Set**，而抖音的签名 JS 用了 Map
     * （不补的话在 Android 4.x 上直接 `ReferenceError: "Map" is not defined`）。
     *
     * 为什么宁可用老 Rhino + 补丁，也不用新版 Rhino：
     * 新版（1.7.13 起）源码里含 Java 8 lambda，D8 会生成 `$$ExternalSyntheticLambda` 系列类，
     * 而 **Dalvik（Android 4.4）加载不到副 dex 里的这些类** → `NoClassDefFoundError` 闪退。
     * 1.7.7.2 是 Java 7 时代的版本，编译进 dex 后一个 lambda 类都不产生。
     *
     * 注意 size 用普通属性维护而不是 getter —— 老 Rhino 对 defineProperty 的 getter 支持不稳。
     */
    private static final String ES6_POLYFILL =
            "if (typeof Map === 'undefined') {\n"
                    + "  Map = function(iterable) {\n"
                    + "    this._k = []; this._v = {}; this.size = 0;\n"
                    + "    if (iterable) { for (var i = 0; i < iterable.length; i++) this.set(iterable[i][0], iterable[i][1]); }\n"
                    + "  };\n"
                    + "  Map.prototype.set = function(k, v) { var s = '$' + k; if (!(s in this._v)) this._k.push(k); this._v[s] = v; this.size = this._k.length; return this; };\n"
                    + "  Map.prototype.get = function(k) { var s = '$' + k; return (s in this._v) ? this._v[s] : undefined; };\n"
                    + "  Map.prototype.has = function(k) { return ('$' + k) in this._v; };\n"
                    + "  Map.prototype[\'delete\'] = function(k) { var s = '$' + k; if (s in this._v) { delete this._v[s]; this._k.splice(this._k.indexOf(k), 1); this.size = this._k.length; return true; } return false; };\n"
                    + "  Map.prototype.clear = function() { this._k = []; this._v = {}; this.size = 0; };\n"
                    + "  Map.prototype.forEach = function(cb, t) { for (var i = 0; i < this._k.length; i++) { var k = this._k[i]; cb.call(t, this._v['$' + k], k, this); } };\n"
                    + "}\n"
                    + "if (typeof Set === 'undefined') {\n"
                    + "  Set = function(iterable) { this._m = new Map(); this.size = 0; if (iterable) { for (var i = 0; i < iterable.length; i++) this.add(iterable[i]); } };\n"
                    + "  Set.prototype.add = function(v) { if (!this._m.has(v)) { this._m.set(v, true); this.size = this._m.size; } return this; };\n"
                    + "  Set.prototype.has = function(v) { return this._m.has(v); };\n"
                    + "  Set.prototype[\'delete\'] = function(v) { var r = this._m[\'delete\'](v); this.size = this._m.size; return r; };\n"
                    + "  Set.prototype.clear = function() { this._m.clear(); this.size = 0; };\n"
                    + "  Set.prototype.forEach = function(cb, t) { var s = this; this._m.forEach(function(v, k) { cb.call(t, k, k, s); }); };\n"
                    + "}\n"
                    + "if (!Array.prototype.includes) { Array.prototype.includes = function(x) { return this.indexOf(x) !== -1; }; }\n"
                    + "if (!String.prototype.includes) { String.prototype.includes = function(x) { return this.indexOf(x) !== -1; }; }\n"
                    + "if (!String.prototype.startsWith) { String.prototype.startsWith = function(x) { return this.indexOf(x) === 0; }; }\n"
                    + "if (!String.prototype.endsWith) { String.prototype.endsWith = function(x) { return this.slice(-x.length) === x; }; }\n"
                    + "if (!Array.from) { Array.from = function(a) { return Array.prototype.slice.call(a); }; }\n";

    private final Scriptable scope;

    public Signer(List<String> jsSources) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1); // Rhino 在 Android 上必须解释执行
            cx.setLanguageVersion(Context.VERSION_ES6); // 算法 JS 用了 let/解构赋值
            scope = cx.initStandardObjects();
            cx.evaluateString(scope, ENV_PRELUDE, "env", 1, null);
            // 老 Rhino 缺 Map/Set，而抖音签名 JS 用了 Map —— 必须在加载签名 JS 之前补上
            cx.evaluateString(scope, ES6_POLYFILL, "polyfill", 1, null);
            int i = 0;
            for (String src : jsSources) {
                cx.evaluateString(scope, src, "sign-" + (i++) + ".js", 1, null);
            }
        } finally {
            Context.exit();
        }
    }

    /**
     * 对原始 query 串生成 a_bogus。
     *
     * ⚠️ 2026-10-02 加 synchronized（代码审计 M3）：整个 App 只建一个 Signer（"我的"页喜欢/看过
     * 两条线程共用一个 mApi），而 Rhino 的 `scope` 是**共享可变对象**——两个线程同时在一个
     * scope 上执行 JS，会互相踩隐式全局变量（assets/sign/utils.js 里就有 `x = ...` 这种写法），
     * 结果是签名算错 → 服务端返回空数据 / 拉取失败。
     * 症状极像"抖音改版了"，会把真正的失效信号淹没，所以在这里一刀切掉。
     * 签名本身是毫秒级，串行化的代价可以忽略。
     */
    public synchronized String makeABogus(String query) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            Object fnObj = scope.get("makeABogus", scope);
            if (!(fnObj instanceof Function)) {
                throw new IllegalStateException("makeABogus 未定义（JS 加载顺序不对？utils→sm3→vm_decode）");
            }
            Function fn = (Function) fnObj;
            Object ret = fn.call(cx, scope, scope, new Object[] {query, 0});
            return Context.toString(ret);
        } finally {
            Context.exit();
        }
    }
}
