// 用 API 返回的 message 原文重建提交对象，试出与远端 sha 一致的变体（定位归一化差异）。
const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
function sh(cmd, env) { return execSync(cmd, { encoding: 'utf8', maxBuffer: 64e6, env: { ...process.env, ...(env || {}) } }).trim(); }

const remoteSha = process.argv[2];
const localSha = process.argv[3];

(async () => {
  const token = sh('gh auth token');
  const res = await fetch(`https://api.github.com/repos/5h1iky/wanshang-yinfu/git/commits/${remoteSha}`, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json', 'User-Agent': 'x' },
  });
  const j = await res.json();

  const raw = execSync(`git cat-file commit ${localSha}`, { maxBuffer: 64e6 });
  const sep = raw.indexOf('\n\n');
  const localMsg = raw.subarray(sep + 2);
  const apiMsg = Buffer.from(j.message, 'utf8');
  console.log('本地 message 字节数: ' + localMsg.length);
  console.log('API  message 字节数: ' + apiMsg.length);
  console.log('本地尾部: ' + JSON.stringify(localMsg.subarray(-12).toString()));
  console.log('API  尾部: ' + JSON.stringify(apiMsg.subarray(-12).toString()));

  const header = `tree ${j.tree.sha}\n` +
    j.parents.map(p => `parent ${p.sha}\n`).join('') +
    `author ${j.author.name} <${j.author.email}> ${Math.floor(Date.parse(j.author.date) / 1000)} +0000\n` +
    `committer ${j.committer.name} <${j.committer.email}> ${Math.floor(Date.parse(j.committer.date) / 1000)} +0000\n\n`;

  const candidates = [
    ['apiMsg raw', header + j.message],
    ['apiMsg + \\n', header + j.message + '\n'],
    ['localMsg raw', header + localMsg.toString('utf8')],
    ['localMsg trimEnd', header + localMsg.toString('utf8').replace(/\s+$/, '')],
  ];
  for (const [label, body] of candidates) {
    const f = path.join(os.tmpdir(), 'c-' + Date.now() + '.txt');
    fs.writeFileSync(f, body);
    const h = sh(`git hash-object -t commit --stdin < "${f}"`);
    fs.rmSync(f, { force: true });
    console.log(`  ${label.padEnd(18)} -> ${h.slice(0, 12)} ${h === remoteSha ? '✅' : ''}`);
  }
  console.log('目标 = ' + remoteSha.slice(0, 12));
})();
