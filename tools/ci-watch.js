// 轮询最新一次 CI run 直到结束（或超时），打印步骤级结果。
// 用法：node tools/ci-watch.js [最长等待秒数]
const { execSync } = require('child_process');
const token = execSync('gh auth token', { encoding: 'utf8' }).trim();
const maxWait = Number(process.argv[2] || 600);
const H = { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json', 'User-Agent': 'ci-watch' };
const repo = '5h1iky/wanshang-yinfu';

async function get(path) {
  const r = await fetch('https://api.github.com/repos/' + repo + path, { headers: H });
  return r.json();
}

(async () => {
  const t0 = Date.now();
  let lastLine = '';
  while ((Date.now() - t0) / 1000 < maxWait) {
    const runs = await get('/actions/runs?per_page=1');
    const run = (runs.workflow_runs || [])[0];
    if (!run) { console.log('还没有 run'); return; }
    const line = run.head_sha.slice(0, 7) + ' ' + run.status + '/' + (run.conclusion || '-');
    if (line !== lastLine) { console.log('[' + Math.round((Date.now() - t0) / 1000) + 's] ' + line); lastLine = line; }
    if (run.status === 'completed') {
      const jobs = await get('/actions/runs/' + run.id + '/jobs');
      for (const job of (jobs.jobs || [])) {
        console.log('\njob: ' + job.name + ' -> ' + job.conclusion);
        (job.steps || []).forEach(s => console.log('   ' + (s.conclusion || s.status) + '  ' + s.name));
      }
      console.log('\nrun 页面: ' + run.html_url);
      process.exit(run.conclusion === 'success' ? 0 : 2);
    }
    await new Promise(r => setTimeout(r, 15000));
  }
  console.log('等待超时，run 仍在进行');
})();
