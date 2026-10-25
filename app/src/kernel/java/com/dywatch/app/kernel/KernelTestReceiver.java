package com.dywatch.app.kernel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

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
 */
public class KernelTestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String kind = intent.getStringExtra("kind");
        String id = intent.getStringExtra("id");
        boolean want = intent.getBooleanExtra("want", true);
        if (kind == null || id == null) {
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
}
