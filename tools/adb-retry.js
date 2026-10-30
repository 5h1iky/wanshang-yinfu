// 带自动重试的 adb 封装（用户提示：非官方充电器，连接可能不稳，断了要多试几次）。
// 用法：const adb = require('./adb-retry'); adb.sh('getprop ro.product.model')
const { execSync, execFileSync } = require('child_process');
// 本机 adb 路径：默认按 Windows SDK 常见位置，可用环境变量 ADB 覆盖
const ADB = process.env.ADB || 'E:\\sdk\\platform-tools\\adb.exe';

function sleep(ms) { try { execSync(`powershell -NoProfile -Command "Start-Sleep -Milliseconds ${ms}"`, { stdio: 'ignore' }); } catch (e) {} }

/** 当前设备状态：device / offline / unauthorized / 空(没设备) */
function state() {
  try {
    const out = execFileSync(ADB, ['devices'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    const line = out.split('\n').find(l => /\t/.test(l) && !/List of devices/.test(l));
    if (!line) return { s: 'none' };
    const [id, s] = line.split('\t').map(x => x.trim());
    return { s, id };
  } catch (e) { return { s: 'error' }; }
}

/** 等设备可用（必要时重启 adb 服务）。返回 true=可用 */
function waitDevice(retries = 6) {
  for (let i = 0; i < retries; i++) {
    const st = state();
    if (st.s === 'device') return true;
    if (st.s === 'unauthorized') {
      console.error('  [adb] 手表等待授权——请在手表上点「允许」');
    } else if (st.s === 'offline' || st.s === 'none' || st.s === 'error') {
      console.error(`  [adb] 状态=${st.s}，第 ${i + 1}/${retries} 次尝试重连…`);
      try { execFileSync(ADB, ['kill-server'], { stdio: 'ignore' }); } catch (e) {}
      sleep(800);
      try { execFileSync(ADB, ['start-server'], { stdio: 'ignore' }); } catch (e) {}
    }
    sleep(2500);
  }
  return state().s === 'device';
}

/** 跑一条设备侧命令，失败自动重试（含设备重连） */
function sh(cmd, retries = 5) {
  for (let i = 0; i < retries; i++) {
    if (state().s !== 'device') {
      if (!waitDevice(4)) continue;
    }
    try {
      return execFileSync(ADB, ['shell', cmd], {
        encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'],
      });
    } catch (e) {
      // 设备侧命令本身返回非 0（如 grep 无匹配）→ 不算连接问题，直接返回
      const out = (e.stdout || '').toString();
      if (out) return out;
      console.error(`  [adb] 「${cmd}」第 ${i + 1}/${retries} 次失败，重试…`);
      sleep(1500);
    }
  }
  return '';
}

module.exports = { sh, waitDevice, state, ADB };
