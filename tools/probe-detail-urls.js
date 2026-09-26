// 只读取证：detail 接口到底给不给"可播"的播放候选（卡点 A 分叉判定）
// 用页面自身上下文带 cookie 请求，参数族照抄 DouyinApi.buildDetailQuery
// 用法: node tools/cdp-eval.js @tools/probe-detail-urls.js
(function () {
  var P = 'device_platform=webapp&aid=6383&channel=channel_pc_web'
    + '&update_version_code=170400&pc_client_type=1&pc_libra_divert=Windows'
    + '&support_h265=1&support_dash=1&version_code=170400&version_name=17.4.0'
    + '&cookie_enabled=true&screen_width=2560&screen_height=1440&browser_language=zh-CN'
    + '&browser_platform=Win32&browser_name=Chrome&browser_version=135.0.0.0'
    + '&browser_online=true&engine_name=Blink&engine_version=135.0.0.0'
    + '&os_name=Windows&os_version=10&cpu_core_num=20&device_memory=8&platform=PC'
    + '&downlink=0.55&effective_type=3g&round_trip_time=500';

  function shape(u) {
    if (!u) return null;
    var m = /^https?:\/\/([^\/]+)/.exec(u);
    return {
      host: m ? m[1] : '?',
      path: u.replace(/^https?:\/\/[^\/]+/, '').slice(0, 46),
      sign: /[?&]sign=/.test(u),
      source: /[?&]source=/i.test(u),
      len: u.length
    };
  }
  function addr(v, k) {
    var a = v[k];
    if (!a || typeof a !== 'object') return { missing: true };
    var l = a.url_list || [];
    return {
      uri: a.uri || '',
      fileSize: a.file_hash || '',
      list: l.map(shape),
      raw0: l[0] ? l[0].slice(0, 200) : ''
    };
  }

  function one(id) {
    var url = 'https://www.douyin.com/aweme/v1/web/aweme/detail/?' + P + '&aweme_id=' + id;
    return fetch(url, { credentials: 'include' })
      .then(function (r) { return r.text().then(function (t) { return { http: r.status, t: t }; }); })
      .then(function (res) {
        var j = null;
        try { j = JSON.parse(res.t); } catch (e) {}
        if (!j) return { id: id, http: res.http, parseFail: res.t.slice(0, 140) };
        var d = j.aweme_detail;
        if (!d) return { id: id, http: res.http, status_code: j.status_code, status_msg: j.status_msg, noDetail: true };
        var v = d.video || {};
        var out = {
          id: id, http: res.http, status_code: j.status_code,
          desc: (d.desc || '').slice(0, 20),
          videoKeys: Object.keys(v).join(','),
          play_addr: addr(v, 'play_addr'),
          play_addr_h264: addr(v, 'play_addr_h264'),
          play_addr_265: addr(v, 'play_addr_265'),
          download_addr: addr(v, 'download_addr'),
          isDash: v.dash_addr ? true : false
        };
        if (v.bit_rate && v.bit_rate.length) {
          out.bit_rate = v.bit_rate.map(function (b) {
            var pa = b.play_addr || {};
            var l = pa.url_list || [];
            return {
              gear: b.gear_name, qt: b.quality_type, host: shape(l[0]),
              n: l.length, uri: pa.uri || ''
            };
          });
        }
        return out;
      })
      .catch(function (e) { return { id: id, err: String(e) }; });
  }

  // 先用精选页真实 id，取不到就回退到已知 id
  var found = [];
  var els = document.querySelectorAll('[data-aweme-id]');
  var seen = {};
  for (var i = 0; i < els.length; i++) {
    var x = els[i].getAttribute('data-aweme-id');
    if (x && /^\d{15,}$/.test(x) && !seen[x]) { seen[x] = 1; found.push(x); }
  }
  var use = found.slice(0, 2);
  if (use.length < 2) use = IDS;
  return Promise.all(use.map(one)).then(function (rs) {
    return JSON.stringify({ pageIdsTotal: found.length, probed: use, results: rs }, null, 1);
  });
})();
