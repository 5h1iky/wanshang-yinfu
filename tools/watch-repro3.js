// 手表复现 v3：屏幕常亮 + 每步验证状态 + 自动关弹窗 + 进聊天页 + 抓日志
const { execFileSync } = require('child_process');
const adb = require('./adb-retry');
const ADB = adb.ADB;
function sleep(ms) { try { execFileSync('powershell', ['-NoProfile', '-Command', `Start-Sleep -Milliseconds ${ms}`], { stdio: 'ignore' }); } catch (e) {} }
function raw(args) {
  try { return execFileSync(ADB, args, { encoding: 'utf8', maxBuffer: 64e6, stdio: ['ignore', 'pipe', 'ignore'] }); }
  catch (e) { return (e.stdout || '').toString(); }
}
function wake() { adb.sh('input keyevent KEYCODE_WAKEUP'); }
function nodes() {
  wake();
  const xml = adb.sh('uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml');
  return [...xml.matchAll(/text="([^"]*)"[^>]*bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/g)]
    .filter(m => m[1].trim())
    .map(m => ({ text: m[1], cx: Math.round((+m[2] + +m[4]) / 2), cy: Math.round((+m[3] + +m[5]) / 2) }));
}
function show(label, n = 12) {
  console.log('  [' + label + '] ' + (nodes().slice(0, n).map(x => `"${x.text}"@${x.cx},${x.cy}`).join('  ') || '(空)'));
}
function tap(re) {
  const hit = nodes().find(x => re.test(x.text));
  if (!hit) return false;
  wake();
  adb.sh(`input tap ${hit.cx} ${hit.cy}`);
  return hit;
}

raw(['logcat', '-c']);
console.log('[1] 保持常亮 + 启动 App');
adb.sh('svc power stayon usb');
adb.sh('input keyevent KEYCODE_WAKEUP');
sleep(800);
adb.sh('am force-stop com.dywatch.app'); sleep(1000);
adb.sh('am start -n com.dywatch.app/.MainActivity'); sleep(7000);
show('启动后');

console.log('[2] 关公告弹窗');
for (let i = 0; i < 4; i++) {
  const t = tap(/^知道了$/);
  if (!t) { console.log('   (没有弹窗了)'); break; }
  console.log(`   点了「知道了」@ ${t.cx},${t.cy}`);
  sleep(2000);
}
show('关窗后');

console.log('[3] 进聊天页');
const c = tap(/^(聊天|私信)$/);
if (!c) { console.log('   ⚠️ 未找到聊天入口'); }
else {
  console.log(`   点了「${c.text}」@ ${c.cx},${c.cy}`);
  sleep(30000);
  show('聊天页（30 秒后）', 16);
}

console.log('\n[4] App 日志:');
raw(['logcat', '-d', '-s', 'dywatch:I', '*:S']).split('\n').filter(l => /dywatch/.test(l))
  .forEach(l => console.log('  ' + l.replace(/^.*?dywatch\s*:\s*/, '')));

console.log('\n[5] Chromium/WebView 报错:');
const cr = raw(['logcat', '-d', '-s', 'chromium:E', 'chromium:W', 'WebViewFactory:E', 'libc:E', '*:S'])
  .split('\n').filter(l => l.trim() && !/^---/.test(l));
console.log(cr.length ? cr.slice(0, 40).map(l => '  ' + l.trim()).join('\n') : '  (无)');

console.log('\n[6] 系统是否杀过 App / 渲染进程:');
const kill = raw(['logcat', '-d', '-s', 'ActivityManager:I', 'lowmemorykiller:I', '*:S'])
  .split('\n').filter(l => /dywatch|webview|render/i.test(l));
console.log(kill.length ? kill.slice(0, 25).map(l => '  ' + l.trim()).join('\n') : '  (无相关记录)');
