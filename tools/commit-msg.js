// ??? BOM UTF-8 ????????PowerShell 5.1 Out-File utf8 ? BOM???? git log?
// ??: node tools/commit-msg.js "???" "???2" "???3" ...
const fs = require('fs');
const lines = process.argv.slice(2);
if (!lines.length) { console.error('??: node tools/commit-msg.js <??> [??...]'); process.exit(1); }
fs.writeFileSync('D:/dev/dywatch/.cm.txt', lines.join('\n') + '\n', 'utf8');
console.log('?? .cm.txt?' + lines.length + ' ??? BOM?');
