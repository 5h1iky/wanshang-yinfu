// 暴力枚举：author/committer 时区 × message 尾部换行 → 找出与远端 sha 一致的对象组合
const { execSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
function hash(body) {
  const f = path.join(os.tmpdir(), 'bf-' + Date.now() + '-' + Math.random() + '.txt');
  fs.writeFileSync(f, body);
  const h = execSync(`git hash-object -t commit --stdin < "${f}"`, { encoding: 'utf8' }).trim();
  fs.rmSync(f, { force: true });
  return h;
}
const remoteSha = process.argv[2];
const localSha = process.argv[3];

(async () => {
  const token = execSync('gh auth token', { encoding: 'utf8' }).trim();
  const res = await fetch(`https://api.github.com/repos/5h1iky/wanshang-yinfu/git/commits/${remoteSha}`, {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json', 'User-Agent': 'x' },
  });
  const j = await res.json();
  const raw = execSync(`git cat-file commit ${localSha}`, { maxBuffer: 64e6 });
  const msg = raw.subarray(raw.indexOf('\n\n') + 2).toString('utf8');
  const epoch = Math.floor(Date.parse(j.author.date) / 1000);

  const zones = ['+0000', '+0800'];
  const msgVariants = [['nl', msg], ['nonl', msg.replace(/\n$/, '')]];
  let hit = null;
  for (const az of zones) {
    for (const cz of zones) {
      for (const [ml, m] of msgVariants) {
        const body = `tree ${j.tree.sha}\n` +
          j.parents.map(p => `parent ${p.sha}\n`).join('') +
          `author ${j.author.name} <${j.author.email}> ${epoch} ${az}\n` +
          `committer ${j.committer.name} <${j.committer.email}> ${epoch} ${cz}\n\n` + m;
        const h = hash(body);
        const mark = h === remoteSha ? '  ✅ 命中' : '';
        console.log(`author=${az} committer=${cz} msg=${ml.padEnd(5)} -> ${h.slice(0, 12)}${mark}`);
        if (h === remoteSha) hit = { az, cz, ml };
      }
    }
  }
  console.log(hit ? '命中组合: ' + JSON.stringify(hit) : '未命中（还有别的归一化）');
})();
