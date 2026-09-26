// 实测：https://www.douyin.com/?recommend=1 到底落在哪、是什么形态的页面
// 只读取证：导航后轮询 location/标题/卡片锚点/播放器元素
(function () {
  location.href = 'https://www.douyin.com/?recommend=1';
  var t0 = Date.now();
  return new Promise(function (res) {
    (function poll() {
      var cards = document.querySelectorAll('[data-aweme-id]');
      var ids = {};
      cards.forEach(function (c) { ids[c.getAttribute('data-aweme-id')] = 1; });
      var vids = document.querySelectorAll('video');
      var playing = 0;
      vids.forEach(function (v) { if (v.currentSrc || v.src) playing++; });
      var out = {
        href: location.href,
        title: document.title,
        ready: document.readyState,
        uniqIds: Object.keys(ids).length,
        jxCards: document.querySelectorAll('[class*="discover-video-card-item"]').length,
        videoEls: vids.length,
        videoWithSrc: playing,
        docH: document.documentElement.scrollHeight,
        innerH: window.innerHeight,
        bodyCls: String(document.body.className).slice(0, 70),
        elapsed: Date.now() - t0
      };
      // 导航生效（href 已含 recommend）且卡片/播放器出现，或超时 12s
      if ((out.href.indexOf('recommend') >= 0 && (out.uniqIds > 0 || out.videoWithSrc > 0)) || Date.now() - t0 > 12000) {
        res(JSON.stringify(out, null, 1));
        return;
      }
      setTimeout(poll, 800);
    })();
  });
})();
