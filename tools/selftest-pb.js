// pb.js 补丁管线自测（假数据打补丁→回读校验，不发网络请求）
const fs = require('fs');
const path = require('path');
const pb = require('./pb.js');

const JAVA = path.join(__dirname, 'dyapi-src', 'src', 'main', 'java', 'com', 'dy_web_api', 'sdk', 'message', 'handler', 'MessageSender.java');
const src = fs.readFileSync(JAVA, 'utf8');
const tpl = (name) => Buffer.from(src.match(new RegExp(name + '\\s*=\\s*"([^"]+)"'))[1], 'base64');

let pass = 0, fail = 0;
const chk = (label, ok) => {
  console.log((ok ? 'PASS ' : 'FAIL ') + label);
  if (ok) pass++; else fail++;
};

let buf = tpl('TEXT_MESSAGE_TEMPLATE');
const before = buf.length;
buf = pb.replaceAtPath(buf, [8, 100, 1], '0:1:111111:222222');
buf = pb.replaceVarintAtPath(buf, [8, 100, 2], 1);
buf = pb.replaceVarintAtPath(buf, [8, 100, 3], 987654321098765432n);
buf = pb.replaceAtPath(buf, [8, 100, 4], JSON.stringify({ mention_users: [], aweType: 700, richTextInfos: [], text: '补丁自测ABC' }));
buf = pb.replaceAtPath(buf, [8, 100, 8], 'TEST-UUID-0000');
buf = pb.replaceKvValue(buf, [8, 100, 5], 's:client_message_id', 'TEST-UUID-0000');
buf = pb.replaceKvValue(buf, [8, 100, 5], 's:stime', '1700000000000.1234');
buf = pb.replaceKvValue(buf, [15], 'identity_security_token', '{"token":"SELFTEST"}');
console.log('size ' + before + ' -> ' + buf.length);

const s = pb.collectStrings(buf);
chk('conversationId 已替换', s.includes('0:1:111111:222222'));
chk('content JSON 已替换', s.some((x) => x.includes('补丁自测ABC')));
chk('UUID [8/100/8] 已替换', s.includes('TEST-UUID-0000'));
chk('kv s:stime 已替换', s.includes('1700000000000.1234'));
chk('kv identity_security_token 已替换', s.some((x) => x.includes('SELFTEST')));
const nums = s.filter((x) => /^\d+$/.test(x) && BigInt(x) > 10n ** 16n);
chk('conversationShortId varint 已替换', nums.includes('987654321098765432'));

let c = tpl('CREATE_CONVERSATION_TEMPLATE');
c = pb.replaceRepeatedVarintsAtPath(c, [8, 609, 2], [123456789012345n, 987654321098765n]);
const cs = pb.collectStrings(c).map(Number).filter((n) => Number.isFinite(n) && n > 1e14);
chk('create users[0/1] 已替换', cs.includes(123456789012345) && cs.includes(987654321098765));

console.log('结果: ' + pass + ' PASS / ' + fail + ' FAIL');

// 真实响应回归：ticket 提取（若存在上轮落盘的会话创建响应）
const respFile = path.join(__dirname, 'dm-create-resp.bin');
if (fs.existsSync(respFile)) {
  const resp = fs.readFileSync(respFile);
  const ticket = pb.readLeafAtPath(resp, [6, 609, 1, 4]);
  chk('readLeafAtPath 提取会话 ticket', !!ticket && /^1la/.test(ticket.toString()));
  const convId = pb.readLeafAtPath(resp, [6, 609, 1, 1]);
  chk('readLeafAtPath 提取 conversationId', !!convId && convId.toString().startsWith('0:1:'));
  const stripped = pb.removeFieldsAtPath(buf, [23]);
  chk('removeFieldsAtPath 删除顶层信封', stripped.length < buf.length);
  console.log('结果(含回归): ' + pass + ' PASS / ' + fail + ' FAIL');
}
process.exit(fail ? 1 : 0);
