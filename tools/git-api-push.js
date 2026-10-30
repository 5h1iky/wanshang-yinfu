// 当 github.com:443 被墙（git push 不通）但 api.github.com 可达时，用 GitHub Git Data API
// 把本地未推送的提交原样复制到远端。
//
// 用法：node tools/git-api-push.js [owner/repo] [branch]
// 前提：本机已 `gh auth login`（脚本用 `gh auth token` 取令牌）
//
// 原理：逐个提交走 blobs → trees(base_tree=父提交的树) → commits → 更新 ref。
// 复制 author/committer 的名称、邮箱、时间戳与**时区偏移**，因此 sha 通常与本地完全一致；
// 若不一致（API 归一化时间戳），内容仍一致，本地执行 git fetch && git reset --hard origin/<branch> 即可对齐。

const { execSync } = require('child_process');

const repo = process.argv[2] || '5h1iky/wanshang-yinfu';
const branch = process.argv[3] || 'main';

function sh(cmd, opts = {}) {
  return execSync(cmd, { encoding: opts.buffer ? 'buffer' : 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts }).toString().trim();
}
function shBuf(cmd) {
  return execSync(cmd, { maxBuffer: 64 * 1024 * 1024 });
}

const token = sh('gh auth token');
const API = 'https://api.github.com';

async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: {
      Authorization: 'Bearer ' + token,
      Accept: 'application/vnd.github+json',
      'User-Agent': 'wanshang-yinfu-api-push',
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* ignore */ }
  if (!res.ok) {
    throw new Error(`${method} ${path} -> HTTP ${res.status}: ${(json && json.message) || text.slice(0, 300)}`);
  }
  return json;
}

/** 解析 git cat-file -p <sha> 的头部（tree/parent/author/committer）+ message */
function readCommit(sha) {
  const raw = sh(`git cat-file -p ${sha}`);
  const [head, ...rest] = raw.split('\n\n');
  const lines = head.split('\n');
  const tree = lines.find(l => l.startsWith('tree ')).slice(5).trim();
  const parents = lines.filter(l => l.startsWith('parent ')).map(l => l.slice(7).trim());
  const authorLine = lines.find(l => l.startsWith('author ')).slice(7);
  const committerLine = lines.find(l => l.startsWith('committer ')).slice(10);
  const message = rest.join('\n\n');
  return { tree, parents, author: parseIdent(authorLine), committer: parseIdent(committerLine), message };
}

/** "Name <email> 1790493491 +0800" → {name, email, date(ISO with offset)} */
function parseIdent(line) {
  const m = line.match(/^(.*) <(.*)> (\d+) ([+-]\d{4})$/);
  if (!m) throw new Error('cannot parse ident: ' + line);
  const [, name, email, epoch, tz] = m;
  const d = new Date(Number(epoch) * 1000);
  const pad = n => String(n).padStart(2, '0');
  // 用原始时区偏移构造 ISO，保证时间戳与偏移都与本地一致
  const sign = tz[0], hh = tz.slice(1, 3), mm = tz.slice(3, 5);
  const local = new Date(d.getTime() + (sign === '-' ? -1 : 1) * (Number(hh) * 60 + Number(mm)) * 60000);
  const iso = `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}T` +
    `${pad(local.getUTCHours())}:${pad(local.getUTCMinutes())}:${pad(local.getUTCSeconds())}${sign}${hh}:${mm}`;
  return { name, email, date: iso };
}

/** ISO(带偏移) → epoch 秒 */
function toEpoch(iso) {
  return Math.floor(Date.parse(iso) / 1000);
}
/** ISO(带偏移) → git 时区串（+0800 / +0000 …） */
function toTz(iso) {
  const m = iso.match(/(Z|[+-]\d{2}:\d{2})$/);
  if (!m || m[1] === 'Z') return '+0000';
  return m[1].replace(':', '');
}

function changedFiles(sha) {
  // ⚠️ 不能用 `sha^`：Windows 的 cmd.exe 会把 ^ 当转义符吃掉（曾导致 diff 为空 → 422）。
  // 统一用 `git log -1 --format=%P` 取父提交，再显式传给 diff-tree。
  const parents = sh(`git log -1 --format=%P ${sha}`).split(' ').filter(Boolean);
  const args = parents.length ? `${parents[0]} ${sha}` : `--root ${sha}`;
  const out = sh(`git diff-tree -r --name-status ${args}`);
  return out.split('\n').filter(Boolean).map(l => {
    const [status, ...pathParts] = l.split('\t');
    return { status: status[0], path: pathParts.join('\t') };
  });
}

function treeOf(sha) {
  // 同理避免 `sha^{tree}`
  return sh(`git show -s --format=%T ${sha}`);
}

function fileMode(sha, path) {
  const out = sh(`git ls-tree ${sha} "${path}"`);
  return out.split(/\s+/)[0] || '100644';
}

