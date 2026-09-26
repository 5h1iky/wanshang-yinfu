// 从 protobuf 生成 Java 内嵌的 descriptor 还原 schema（消息名/字段名/编号对照表）
// 用法: node pb-schema.js <生成类.java>
const fs = require('fs');
const pb = require('./pb.js');

// Java 字符串字面量解码（含八进制转义）
function unescapeJava(s) {
  const out = [];
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c !== '\\') {
      out.push(c.charCodeAt(0) & 0xff);
      continue;
    }
    const n = s[++i];
    if (n >= '0' && n <= '7') {
      let oct = n;
      while (oct.length < 3 && i + 1 < s.length && s[i + 1] >= '0' && s[i + 1] <= '7') oct += s[++i];
      out.push(parseInt(oct, 8) & 0xff);
    } else if (n === 'n') out.push(0x0a);
    else if (n === 't') out.push(0x09);
    else if (n === 'r') out.push(0x0d);
    else if (n === '"') out.push(0x22);
    else if (n === "'") out.push(0x27);
    else if (n === '\\') out.push(0x5c);
    else out.push(n.charCodeAt(0) & 0xff);
  }
  return Buffer.from(out);
}

function extractDescriptor(src) {
  const idx = src.indexOf('descriptorData');
  if (idx < 0) throw new Error('未找到 descriptorData');
  const start = src.indexOf('"', idx);
  // 逐字符扫描：收集字符串字面量，遇到字符串外的 ';' 结束语句
  let i = start;
  let inStr = false;
  let seg = '';
  for (; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      seg += c;
      if (c === '\\') seg += src[++i];
      else if (c === '"') inStr = false;
    } else {
      if (c === '"') {
        inStr = true;
        seg += c;
      } else if (c === ';') {
        break;
      } else {
        seg += c;
      }
    }
  }
  const chunks = [...seg.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]);
  return unescapeJava(chunks.join(''));
}

const FIELD_TYPE = { 1: 'double', 2: 'float', 3: 'int64', 4: 'uint64', 5: 'int32', 6: 'fixed64', 7: 'fixed32', 8: 'bool', 9: 'string', 10: 'group', 11: 'message', 12: 'bytes', 13: 'uint32', 14: 'enum', 15: 'sfixed32', 16: 'sfixed64', 17: 'sint32', 18: 'sint64' };

function dumpDescriptor(buf, indent, prefix) {
  const fields = pb.decodeFields(buf);
  const name = fields.find((f) => f.field === 1 && f.wire === 2);
  const msgName = prefix ? prefix + '.' + (name ? name.value.toString() : '?') : (name ? name.value.toString() : '?');
  console.log('  '.repeat(indent) + 'message ' + msgName + ' {');
  for (const f of fields) {
    if (f.field === 2 && f.wire === 2) {
      // FieldDescriptorProto
      const fs2 = pb.decodeFields(f.value);
      const fname = (fs2.find((x) => x.field === 1 && x.wire === 2) || { value: Buffer.from('?') }).value.toString();
      const num = fs2.find((x) => x.field === 3 && x.wire === 0);
      const type = fs2.find((x) => x.field === 5 && x.wire === 0);
      const typeName = (fs2.find((x) => x.field === 6 && x.wire === 2) || { value: Buffer.from('') }).value.toString();
      const label = fs2.find((x) => x.field === 4 && x.wire === 0);
      const rep = label && Number(label.value) === 3 ? 'repeated ' : '';
      console.log(
        '  '.repeat(indent + 1) + rep + (FIELD_TYPE[Number(type ? type.value : 0)] || '?') +
          (typeName ? ' ' + typeName : '') + ' ' + fname + ' = ' + (num ? num.value : '?') + ';'
      );
    }
    if (f.field === 3 && f.wire === 2) {
      dumpDescriptor(f.value, indent + 1, msgName);
    }
  }
  console.log('  '.repeat(indent) + '}');
}

const file = process.argv[2];
const src = fs.readFileSync(file, 'utf8');
const desc = extractDescriptor(src);
console.log('descriptor 解出 ' + desc.length + ' 字节');
const top = pb.decodeFields(desc);
const protoFile = (top.find((f) => f.field === 1 && f.wire === 2) || { value: Buffer.from('?') }).value.toString();
console.log('proto 文件: ' + protoFile);
for (const f of top) {
  if (f.field === 4 && f.wire === 2) dumpDescriptor(f.value, 0, '');
}
