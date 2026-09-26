// 生成 ic_gear 的 pathData（2026-09-27）
// 背景：旧 ic_gear.xml 的齿轮横向半径 8.6、纵向半径 11（还整体下移 1），
//       在 24 见方的 viewport 里被纵向拉长 28% —— 真机上就是个"长脸齿轮"。
// 这个脚本按极坐标算一个严格对称的 8 齿齿轮，避免手算坐标再写歪。
//   用法: node tools/gen-gear-path.js
const CX = 12, CY = 12;      // 圆心：viewport 正中
const R_TIP = 9.5;           // 齿顶半径
const R_ROOT = 7.2;          // 齿根半径
const R_HOLE = 3.6;          // 中心孔
const TEETH = 8;
const TIP_HALF = 9;          // 齿顶半角（度）
const ROOT_HALF = 17;        // 齿根半角（度）

const rad = d => (d * Math.PI) / 180;
const P = (r, deg) => {
  const x = CX + r * Math.cos(rad(deg));
  const y = CY + r * Math.sin(rad(deg));
  return [+x.toFixed(2), +y.toFixed(2)];
};
const n = v => (Number.isInteger(v) ? String(v) : String(v));

const seg = [];
const pitch = 360 / TEETH;
for (let i = 0; i < TEETH; i++) {
  const c = i * pitch - 90; // 第一个齿朝正上
  const pts = [P(R_ROOT, c - ROOT_HALF), P(R_TIP, c - TIP_HALF), P(R_TIP, c + TIP_HALF), P(R_ROOT, c + ROOT_HALF)];
  pts.forEach(([x, y], k) => seg.push((i === 0 && k === 0 ? 'M' : 'L') + n(x) + ',' + n(y)));
}
seg.push('Z');
// 中心孔：反向绕（逆时针）才能在 nonZero 填充下挖空
const top = P(R_HOLE, -90), bot = P(R_HOLE, 90);
seg.push(`M${n(top[0])},${n(top[1])} A${R_HOLE},${R_HOLE} 0 1,0 ${n(bot[0])},${n(bot[1])} A${R_HOLE},${R_HOLE} 0 1,0 ${n(top[0])},${n(top[1])} Z`);

// 自检：包围盒必须正方形且居中（这就是旧图标坏掉的那条性质）
const xs = [], ys = [];
for (let i = 0; i < TEETH; i++) {
  const c = i * pitch - 90;
  [P(R_TIP, c - TIP_HALF), P(R_TIP, c + TIP_HALF)].forEach(([x, y]) => { xs.push(x); ys.push(y); });
}
const w = Math.max(...xs) - Math.min(...xs), h = Math.max(...ys) - Math.min(...ys);
console.log('pathData =');
console.log(seg.join(' '));
console.log('\n自检:');
console.log(`  宽=${w.toFixed(2)} 高=${h.toFixed(2)} 纵横比=${(w / h).toFixed(4)}  (必须≈1)`);
console.log(`  包围盒 x[${Math.min(...xs).toFixed(2)}, ${Math.max(...xs).toFixed(2)}] y[${Math.min(...ys).toFixed(2)}, ${Math.max(...ys).toFixed(2)}]`);
console.log(`  中心=(${((Math.max(...xs) + Math.min(...xs)) / 2).toFixed(2)}, ${((Math.max(...ys) + Math.min(...ys)) / 2).toFixed(2)})  (必须≈12,12)`);
