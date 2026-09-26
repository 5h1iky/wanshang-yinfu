// 只读盘点当前页面形态：用于判定 ?recommend=1 与 /jingxuan 是不是同一个东西
// 用法: node tools/cdp-eval.js @tools/probe-report-page.js
(function () {
  function host(u) { var m = /^https?:\/\/([^\/]+)/.exec(u || ''); return m ? m[1] : (u || '').slice(0, 40); }
  function path(u) { var m = /^https?:\/\/[^\/]+([^?]*)/.exec(u || ''); return m ? m[1] : ''; }

  var poll = function (tries, cb) {
    var els = document.querySelectorAll('[data-aweme-id]');
    var vids = [].slice.call(document.querySelectorAll('video'));
    var withSrc = vids.filter(function (v) { return v.currentSrc || v.src; });
    var api = performance.getEntriesByType('resource').filter(function (e) {
      return /\/aweme\/v1\/web\//.test(e.name);
    });
    // 有可播源、或有 id 锚点、或已抓到 feed 类接口，就算就绪；否则最多等 9s
    var ready = withSrc.length > 0 || els.length > 0 || api.some(function (e) { return /feed|recommend/.test(e.name); });
    if (ready || tries <= 0) return cb();
    setTimeout(function () { poll(tries - 1, cb); }, 900);
  };

  return new Promise(function (res) {
    poll(10, function () {
      var ids = {}, anchors = {};
      document.querySelectorAll('[data-aweme-id]').forEach(function (c) {
        ids[c.getAttribute('data-aweme-id')] = 1;
      });
      document.querySelectorAll('a[href]').forEach(function (a) {
        var m = /\/video\/(\d{15,})|modal_id=(\d{15,})/.exec(a.href);
        if (m) anchors[m[1] || m[2]] = 1;
      });
      var api = performance.getEntriesByType('resource')
        .filter(function (e) { return /\/aweme\/v1\/web\//.test(e.name); })
        .map(function (e) {
          return {
            ep: path(e.name).replace('/aweme/v1/web/', ''),
            host: host(e.name),
            params: (e.name.split('?')[1] || '').split('&').map(function (p) { return p.split('=')[0]; })
          };
        });
      var feedEps = {};
      api.forEach(function (a) { if (/feed|recommend/.test(a.ep)) feedEps[a.ep] = a; });
      var vids = [].slice.call(document.querySelectorAll('video')).map(function (v) {
        var s = v.currentSrc || v.src || '';
        return { src: s.slice(0, 110), host: host(s), sign: /[?&]sign=/.test(s), w: v.videoWidth, ready: v.readyState };
      });
      res(JSON.stringify({
        href: location.href,
        title: document.title,
        uniqDataAwemeId: Object.keys(ids).length,
        uniqFromHref: Object.keys(anchors).length,
        sampleIds: Object.keys(ids).slice(0, 3).concat(Object.keys(anchors).slice(0, 3)),
        gridCards: document.querySelectorAll('[class*="discover-video-card-item"]').length,
        videoEls: vids.length,
        videos: vids.slice(0, 3),
        webApiTotal: api.length,
        feedEndpoints: Object.keys(feedEps),
        allEndpoints: api.map(function (a) { return a.ep; }).slice(0, 14),
        scroll: { docH: document.documentElement.scrollHeight, innerH: window.innerHeight, innerW: window.innerWidth },
        hasRenderData: !!(document.getElementById('RENDER_DATA') || document.getElementById('js-link-line'))
      }, null, 1));
    });
  });
})();
