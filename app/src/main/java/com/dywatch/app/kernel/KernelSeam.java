package com.dywatch.app.kernel;

import com.dywatch.app.util.AppLog;

/**
 * 「内核构建」接缝（main 源集，**两个变体都会编进来**）。
 *
 * 为什么用反射：GeckoView 只有 `kernel` flavor 才依赖并打包；`webview` flavor 里那些类根本不存在。
 * 直接引用会让 webview 变体编不过（或运行时 NoClassDefFound）。这里用 {@link #enabled()} 先判构建，
 * 再用反射去调内核实现，webview 变体永远不会加载那个类。
 *
 * 0.9.0 的内核构建目前只接管**点赞/收藏**（见 调研\15 的小步路径）；
 * 私信仍走系统 WebView，桥迁移（K3）留到内核版下一篇。
 */
public final class KernelSeam {

    /** 内核返回结果用（与 ChatEngine.ActionListener 同语义） */
    public interface Callback {
        void onResult(boolean ok, String detail);
    }

    private KernelSeam() {}

    /** 当前构建是不是「装了内核」的那个 */
    public static boolean enabled() {
        return "kernel".equals(com.dywatch.app.BuildConfig.FLAVOR);
    }

    /**
     * 让内核执行一次互动。
     *
     * @return 请求号（非 null 表示已交给内核）；**null = 内核不可用/未就绪，调用方应回落 WebView 引擎**
     */
    public static String action(android.content.Context ctx, String kind, String awemeId,
                                boolean want, Callback cb) {
        if (!enabled()) return null;
        try {
            Class<?> c = Class.forName("com.dywatch.app.kernel.GeckoKernel");
            java.lang.reflect.Method m = c.getMethod("action", android.content.Context.class,
                    String.class, String.class, boolean.class, Callback.class);
            Object r = m.invoke(null, ctx, kind, awemeId, want, cb);
            return r instanceof String ? (String) r : null;
        } catch (Throwable t) {
            AppLog.i("kernel", "内核调用失败（将回落 WebView）: " + t);
            return null;
        }
    }

    /**
     * 调试用：显示/隐藏内核（Gecko）那一层视图，让用户能亲眼看到内核里渲染成什么样。
     * 只有 kernel 构建有这个视图；webview 构建调用无效。
     */
    public static void debugVisible(android.content.Context ctx, boolean visible) {
        if (!enabled()) return;
        try {
            Class<?> c = Class.forName("com.dywatch.app.kernel.GeckoKernel");
            c.getMethod("setDebugVisible", android.content.Context.class, boolean.class)
                    .invoke(null, ctx, visible);
        } catch (Throwable t) {
            AppLog.i("kernel", "切换内核页面失败: " + t);
        }
    }

    /**
     * kernel 变体的聊天通道。返回 null = 当前不是内核构建 / 内核类不存在，调用方回落 WebView。
     *
     * 这里仍然用反射：`KernelChatPort` 只存在于 `src/kernel` 源集，webview 变体不能直接引用。
     */
    public static com.dywatch.app.chat.ChatPort chatPort(
            android.app.Activity activity, com.dywatch.app.chat.ChatEngine.Listener listener) {
        if (!enabled()) return null;
        try {
            Class<?> c = Class.forName("com.dywatch.app.kernel.KernelChatPort");
            return (com.dywatch.app.chat.ChatPort) c
                    .getConstructor(android.app.Activity.class, com.dywatch.app.chat.ChatEngine.Listener.class)
                    .newInstance(activity, listener);
        } catch (Throwable t) {
            AppLog.i("kernel", "内核聊天通道不可用（回落 WebView）: " + t);
            return null;
        }
    }
}
