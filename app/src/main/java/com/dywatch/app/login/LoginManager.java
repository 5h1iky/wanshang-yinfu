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
                cm.setCookie(COOKIE_URL, p.trim());
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

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
