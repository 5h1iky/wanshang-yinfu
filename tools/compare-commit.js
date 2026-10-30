// 对比本地提交对象与远端（API 推送后）的字段，找出 sha 不一致的原因。
const { execSync } = require('child_process');
function sh(cmd) { return execSync(cmd, { encoding: 'utf8', maxBuffer: 64e6 }).trim(); }
const localSha = process.argv[2];
const remoteSha = process.argv[3];

(async () => {
  const local = sh(`git cat-file -p ${localSha}`);
  console.log('=== 本地提交对象 ===');
  console.log(JSON.stringify(local.split('\n\n')[0].split('\n'), null, 1));
  console.log('message 尾部字节: ' + JSON.stringify(local.split('\n\n').slice(1).join('\n\n').slice(-20)));

  const token = sh('gh auth token');
  const res = await fetch(`https://api.github.com/repos/5h1iky/wanshang-yinfu/git/commits/${remoteSha}`, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json', 'User-Agent': 'x' },
  });
  const j = await res.json();
  console.log('=== 远端提交（API）===');
  console.log(JSON.stringify({ tree: j.tree.sha, parents: j.parents.map(p => p.sha), author: j.author, committer: j.committer }, null, 1));
  console.log('message 长度: ' + (j.message || '').length);
})();
