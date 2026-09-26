// 推荐页第二轮取证：全部非图片请求（找它的取流请求）+ 全部 video/source 候选全文
// 用法: node tools/cdp-eval.js @tools/probe-recommend-srcs.js
(function () {
  var RES = performance.getEntriesByType('resource').map(function (e) { return e.name; });
  var notImg = RES.filter(function (u) {
    return !/\.(png|jpe?g|webp|gif|ico|svg|css|woff2?|ttf|eot|mp3)(\?|$)/i.test(u)
      && !/douyinpic|p\d+-sign|p\d+\.douyinpic|bytedance\.com\/dfd/.test(u);
  });
  function info(u) {
    u = u || '';
    var q = u.split('?')[1] || '';
    return {
      host: (/^https?:\/\/([^\/]+)/.exec(u) || [])[1] || '',
      path: (u.replace(/^https?:\/\/[^\/]+/, '').split('?')[0] || '').slice(0, 70),
      len: u.length,
      sign: /[?&]sign=/.test(u),
      keyParams: q.split('&').filter(function (p) { return /^(a|ch|br|bt|btag|cd|cquery|dr|lr|rr|video_id|sign|source|type|aid|did|uifid|from)=/.test(p); }).join('&').slice(0, 160)
    };
  }
  var vids = [].slice.call(document.querySelectorAll('video')).map(function (v) {
    var out = { cur: info(v.currentSrc || v.src), ready: v.readyState, w: v.videoWidth, h: v.videoHeight, dur: Math.round(v.duration || 0),
                srcs: [].slice.call(v.querySelectorAll('source')).map(function (s) { return info(s.src); }) };
    return out;
  });
  var anchors = [].slice.call(document.querySelectorAll('[data-e2e-aweme-id],[data-e2e-vid]')).map(function (el) {
    return { id: el.getAttribute('data-e2e-aweme-id') || el.getAttribute('data-e2e-vid'), e2e: el.getAttribute('data-e2e'), tag: el.tagName };
  });
  return JSON.stringify({
    href: location.href,
    reqTotal: RES.length, nonImg: notImg.slice(0, 25),
    awemeAnchors: anchors, videos: vids
  }, null, 1);
})();
