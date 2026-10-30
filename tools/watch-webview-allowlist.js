// 决定性检查：这块手表到底允许哪些包当 WebView 提供者？
// Android 10+ 的规则写在 framework-res.apk 的 config_webview_packages 里（包名 + 签名摘要）。
// 这里直接在设备上从 framework-res.apk 里把候选包名抠出来（不需要 pull 几十 MB）。
const { execFileSync } = require('child_process');
// 本机 adb 路径：默认按 Windows SDK 常见位置，可用环境变量 ADB 覆盖
const ADB = process.env.ADB || 'E:\\sdk\\platform-tools\\adb.exe';
function dsh(cmd) {
  try {
    return execFileSync(ADB, ['shell', cmd], { encoding: 'utf8', maxBuffer: 64e6, stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (e) { return (e.stdout || '').toString() || '(失败)'; }
}

console.log('=== framework-res.apk 位置与大小 ===');
console.log('  ' + dsh('ls -l /system/framework/framework-res.apk').trim());

console.log('\n=== 里面出现的 webview/chrome 相关包名（= 白名单候选）===');
const hits = dsh("grep -a -o -E '[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z0-9_.]*[Ww]eb[Vv]iew[a-zA-Z0-9_.]*|[a-zA-Z][a-zA-Z0-9_]*\\.[a-zA-Z0-9_.]*chrome[a-zA-Z0-9_.]*' /system/framework/framework-res.apk | sort -u | head -40");
console.log(hits.trim() || '  (无匹配)');

console.log('\n=== 系统里所有可能当提供者的包 ===');
console.log(dsh("pm list packages | grep -iE 'webview|chrome|trichrome'").trim() || '  (无)');

console.log('\n=== 当前 webviewupdate 状态（权威）===');
console.log(dsh('dumpsys webviewupdate').trim());

console.log('\n=== 试探：能否切换到另一个包（用一个不存在的包名，只为看报错机制）===');
console.log(dsh('cmd webviewupdate set-webview-implementation com.example.notexist').trim() || '(无输出)');
