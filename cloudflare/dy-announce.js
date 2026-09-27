/**
 * 腕上音符 · 公告 & 版本镜像 Worker（Cloudflare Workers 免费版）
 *
 * 部署名：dy-announce  →  https://dy-announce.<子域>.workers.dev
 *
 * 接口：
 *   GET /announce   公告（KV 单条记录，改公告只改 KV，不用重新部署 Worker）
 *                   有公告 → 200 + JSON；无公告或已过期 → 204
 *   GET /version    GitHub Releases 的镜像兜底（国内直连 GitHub API 不稳时用）
 *   GET /           自述（人肉访问时的提示页）
 *
 * 设计口径（用户拍板）：
 *   - App 侧**启动时拉一次 + 进设置页拉一次**，不轮询、不弹窗；主屏顶部细 banner，点击消失
 *   - 无自有域名 → 走默认 workers.dev；国内访问不稳没关系，拉不到就静默不显示
 *   - 零成本：群友几十人，日请求几百次，远低于免费额度（10 万请求/天）
 *
 * 公告 JSON 结构（KV key = "current"）：
 *   {
 *     "id": "2026-09-27-01",          // 必填，用于本地记 lastReadId（点击消失后不再提示同一条）
 *     "level": "info|warn|danger",   // 可选，决定 banner 配色（info 默认）
 *     "title": "标题",                // 必填
 *     "text": "正文（可空）",
 *     "link": "https://…",           // 可选，点击 banner 附带打开
 *     "showUntil": 1790000000000     // 可选，毫秒时间戳；过期后自动返回 204
 *   }
 *
 * 敏感信息一律不放这里（Worker 只读取 KV，写用 API/控制台）。
 */

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,HEAD,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Cache-Control': 'no-store',
};

const REPO = '5h1iky/wanshang-yinfu';

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8' },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return json({ error: 'method not allowed' }, 405);
    }

    // ---- 公告 ----
    if (path === '/announce') {
      let raw = null;
      try {
        raw = await env.ANNOUNCE.get('current');
      } catch (e) {
        return json({ error: 'kv unavailable' }, 503);
      }
      if (!raw) return new Response(null, { status: 204, headers: CORS });

      let obj;
      try {
        obj = JSON.parse(raw);
      } catch (e) {
        return json({ error: 'announce payload is not valid json' }, 500);
      }
      // 过期公告自动静默（App 侧不用管时间）
      if (obj && obj.showUntil && Date.now() > Number(obj.showUntil)) {
        return new Response(null, { status: 204, headers: CORS });
      }
      return json(obj);
    }

    // ---- 版本镜像（GitHub Releases 兜底）----
    if (path === '/version') {
      const gh = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
        headers: { 'User-Agent': 'wanshang-yinfu-worker', Accept: 'application/vnd.github+json' },
        cf: { cacheTtl: 600, cacheEverything: true },   // 10 分钟边缘缓存，省 GitHub 限流
      });
      if (!gh.ok) return json({ error: 'upstream ' + gh.status }, 502);
      const rel = await gh.json();
      const apk = (rel.assets || []).find(a => /\.apk$/i.test(a.name) && /release/i.test(a.name))
        || (rel.assets || []).find(a => /\.apk$/i.test(a.name));
      return json({
        tag: rel.tag_name,
        name: rel.name,
        body: rel.body ? String(rel.body).slice(0, 4000) : '',
        publishedAt: rel.published_at,
        htmlUrl: rel.html_url,
        apkName: apk ? apk.name : null,
        apkUrl: apk ? apk.browser_download_url : null,
      });
    }

    // ---- 自述 ----
    if (path === '/') {
      return new Response(
        'wanshang-yinfu announce worker\n\n' +
        'GET /announce  公告（204 = 无公告）\n' +
        'GET /version   GitHub Releases 镜像兜底\n',
        { status: 200, headers: { ...CORS, 'Content-Type': 'text/plain; charset=utf-8' } }
      );
    }

    return json({ error: 'not found' }, 404);
  },
};
