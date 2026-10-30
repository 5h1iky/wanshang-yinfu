// 手表体检（带重试版）：所有 adb 调用走 adb-retry，连接不稳也能跑完
const adb = require('./adb-retry');

function p(l, v) { console.log('  ' + String(l).padEnd(16) + (v === '' ? '(空)' : v)); }
function pick(text, re, label, max = 6) {
  const lines = String(text).split('\n').filter(l => re.test(l)).slice(0, max);
  console.log('  ' + label + ':');
  if (!lines.length) { console.log('    (无匹配)'); return; }
  lines.forEach(l => console.log('    ' + l.trim()));
}

console.log('等待手表… 状态=' + adb.state().s);
if (!adb.waitDevice(8)) { console.error('手表不可用，请检查连接后重跑'); process.exit(1); }

console.log('\n=== 设备 ===');
p('型号', adb.sh('getprop ro.product.model').trim());
p('Android', adb.sh('getprop ro.build.version.release').trim() + ' (SDK ' + adb.sh('getprop ro.build.version.sdk').trim() + ')');
p('ABI', adb.sh('getprop ro.product.cpu.abi').trim());

console.log('\n=== WebView（私信 100% 依赖它）===');
p('webview 包', adb.sh('pm list packages | grep -i webview').trim().replace(/\n/g, ' '));
pick(adb.sh('pm dump com.android.webview'), /versionName|versionCode|codePath/, 'webview 版本', 4);
pick(adb.sh('dumpsys webviewupdate'), /Current WebView package|Valid WebView|Fallback/, '当前实现', 6);

console.log('\n=== 本 App ===');
p('已安装', adb.sh('pm list packages | grep dywatch').trim().replace(/\n/g, ' '));
pick(adb.sh('dumpsys package com.dywatch.app'), /versionName|versionCode|firstInstallTime|lastUpdateTime/, '版本信息', 6);

console.log('\n=== 内存 ===');
adb.sh('cat /proc/meminfo').split('\n').slice(0, 3).forEach(l => console.log('  ' + l.trim()));
p('堆上限', adb.sh('getprop dalvik.vm.heapsize').trim());

console.log('\n=== 屏幕 ===');
console.log('  ' + adb.sh('wm size').trim().replace(/\n/g, ' | '));
console.log('  ' + adb.sh('wm density').trim().replace(/\n/g, ' | '));

console.log('\n=== 前台 Activity ===');
console.log('  ' + (adb.sh('dumpsys activity activities').split('\n').filter(l => /ResumedActivity/.test(l)).slice(0, 2).join('\n  ').trim() || '(无)'));