(async () => {
  const ref = await api('GET', `/repos/${repo}/git/ref/heads/${branch}`);
  let remoteHead = ref.object.sha;
  const baseRemoteHead = remoteHead;
  /** 本地 sha → 归一化后的远端 sha（GitHub 会去掉 message 末尾换行，其余保持不变） */
  const syncMap = new Map();
  console.log('remote head: ' + remoteHead);

  const localHead = sh('git rev-parse HEAD');
  console.log('local  head: ' + localHead);
  if (remoteHead === localHead) { console.log('已同步，无需推送'); return; }

  const pending = sh(`git rev-list --reverse ${remoteHead}..${localHead}`).split('\n').filter(Boolean);
  if (!pending.length) { console.log('远端不是本地祖先（历史分叉），请人工处理'); process.exit(1); }
  console.log(`待推送提交 ${pending.length} 个:`);
  pending.forEach(s => console.log('  ' + s.slice(0, 8) + ' ' + sh(`git log -1 --format=%s ${s}`).slice(0, 70)));

  for (const sha of pending) {
    const c = readCommit(sha);
    // 父必须是"当前远端头"——本地父经归一化后 sha 会变，所以按映射判断而不是直接比 sha
    const localParent = c.parents[0];
    const expectedParent = syncMap.get(localParent) || baseRemoteHead;
    if (expectedParent !== remoteHead) {
      throw new Error(`提交 ${sha.slice(0, 8)} 的父链对不上：期望 ${expectedParent?.slice(0, 8)}，当前远端 ${remoteHead.slice(0, 8)}`);
    }
    const files = changedFiles(sha);
    const entries = [];
    for (const f of files) {
      if (f.status === 'D') {
        entries.push({ path: f.path, mode: '100644', type: 'blob', sha: null });
        continue;
      }
      const content = shBuf(`git show ${sha}:${f.path}`);
      const blob = await api('POST', `/repos/${repo}/git/blobs`, {
        content: content.toString('base64'),
        encoding: 'base64',
      });
      entries.push({ path: f.path, mode: fileMode(sha, f.path), type: 'blob', sha: blob.sha });
    }

    // base_tree = 父提交在远端的树
    const parentTree = (await api('GET', `/repos/${repo}/git/commits/${remoteHead}`)).tree.sha;
    const tree = await api('POST', `/repos/${repo}/git/trees`, { base_tree: parentTree, tree: entries });
    const localTree = treeOf(sha);
    console.log(`  tree ${tree.sha.slice(0, 8)} ${tree.sha === localTree ? '== 本地树 OK' : '!= 本地树 ' + localTree.slice(0, 8) + ' ⚠️'}`);

    const commit = await api('POST', `/repos/${repo}/git/commits`, {
      message: c.message.replace(/\n$/, ''),   // GitHub 会去掉末尾换行，这里主动对齐
      tree: tree.sha,
      parents: [remoteHead],
      author: c.author,
      committer: c.committer,
    });

    // 在本地重建同一个对象（父用远端 sha），使本地/远端链路完全一致
    const header = `tree ${tree.sha}\nparent ${remoteHead}\n` +
      `author ${c.author.name} <${c.author.email}> ${toEpoch(c.author.date)} ${toTz(c.author.date)}\n` +
      `committer ${c.committer.name} <${c.committer.email}> ${toEpoch(c.committer.date)} ${toTz(c.committer.date)}\n\n`;
    const body = header + c.message.replace(/\n$/, '');
    // 用 stdin 传内容，避免 shell 重定向（Windows 下会触发 Node/libuv 断言崩溃）
    const rebuilt = execSync('git hash-object -t commit -w --stdin', { input: body, encoding: 'utf8' }).trim();

    console.log(`  commit ${commit.sha.slice(0, 8)} ${commit.sha === rebuilt ? '(本地同 sha ✓)' : '(本地重建 ' + rebuilt.slice(0, 8) + ' ⚠️)'}`);
    syncMap.set(sha, commit.sha);

    await api('PATCH', `/repos/${repo}/git/refs/heads/${branch}`, { sha: commit.sha, force: false });
    remoteHead = commit.sha;
    console.log('  -> ref 已更新');
  }
  console.log('推送完成：' + remoteHead);

  // 本地分支改指归一化后的链（内容完全一致，只是 sha 与 GitHub 一致）
  const finalLocal = syncMap.get(localHead);
  if (finalLocal) {
    sh(`git update-ref refs/heads/${branch} ${finalLocal}`);
    sh(`git update-ref refs/remotes/origin/${branch} ${finalLocal}`);
    console.log('✅ 本地已对齐：' + branch + ' = origin/' + branch + ' = ' + finalLocal.slice(0, 12));
  } else {
    console.log('⚠️ 未找到本地头对应的远端 sha，请人工核对（内容不受影响）');
  }
})().catch(e => { console.error('FAILED: ' + e.message); process.exit(1); });
