// 真值核对：不靠 class 猜，直接问服务端"这条视频我赞没赞"
// 页面同源带 cookie fetch aweme/detail，读 status 里的 digg 字段 + 顺带记录当前按钮 class
// 用法: node tools/cdp-eval.js @tools/probe-like-truth.js
(function () {
  var id = '7673428035504196915';
  var b = document.querySelector('[data-e2e="video-player-digg"]');
  var domCls = b ? String(b.className) : '(no button)';
  var domText = b ? (b.textContent || '').trim() : '';
  var url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383'
    + '&channel=channel_pc_web&aweme_id=' + id + '&update_version_code=170400'
    + '&version_code=170400&version_name=17.4.0&cookie_enabled=true&platform=PC';
  return fetch(url, { credentials: 'include' })
    .then(function (r) { return r.text(); })
    .then(function (t) {
      var j = null;
      try { j = JSON.parse(t); } catch (e) {}
      var st = j && j.aweme_detail && j.aweme_detail.status ? j.aweme_detail.status : null;
      var out = {
        domCls: domCls, domText: domText,
        httpParse: j ? 'ok' : ('raw:' + t.slice(0, 60)),
        status_code: j ? j.status_code : null,
        diggStatus: st ? st.digg_status : null,
        statusKeys: st ? Object.keys(st).join(',') : null,
        userDigg: j && j.aweme_detail ? ((j.aweme_detail.user_digg !== undefined ? j.aweme_detail.user_digg : '?')) : null
      };
      return JSON.stringify(out);
    })
    .catch(function (e) { return JSON.stringify({ domCls: domCls, err: String(e) }); });
})();
