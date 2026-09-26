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

    private final Scriptable scope;

    public Signer(List<String> jsSources) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1); // Rhino 在 Android 上必须解释执行
            cx.setLanguageVersion(Context.VERSION_ES6); // 算法 JS 用了 let/解构赋值
            scope = cx.initStandardObjects();
            cx.evaluateString(scope, ENV_PRELUDE, "env", 1, null);
            int i = 0;
            for (String src : jsSources) {
                cx.evaluateString(scope, src, "sign-" + (i++) + ".js", 1, null);
            }
        } finally {
            Context.exit();
        }
    }

    /** 对原始 query 串生成 a_bogus */
    public String makeABogus(String query) {
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
