// 分析 MessageSender.java 内嵌的 protobuf 模板结构，定位打补丁坐标
// 用法: node pb-dump.js [模板名]  （默认 TEXT_MESSAGE_TEMPLATE）
const fs = require('fs');
const path = require('path');
const pb = require('./pb.js');

const JAVA = path.join(__dirname, 'dyapi-src', 'src', 'main', 'java', 'com', 'dy_web_api', 'sdk', 'message', 'handler', 'MessageSender.java');
const name = process.argv[2] || 'TEXT_MESSAGE_TEMPLATE';
const src = fs.readFileSync(JAVA, 'utf8');
const m = src.match(new RegExp(name + '\\s*=\\s*"([^"]+)"'));
if (!m) {
  console.log('未找到模板 ' + name);
  process.exit(1);
}
const buf = Buffer.from(m[1], 'base64');
console.log('模板 ' + name + '：' + buf.length + ' 字节');
console.log('===== 结构树 =====');
pb.dump(buf);

console.log('===== 定位打补丁坐标 =====');
const probes = {
  'conversationId(0:1:…)': '0:1:',
  'clientMessageId(UUID)': /^[0-9a-f]{8}-[0-9a-f]{4}-/,
  'content(JSON)': /^{"mention_users"/,
  'content(JSON,卡片)': /^{"aweType"/,
};
// 逐叶子扫描
(function scan(b, pathArr) {
  let fields;
  try {
    fields = pb.decodeFields(b);
  } catch (e) {
    return;
  }
  for (const f of fields) {
    const p = pathArr.concat(f.field);
    if (f.wire === 2) {
      const s = f.value.toString('utf8');
      for (const [label, pat] of Object.entries(probes)) {
        const hit = typeof pat === 'string' ? s.startsWith(pat) : pat.test(s);
        if (hit) console.log(label + ' -> path [' + p.join('/') + '] len=' + f.value.length + ' val=' + s.slice(0, 60));
      }
      if (f.value.length > 0 && !/^[\x20-\x7e]+$/.test(s)) {
        try {
          scan(f.value, p);
        } catch (e) {}
      }
    } else if (f.wire === 0) {
      // 找疑似 conversationShortId 的大数值
      if (f.value > 1000000000000n) console.log('大数值(疑 conversationShortId) -> path [' + p.join('/') + '] = ' + f.value.toString());
    }
  }
})(buf, []);
