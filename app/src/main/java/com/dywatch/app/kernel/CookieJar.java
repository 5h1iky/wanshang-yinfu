package com.dywatch.app.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 `LoginManager` 存的 cookie 头（`k=v; k2=v2`）拆成内核扩展能吃的结构。
 *
 * 纯 Java（不依赖 GeckoView），所以放在 main 源集，两个变体都能编、也便于单测。
 * 拆出来的每条是 `{name, value, httpOnly}`；httpOnly 我们这里只能给 false ——
 * CookieManager 的 `getCookie` 头本身不携带该属性，而 WebView 那边真实值仍保留在系统 cookie 仓里，
 * 内核侧只需要"页面请求能带上"这一条语义（实测 ttwid 这类 cookie 不带 httpOnly 也能被站点接受）。
 */
public final class CookieJar {

    private CookieJar() {}

    public static List<Map<String, Object>> fromHeader(String header) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (header == null) return out;
        for (String part : header.split(";")) {
            String seg = part.trim();
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            if (eq <= 0) continue;
            String name = seg.substring(0, eq).trim();
            String value = seg.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", name);
            c.put("value", value);
            c.put("httpOnly", false);
            out.add(c);
        }
        return out;
    }
}
