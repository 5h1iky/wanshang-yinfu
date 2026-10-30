// 测 git 推送到 GitHub 的可用通道：https(github.com:443) / ssh(ssh.github.com:443) / ssh(22)
const net = require('net');
const targets = [
  ['github.com:443', 'github.com', 443],
  ['ssh.github.com:443', 'ssh.github.com', 443],
  ['github.com:22', 'github.com', 22],
  ['api.github.com:443', 'api.github.com', 443],
];
(async () => {
  for (const [label, host, port] of targets) {
    const t0 = Date.now();
    await new Promise((resolve) => {
      const sock = net.connect({ host, port, timeout: 8000 });
      sock.on('connect', () => { console.log(`${label.padEnd(20)} OK ${Date.now() - t0}ms`); sock.destroy(); resolve(); });
      sock.on('timeout', () => { console.log(`${label.padEnd(20)} TIMEOUT`); sock.destroy(); resolve(); });
      sock.on('error', (e) => { console.log(`${label.padEnd(20)} FAIL ${e.code}`); resolve(); });
    });
  }
})();
