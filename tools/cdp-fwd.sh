#!/bin/bash
# 自动重接 WebView 的 CDP 转发（App 每次重启 pid 都会变，手工找太容易忘）
# 用法: tools/cdp-fwd.sh   然后照常用 node tools/cdp-eval.js @xxx.js
A=/e/sdk/platform-tools/adb.exe
[ -f "$A" ] || A="E:/sdk/platform-tools/adb.exe"
S=$("$A" shell "cat /proc/net/unix" 2>/dev/null | grep -o "webview_devtools_remote_[0-9]*" | sort -u | head -1)
if [ -z "$S" ]; then echo "没找到 webview 调试套接字（App 开着吗？是 debug 包吗？）"; exit 1; fi
"$A" forward tcp:9222 "localabstract:$S" >/dev/null 2>&1
echo "已转发 $S -> tcp:9222"
