// 生成"无 BOM"的提交信息文件，供 `git commit -F .cm.txt` 使用。
// 起因：PowerShell 5.1 的 `Out-File utf8` 会写 BOM，提交信息里就会混进一个不可见字符，
// `git log` 与 GitHub 上的首行都会带出来。这里显式按 UTF-8 无 BOM 落盘。
// 输出位置：仓库根目录 .cm.txt（已被 .gitignore 挡住，不会进库）。
// 用法：node tools/commit-msg.js "标题" "正文第 1 段" "正文第 2 段" ...
const fs = require('fs');
const path = require('path');

const lines = process.argv.slice(2);
if (!lines.length) {
  console.error('用法: node tools/commit-msg.js <标题> [正文段落...]');
  process.exit(1);
}

const out = path.join(__dirname, '..', '.cm.txt');
fs.writeFileSync(out, lines.join('\n') + '\n', 'utf8');
console.log('已写入 ' + out + '（' + lines.length + ' 行，UTF-8 无 BOM）');
