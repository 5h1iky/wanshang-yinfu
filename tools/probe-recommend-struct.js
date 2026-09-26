// 推荐页(?recommend=1)取证：它靠什么请求取流、aweme_id 在 DOM 哪里、播放器 src 长什么样
// 用法: node tools/cdp-eval.js @tools/probe-recommend-struct.js
(function () {
  var RES = performance.getEntriesByType('resource');
  // 1) 所有像接口的请求（不限 /aweme/v1/web/，推荐流可能走别的 host/路径）
  var apis = RES.filter(function (e) {
    return /douyin|bytedance/.test(e.name) && /(aweme|feed|recommend|detail|\/v\d|imapi)/.test(e.name);
  }).map(function (e) {
    var u = e.name.split('?')[0];
    return { url: u.slice(0, 95), params: (e.name.split('?')[1] || '').split('&').map(function (p) { return p.split('=')[0]; }).join(',').slice(0, 200) };
  });
  var feedLike = apis.filter(function (a) { return /feed|recommend|multi\/aweme/.test(a.url); });

  // 2) 全 DOM 找 19 位 aweme_id 出现在哪些属性上
  var idHits = {};
  document.querySelectorAll('*').forEach(function (el) {
    for (var i = 0; i < el.attributes.length; i++) {
      var an = el.attributes[i].name, av = el.attributes[i].value || '';
      var m = /(\d{18,20})/.exec(av);
      if (!m) continue;
      var key = el.tagName.toLowerCase() + '@' + an;
      if (!idHits[key]) idHits[key] = { n: 0, sample: m[1], val: av.slice(0, 120) };
      idHits[key].n++;
    }
  });

  // 3) 播放器完整 src（不截断，看是不是能直接喂给 MediaPlayer）
  var vids = [].slice.call(document.querySelectorAll('video')).map(function (v) {
    var s = v.currentSrc || v.src || '';
    return { full: s.slice(0, 700), len: s.length, ready: v.readyState, w: v.videoWidth, dur: v.duration };
  });

  // 4) 页面自有的数据岛（SSR/内联 JSON）
  var scripts = [].slice.call(document.querySelectorAll('script')).filter(function (s) {
    var t = s.id + ' ' + (s.textContent || '').slice(0, 40);
    return /RENDER_DATA|_ROUTER_DATA|__pace|loadData/.test(t);
  }).map(function (s) { return { id: s.id, len: (s.textContent || '').length }; });

  return JSON.stringify({
    href: location.href,
    apiCount: apis.length, feedLike: feedLike.slice(0, 6),
    allApis: apis.map(function (a) { return a.url; }).slice(0, 20),
    idHits: idHits, videos: vids, inlineData: scripts
  }, null, 1);
})();
