// Node 运行环境封装：在 vm 沙箱里加载 ylcangel/douyin_sign 的 a_bogus 实现（Apache-2.0）
// 用法：const { createSigner } = require('./sign/abogus.js'); signer.makeABogus(queryString, 0)
// 注意：a_bogus 必须对"最终发送的原始 query 字符串"签名，签完再追加 &a_bogus=...
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.join(__dirname, '..', 'sign-src', 'a_bogus');

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36';

function createSigner(navPatch) {
  const sandbox = {};
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.navigator = Object.assign(
    { userAgent: UA, platform: 'Win32' },
    navPatch || {}
  );
  sandbox.innerWidth = 2048;
  sandbox.innerHeight = 960;
  sandbox.outerWidth = 2554;
  sandbox.outerHeight = 1386;
  sandbox.screen = { availWidth: 2560, availHeight: 1392, width: 2560, height: 1440 };

  const ctx = vm.createContext(sandbox);
  for (const f of ['utils.js', 'sm3.js', 'vm_decode.js']) {
    const code = fs.readFileSync(path.join(SRC, f), 'utf8');
    vm.runInContext(code, ctx, { filename: f });
  }
  return {
    makeABogus(query, ts) {
      return vm.runInContext(
        'makeABogus(' + JSON.stringify(query) + ', ' + (ts || 0) + ')',
        ctx
      );
    },
  };
}

module.exports = { createSigner, UA };
