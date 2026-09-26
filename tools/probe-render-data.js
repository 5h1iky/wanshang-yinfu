// 只读：解析 #RENDER_DATA（SSR JSON），定位推荐流卡片数组的路径与字段名
// 目的：A2 用「读 SSR」替代「抓 DOM」（实测本页 DOM 未水合：docH==视口高、卡片 0 个）
// 用法: node tools/cdp-eval.js @tools/probe-render-data.js
(function () {
  var el = document.getElementById('RENDER_DATA');
  if (!el) return 'NO RENDER_DATA';
  var raw = el.textContent || '';
  var json = null;
  try { json = JSON.parse(decodeURIComponent(raw)); } catch (e) {
    try { json = JSON.parse(raw); } catch (e2) { return 'PARSE FAIL ' + String(e2) + ' len=' + raw.length; }
  }
  var out = { len: raw.length, topKeys: Object.keys(json) };

  // 递归找"像 aweme 列表"的数组：元素是对象且含 aweme_id / video / desc 之类键
  var hits = [];
  function looksLikeAweme(o) {
    if (!o || typeof o !== 'object') return false;
    var k = Object.keys(o);
    return (k.indexOf('aweme_id') >= 0 || k.indexOf('awemeId') >= 0 ||
            (k.indexOf('video') >= 0 && k.indexOf('desc') >= 0));
  }
  function walk(node, path, depth) {
    if (depth > 7 || hits.length > 6) return;
    if (Array.isArray(node)) {
      if (node.length && looksLikeAweme(node[0])) {
        hits.push({ path: path, count: node.length, keys: Object.keys(node[0]).slice(0, 22).join(',') });
        return;
      }
      for (var i = 0; i < Math.min(node.length, 3); i++) walk(node[i], path + '[' + i + ']', depth + 1);
      return;
    }
    if (node && typeof node === 'object') {
      var ks = Object.keys(node);
      for (var j = 0; j < ks.length; j++) walk(node[ks[j]], path ? path + '.' + ks[j] : ks[j], depth + 1);
    }
  }
  walk(json, '', 0);
  out.awemeArrays = hits;

  // 顺带打印各层键名，帮助认路
  function keysOf(o) { return o && typeof o === 'object' ? Object.keys(o).slice(0, 14).join(',') : typeof o; }
  out.l1 = {};
  out.topKeys.slice(0, 6).forEach(function (k) { out.l1[k] = keysOf(json[k]); });
  return JSON.stringify(out);
})();
