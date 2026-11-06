package com.dywatch.app.kernel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dywatch.app.BuildConfig;
import com.dywatch.app.util.AppLog;

/**
 * 内核验证用调试入口（**只在 kernel 变体里存在**）。
 *
 * 为什么需要它：真机上点坐标太脆（按钮位置随页面/滚动变化，测了十几次有一半点在空处），
 * 没法稳定判断"内核到底能不能把一次点赞做成"。这里用广播直接调内核接口，结果进 AppLog：
 *
 *   adb shell am broadcast -a com.dywatch.app.KERNEL_TEST \
 *       --es kind like --es id 7698... --ez want true
 *
 * ⚠️ 只用于验证，不接任何用户路径；webview 变体的清单里没有这个 receiver。
 *
 * ⚠️ 2026-11-07 代码审计 H1 修复（原来这个入口对同设备任意应用开放）：
 *     ① 清单里加了 signature 级权限 `com.dywatch.app.permission.KERNEL_TEST`；
 *     ② 这里再查两道：调用方必须是本包（或同签名的本项目构建）**且**当前构建必须是
 *        debuggable —— release 包装上了也不给第三方 App 留写操作入口。
 *     调试怎么用：
 *        · devdiag 包（release 同签名 + debuggable）：`adb shell am broadcast` 直接可用；
 *        · release 包：入口自动失效（记一条日志就返回），无需另删代码。
 */
public class KernelTestReceiver extends BroadcastReceiver {

    private static final String PERM = "com.dywatch.app.permission.KERNEL_TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        // ① 只在可调试构建里生效（release 用户拿不到这个后门）
        if (!BuildConfig.DEBUG) {
            AppLog.i("kernel", "调试广播在 release 构建中被忽略（审计 H1）");
            return;
        }
        // ② 调用方必须是本包或同签名应用（权限已挡一层，这里再确认，防 OEM 权限判定差异）
        if (!isTrustedCaller(context)) {
            AppLog.i("kernel", "调试广播调用方不受信任，已拒绝");
            return;
        }

        String kind = intent.getStringExtra("kind");
        String id = intent.getStringExtra("id");
        String name = intent.getStringExtra("name");
        String text = intent.getStringExtra("text");
        boolean want = intent.getBooleanExtra("want", true);
        if (kind == null) {
            AppLog.i("kernel", "调试广播缺参数 kind");
            return;
        }
        // 内核聊天线单独验证，不再借"点赞"的通用入口。
        // 例：kind=chatList / kind=chatOpen&name=... / kind=chatMessages / kind=chatSend&text=...
        if (kind.startsWith("chat")) {
            GeckoKernel.chat(context, kind, name == null ? "" : name, text == null ? "" : text);
            return;
        }
        if (id == null) {
            AppLog.i("kernel", "调试广播缺参数（kind/id）");
            return;
        }
        AppLog.i("kernel", "调试广播触发 " + kind + " aweme=" + id + " want=" + want);
        String reqId = KernelSeam.action(context, kind, id, want, new KernelSeam.Callback() {
            @Override
            public void onResult(boolean ok, String detail) {
                AppLog.i("kernel", "调试广播结果 ok=" + ok + " detail=" + detail);
            }
        });
        AppLog.i("kernel", "调试广播下发 reqId=" + reqId
                + (reqId == null ? "（内核未就绪，已回落）" : ""));
    }

    /**
     * 调用方是否可信。
     *
     * 说明（2026-11-07 审计 H1）：真正挡住第三方的门是**清单里的 signature 级权限** ——
     * 系统在进 onReceive 之前就把非本签名的发送方拦掉了；这里做的是第二道：
     * ① 只在 debuggable 构建里生效（release 包即使用户被诱导装了也不留后门）；
     * ② 本进程必须真的持有那个权限（防清单被裁剪/合并异常导致"权限形同未声明"）。
     *
     * ⚠️ 没有用 `BroadcastReceiver.getSendingUid()`：那是 @hide API（编译期就找不到符号），
     *    而且普通广播的发送方身份在接收时并不可靠。所以这里不猜，直接按上面两条判。
     */
    private boolean isTrustedCaller(Context ctx) {
        try {
            return ctx.checkCallingOrSelfPermission(PERM)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }
}
