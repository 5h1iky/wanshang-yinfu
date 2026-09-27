// 用 CDP/emulator 之外的手段在真机上模拟双击不可靠 → 直接用两个连续快速 tap。
// 但 input tap 间隔>300ms 不会被 GestureDetector 认成双击 —— 这里用 sendevent 太复杂，
// 换思路：uiautomator 的 input swipe 序列不行；正确姿势是 input tap 两次紧挨着发
// （adb 单条命令里 && 连接，间隔可压到 ~50ms）。
// 用法：node doubletap.js <x> <y>
const { execSync } = require('child_process');
const adb = 'E:\\sdk\\platform-tools\\adb.exe';
const x = process.argv[2], y = process.argv[3];
if (!x || !y) { console.error('usage: node doubletap.js <x> <y>'); process.exit(1); }
// input tap x y 连发两次；同一 adb shell 命令串行执行，实测间隔 ~30-80ms，足以触发 onDoubleTap
execSync(`"${adb}" shell input tap ${x} ${y} && "${adb}" shell input tap ${x} ${y}`,
        { shell: 'cmd.exe', stdio: 'inherit' });
console.log(`double-tap @(${x},${y}) sent`);
