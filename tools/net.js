// 网络工具：cookie 累积 + 手动重定向 + ac_signature JS 挑战求解（自写，干净室实现）
// 挑战页原理：先回一段 JS（写 document.cookie + reload），带上它算出的 cookie 重发才给真实内容
const vm = require('vm');

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

function cookieHeader(jar) {
  return Object.entries(jar)
    .map(([k, v]) => k + '=' + v)
    .join('; ');
}

function absorbSetCookies(res, jar) {
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const c of sc) {
    const kv = c.split(';')[0];
    const i = kv.indexOf('=');
    if (i > 0) jar[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
  }
}

// 单跳请求（不自动跟跳转），cookie 走 jar
async function rawFetch(url, opts, jar) {
  const headers = Object.assign({ 'User-Agent': UA }, opts.headers || {});
  const cs = cookieHeader(jar);
  if (cs) headers.Cookie = cs;
  return fetch(url, {
    headers,
    method: opts.method || 'GET',
    body: opts.body,
    redirect: 'manual',
  });
}

// 跟随重定向链，逐跳收集 cookie（redirect 链每一跳都会种 cookie，不能只跟最后一跳）
// jar 既可放 opts.jar，也可作第三参传入
async function fetchCollect(url, opts = {}, jarArg, maxRedirects = 6) {
  const jar = jarArg || opts.jar || {};
  let current = url;
  for (let i = 0; i <= maxRedirects; i++) {
    const res = await rawFetch(current, opts, jar);
    absorbSetCookies(res, jar);
    if (res.status >= 300 && res.status < 400 && res.headers.get('location')) {
      current = new URL(res.headers.get('location'), current).toString();
      continue;
    }
    return { res, jar, url: current };
  }
  throw new Error('too many redirects');
}

// 在 vm 沙箱里跑挑战页脚本，抓它写进 document.cookie 的内容
function runChallenge(html, pageUrl) {
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)]
    .map((m) => m[1])
    .filter((s) => s.trim());
  const captured = {};
  const doc = {
    get cookie() {
      return cookieHeader(captured);
    },
    set cookie(v) {
      const kv = String(v).split(';')[0];
      const i = kv.indexOf('=');
      if (i > 0) captured[kv.slice(0, i).trim()] = kv.slice(i + 1).trim();
    },
    referrer: '',
    title: '',
    createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, readyState: 'complete' }),
    getElementsByTagName: () => [{ appendChild() {} }],
    getElementById: () => null,
    querySelector: () => null,
    addEventListener() {},
    head: { appendChild() {} },
    body: { appendChild() {}, style: {} },
    documentElement: { style: {} },
    cookieEnabled: true,
  };
  const sandbox = {
    document: doc,
    navigator: { userAgent: UA, language: 'zh-CN', platform: 'Win32', webdriver: false, plugins: [] },
    location: { href: pageUrl, reload() {}, replace() {}, toString: () => pageUrl },
    setTimeout: (fn) => {
      if (typeof fn === 'function') {
        try {
          fn();
        } catch (e) {}
      }
      return 0;
    },
    setInterval: () => 0,
    clearTimeout() {},
    clearInterval() {},
    addEventListener() {},
    removeEventListener() {},
    attachEvent() {},
    requestAnimationFrame: (fn) => {
      if (typeof fn === 'function') {
        try {
          fn(0);
        } catch (e) {}
      }
      return 0;
    },
    alert() {},
    confirm: () => true,
    innerWidth: 2048,
    innerHeight: 960,
    outerWidth: 2554,
    outerHeight: 1386,
    screen: { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440 },
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.top = sandbox;
  sandbox.parent = sandbox;
  const ctx = vm.createContext(sandbox);
  const errors = [];
  for (const s of scripts) {
    try {
      vm.runInContext(s, ctx, { filename: 'challenge.js', timeout: 5000 });
    } catch (e) {
      errors.push(String(e && e.message).slice(0, 120));
    }
  }
  return { cookies: captured, errors };
}

// 带挑战求解的请求：遇 HTML 挑战页就解一次再重发
async function fetchWithChallenge(url, opts = {}, jar = {}) {
  let { res } = await fetchCollect(url, opts, jar);
  let text = await res.text();
  if (/<!doctype html/i.test(text) && /document\.cookie/.test(text)) {
    const { cookies, errors } = runChallenge(text, url);
    Object.assign(jar, cookies);
    if (errors.length) console.log('  [challenge] 脚本告警:', errors.join(' | '));
    console.log('  [challenge] 解出 cookie:', Object.keys(cookies).join(',') || '(无)');
    const retry = await fetchCollect(url, opts, jar);
    res = retry.res;
    text = await res.text();
  }
  return { res, text, jar };
}

module.exports = { UA, fetchCollect, fetchWithChallenge, runChallenge, absorbSetCookies };
