// M0.5 验证② 前置：抖音 Web 扫码登录（人工扫码半自动；每个用户只登自己的号）
// 流程：get_qrcode → 展示二维码 → check_qrconnect 轮询(1.5s) → 已确认后跟 redirect 链收 cookie → 落盘 dy-session.json
// 安全：cookie 值只写本地 dy-session.json，控制台只打印 cookie 的"键名"，绝不打印值
const fs = require('fs');
const path = require('path');
const { fetchWithChallenge, fetchCollect } = require('./net.js');
const QRCode = require('qrcode');

const SERVICE = 'https://www.douyin.com';
const SESSION_FILE = path.join(__dirname, 'dy-session.json');
const QR_PNG = path.join(__dirname, 'qr-login.png');

function cookieFile(jar) {
  fs.writeFileSync(SESSION_FILE, JSON.stringify(jar, null, 2), { mode: 0o600 });
}

async function main() {
  const jar = {};

  console.log('[1/4] 获取二维码 ...');
  const getQrUrl =
    'https://sso.douyin.com/get_qrcode/?service=' +
    encodeURIComponent(SERVICE) +
    '&need_logo=false&aid=6383&account_sdk_source=web&qrcode_use_style=web_qrcode&language=zh';
  const r1 = await fetchWithChallenge(getQrUrl, { headers: { Referer: SERVICE + '/' } }, jar);
  let data;
  try {
    data = JSON.parse(r1.text).data || {};
  } catch (e) {
    console.log('❌ get_qrcode 非 JSON，前 400 字:\n' + r1.text.slice(0, 400));
    process.exit(1);
  }
  console.log('  data 字段:', Object.keys(data).join(', '));
  const token = data.token;
  if (!token) {
    console.log('❌ 无 token，data 内容:', JSON.stringify(data).slice(0, 300));
    process.exit(1);
  }
  // 二维码内容：内含 token 的 URL（具体字段名以实测为准）
  const qrContent = data.url_str || data.qrcode_index_url || data.qrcode_url || data.qrcode_content;
  if (!qrContent) {
    console.log('❌ 未找到二维码内容字段，data 内容:', JSON.stringify(data).slice(0, 300));
    process.exit(1);
  }
  await QRCode.toFile(QR_PNG, qrContent, { width: 480, margin: 2 });
  console.log('  二维码已生成: ' + QR_PNG);

  console.log('[2/4] 请用手机抖音"扫一扫"扫这个二维码（约 4 分钟内有效）');
  console.log('[3/4] 开始轮询扫码状态（1.5s 一次，勿加速——有风控）...');
  const checkUrlBase =
    'https://sso.douyin.com/check_qrconnect/?token=' +
    encodeURIComponent(token) +
    '&service=' +
    encodeURIComponent(SERVICE) +
    '&aid=6383&account_sdk_source=web&language=zh';
  const deadline = Date.now() + 240 * 1000;
  let lastStatus = '';
  let redirectUrl = null;
  while (Date.now() < deadline) {
    const r2 = await fetchWithChallenge(checkUrlBase, { headers: { Referer: SERVICE + '/' } }, jar);
    let d;
    try {
      d = JSON.parse(r2.text).data || {};
    } catch (e) {
      console.log('  [warn] 轮询响应非 JSON: ' + r2.text.slice(0, 120));
      await new Promise((s) => setTimeout(s, 1500));
      continue;
    }
    const status = String(d.status !== undefined ? d.status : d.error_code);
    if (status !== lastStatus) {
      console.log('  状态: ' + status + (d.status_msg ? ' (' + d.status_msg + ')' : ''));
      lastStatus = status;
    }
    if (d.redirect_url) {
      redirectUrl = d.redirect_url;
      break;
    }
    if (status === '3' || status === '4') {
      console.log('❌ 二维码已过期/作废，请重新运行本脚本');
      process.exit(2);
    }
    await new Promise((s) => setTimeout(s, 1500));
  }
  if (!redirectUrl) {
    console.log('❌ 超时未确认');
    process.exit(3);
  }

  console.log('[4/4] 跟 redirect 链换 cookie ...');
  await fetchCollect(redirectUrl, { jar, headers: { Referer: SERVICE + '/' } });
  cookieFile(jar);
  const keys = Object.keys(jar);
  console.log('✅ 登录完成，cookie 键: ' + keys.join(', '));
  const must = ['sessionid', 'sessionid_ss'];
  const missing = must.filter((k) => !jar[k]);
  if (missing.length) console.log('⚠️ 缺少关键 cookie: ' + missing.join(', ') + '（检查 redirect 链是否走全）');
  else console.log('✅ sessionid / sessionid_ss 已落盘（值未打印）: ' + SESSION_FILE);
}

main().catch((e) => {
  console.error('FAIL', e);
  process.exit(1);
});
