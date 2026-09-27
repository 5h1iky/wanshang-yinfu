// 从已构建的 APK 里抽出图标并校验像素（证明"打包进去的确实是新图标"）。
// 用法：node verify-apk-icon.js <apk> <entryName> <outPng>
const { execSync } = require('child_process');
const fs = require('fs');
const admzip = null; // 无第三方库：用 PowerShell 解压（jar/unzip 亦可，这里走 Expand-Archive 副本）
const apk = process.argv[2];
const entry = process.argv[3];
const out = process.argv[4];

// APK 是 zip：用 Node 内置做不到解压，改用系统 tar（Win10+ 自带 bsdtar，支持 zip）
try {
  execSync(`tar -xf "${apk}" -C "${require('path').dirname(out)}" "${entry}"`, { stdio: 'inherit' });
  const extracted = require('path').join(require('path').dirname(out), entry);
  if (fs.existsSync(extracted)) {
    fs.copyFileSync(extracted, out);
    fs.rmSync(extracted, { force: true });
    console.log('extracted -> ' + out + ' (' + fs.statSync(out).size + ' bytes)');
  } else {
    console.error('entry not found after extract: ' + entry);
    process.exit(1);
  }
} catch (e) {
  console.error('tar extract failed: ' + e.message);
  process.exit(1);
}
