package com.dywatch.app.login;

// 登录态管理（架构 A）：WebView 官方登录页完成后从 CookieManager 取会话存本机。
// 红线 3：登录态只存用户自己的手表（SharedPreferences），开发者不持有任何凭证；不上传、不打日志。
//
// v2（真机踩坑修复）：WebView CookieStore 进程重启后会丢会话 cookie（实测：登录成功后
// 重启 App，/chat 出登录墙、profile/self=用户未登录，而 SharedPreferences 备份完好）。
// 对策两件套：①登录保存后 CookieManager.flush() 落盘；②App 启动/引擎启动前把备份 cookie
// 回写 CookieManager（只补缺失键，不覆盖 WebView 里的新值）。

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import com.dywatch.app.chat.ChatEngine;
import com.dywatch.app.util.AppLog;

import java.util.HashSet;
import java.util.Set;

public final class LoginManager {

    private static final String PREF = "dywatch_session";
    private static final String KEY_COOKIES = "cookies";
    private static final String KEY_VERIFIED = "verified";
    /** 会话 cookie 适用域（保存时即从该 URL 取得，回写同域） */
    private static final String COOKIE_URL = "https://www.douyin.com/";

    private LoginManager() {}

    public static void saveCookies(Context ctx, String cookieHeader) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_COOKIES, cookieHeader).apply();
        // 立即落盘 WebView cookie（否则进程被杀时 CookieStore 可能没刷盘 = 会话丢失）
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    public static String getCookies(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getString(KEY_COOKIES, "");
    }

    /** 是否已登录（有 sessionid 即认为有会话；有效性由服务端探测判定，过期走无痛重登） */
    public static boolean hasSession(Context ctx) {
        String c = getCookies(ctx);
        return c.contains("sessionid=");
    }

    /** 证据式标记：会话经过 profile/self 实测通过才算已验证（防中间态/秒死会话误报） */
    public static void setVerified(Context ctx, boolean ok) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_VERIFIED, ok).apply();
    }

    public static boolean isVerified(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_VERIFIED, false);
    }

    /**
     * 把备份 cookie 回写 WebView CookieStore（幂等；只补缺失键）。
     * 返回补齐的键数量，0 = 无需动作或无备份。
     */
    public static int restoreToCookieManager(Context ctx) {
        String backup = getCookies(ctx);
        if (backup == null || backup.length() == 0) return 0;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            Set<String> existing = new HashSet<>();
            String cur = cm.getCookie(COOKIE_URL);
            if (cur != null) {
                for (String p : cur.split(";")) {
                    int i = p.indexOf('=');
                    if (i > 0) existing.add(p.substring(0, i).trim());
                }
            }
            int filled = 0;
            for (String p : backup.split(";")) {
                int i = p.indexOf('=');
                if (i <= 0) continue;
                String name = p.substring(0, i).trim();
                if (name.length() == 0 || existing.contains(name)) continue;
                cm.setCookie(COOKIE_URL, withDouyinDomain(p.trim()));
                filled++;
            }
            if (filled > 0) {
                cm.flush();
                AppLog.i("login", "会话 cookie 已回写 WebView（补 " + filled + " 个键）");
            }
            return filled;
        } catch (Exception e) {
            AppLog.i("login", "cookie 回写失败: " + e);
            return 0;
        }
    }

    /**
     * 退出登录。
     *
     * ⚠️ 2026-10-01 用户反馈"清登录状态它清的只是软件，没清里面 Web 内核的登录状态"——**属实**，
     * 而且不只是"显示不一致"，是隐私问题：会话的真正载体是 WebView 的 CookieManager
     * （登录本来就是在这个 WebView 里做的），老实现只清了 SharedPreferences 里的**备份**，
     * 于是：App 界面显示"未登录"，可 WebView 引擎照样是登录态 —— 私信还能收发，
     * 把这块表给别人用，对方直接进的是你的账号。
     *
     * 所以这里必须三件一起做：
     *   ① 清 SharedPreferences（老逻辑）
     *   ② 清 WebView 的 cookie + WebStorage（localStorage 里也留过东西）
     *   ③ **把引擎销毁**：内存里已经加载的页面还带着旧会话，只删 cookie 不重建，页面照样能用
     */
    /**
     * 给回写的 cookie 补上 `Domain=.douyin.com`（纯函数，可 JVM 单测）。
     *
     * ⚠️ 为什么必须补（2026-10-01 实测踩到）：`CookieManager.setCookie(url, "k=v")` 写出来的是
     * **host-only** cookie，只对 `www.douyin.com` 生效；而原始登录会话是 `.douyin.com` 的**域 cookie**
     * （`imapi.*`、`lf*.douyinstatic.com` 等子域请求也要带上）。
     * 现象：页面能打开、私信页脚本也正常，**就是拉不到会话**——因为带会话的 API 请求没带上 cookie。
     * 修法：回写时显式带 Domain/Path。已有 Domain 的不重复追加。
     */
    static String withDouyinDomain(String cookiePair) {
        if (cookiePair == null || cookiePair.isEmpty()) return cookiePair;
        String lower = cookiePair.toLowerCase(java.util.Locale.US);
        StringBuilder sb = new StringBuilder(cookiePair);
        if (!lower.contains("domain=")) sb.append("; Domain=.douyin.com");
        if (!lower.contains("path=")) sb.append("; Path=/");
        return sb.toString();
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);   // API 21+；本应用只会访问抖音域，整体清掉即可
            cm.flush();
            AppLog.i("login", "退出登录：已清除 WebView cookie");
        } catch (Throwable t) {
            AppLog.i("login", "退出登录：清 WebView cookie 失败 " + t);
        }
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable t) {
            AppLog.i("login", "退出登录：清 WebStorage 失败 " + t);
        }
        try {
            ChatEngine.resetForLogout();
        } catch (Throwable t) {
            AppLog.i("login", "退出登录：重置聊天引擎失败 " + t);
        }
    }
}
