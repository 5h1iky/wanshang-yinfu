// API 推送后的"回同步"：让本地提交与 GitHub API 创建的提交**同 sha**（已实测确认的规则）。
//
// 实测结论（2026-09-30，brute-commit 爆破验证）：
//   GitHub Git Data API 创建提交时，唯一改动是**去掉 commit message 末尾的换行**，
//   author/committer 的名称、邮箱、时间戳与时区偏移都原样保留。
//   因此把本地提交对象按此规则重建，hash 即与远端 sha 完全一致。
//
// 用法：node tools/git-api-syncback.js <remoteSha> <localShaWithSameTree> [owner/repo] [branch]

const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

function sh(cmd) { return execSync(cmd, { encoding: 'utf8', maxBuffer: 64e6 }).trim(); }

const remoteSha = process.argv[2];
const localSha = process.argv[3];
const repo = process.argv[4] || '5h1iky/wanshang-yinfu';
const branch = process.argv[5] || 'main';
if (!remoteSha || !localSha) {
  console.error('usage: node git-api-syncback.js <remoteSha> <localShaWithSameTree> [owner/repo] [branch]');
  process.exit(1);
}

(async () => {
  const localRaw = execSync(`git cat-file commit ${localSha}`, { maxBuffer: 64e6 }).toString('utf8');
  const sep = localRaw.indexOf('\n\n');
  const localHeader = localRaw.slice(0, sep);
  const localMsg = localRaw.slice(sep + 2);
  const localTree = sh(`git show -s --format=%T ${localSha}`);

  // 远端元数据（核对树/父是否一致；sha 规则已实测，无需再取 message）
  const token = sh('gh auth token');
  const res = await fetch(`https://api.github.com/repos/${repo}/git/commits/${remoteSha}`, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json', 'User-Agent': 'syncback' },
  });
  if (!res.ok) throw new Error('HTTP ' + res.status + ' ' + (await res.text()).slice(0, 200));
  const j = await res.json();
  if (j.tree.sha !== localTree) throw new Error(`树不一致：本地 ${localTree} vs 远端 ${j.tree.sha}`);

  // 按实测规则重建：本地 header（含原时区）+ message 去掉末尾换行
  // ⚠️ 用 execSync 的 input 走 stdin，别用 shell 的 `< file` 重定向（会触发 Node/libuv 崩溃）
  const rebuiltBody = localHeader + '\n\n' + localMsg.replace(/\n$/, '');
  const rebuilt = execSync('git hash-object -t commit -w --stdin', { input: rebuiltBody, encoding: 'utf8' }).trim();

  console.log('重建 sha: ' + rebuilt);
  console.log('远端 sha: ' + remoteSha);
  if (rebuilt !== remoteSha) {
    console.error('重建结果与远端不一致——规则可能又变了，请用 brute-commit.js 重新爆破');
    process.exit(1);
  }

  sh(`git update-ref refs/heads/${branch} ${remoteSha}`);
  sh(`git update-ref refs/remotes/origin/${branch} ${remoteSha}`);
  console.log('✅ 已对齐：' + branch + ' 与 origin/' + branch + ' 均指向 ' + remoteSha.slice(0, 12));
})();
