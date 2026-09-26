// 最小 protobuf wire 编解码器（自写，干净室；够用即可，不追求全特性）
// 用途：读懂/打补丁抖音私信请求体（DySendMsgRequest 等），M0.5② 与 App 端复用
// wire type: 0=varint, 1=64bit, 2=len-delimited, 5=32bit

function readVarint(buf, pos) {
  let result = 0n;
  let shift = 0n;
  while (true) {
    const b = buf[pos++];
    result |= BigInt(b & 0x7f) << shift;
    if ((b & 0x80) === 0) break;
    shift += 7n;
    if (shift > 70n) throw new Error('varint too long');
  }
  return { value: result, pos };
}

function writeVarint(n) {
  let v = BigInt(n);
  const out = [];
  while (v >= 0x80n) {
    out.push(Number(v & 0x7fn) | 0x80);
    v >>= 7n;
  }
  out.push(Number(v & 0x7fn));
  return Buffer.from(out);
}

// 解析一层字段（不递归），返回 [{field, wire, value}]；value: BigInt(varint) | Buffer(len) | Buffer(8/4)
function decodeFields(buf) {
  const fields = [];
  let pos = 0;
  while (pos < buf.length) {
    const t = readVarint(buf, pos);
    pos = t.pos;
    const tag = Number(t.value);
    const field = tag >>> 3;
    const wire = tag & 7;
    let value;
    if (wire === 0) {
      const v = readVarint(buf, pos);
      value = v.value;
      pos = v.pos;
    } else if (wire === 2) {
      const l = readVarint(buf, pos);
      pos = l.pos;
      const len = Number(l.value);
      value = buf.subarray(pos, pos + len);
      pos += len;
    } else if (wire === 1) {
      value = buf.subarray(pos, pos + 8);
      pos += 8;
    } else if (wire === 5) {
      value = buf.subarray(pos, pos + 4);
      pos += 4;
    } else {
      throw new Error('unknown wire type ' + wire + ' at ' + pos);
    }
    fields.push({ field, wire, value });
  }
  return fields;
}

// 重新编码一层字段
function encodeFields(fields) {
  const parts = [];
  for (const f of fields) {
    const tag = (f.field << 3) | f.wire;
    parts.push(writeVarint(tag));
    if (f.wire === 0) parts.push(writeVarint(f.value));
    else if (f.wire === 2) {
      parts.push(writeVarint(f.value.length));
      parts.push(Buffer.from(f.value));
    } else parts.push(Buffer.from(f.value));
  }
  return Buffer.concat(parts);
}

function isPrintable(b) {
  if (!b.length) return false;
  for (const x of b) {
    if (x < 0x20 || x > 0x7e) {
      // 允许 UTF-8 中文等高位字节
      if (x < 0x80) return false;
    }
  }
  return true;
}

function preview(b) {
  const s = b.toString('utf8');
  return s.length > 60 ? s.slice(0, 60) + '…[' + s.length + ']' : s;
}

// 打印结构树（递归尝试把 len 字段解析为子消息；能解析且字段合理才展示为消息）
function dump(buf, indent = 0, depth = 0) {
  const pad = '  '.repeat(indent);
  let fields;
  try {
    fields = decodeFields(buf);
  } catch (e) {
    console.log(pad + '(非消息: ' + e.message + ')');
    return;
  }
  for (const f of fields) {
    if (f.wire === 2) {
      const b = f.value;
      let asMsg = null;
      if (depth < 6 && b.length > 0) {
        try {
          const sub = decodeFields(b);
          // 启发式：子字段编号都在 1..2000 且编码回环一致
          if (sub.length && sub.every((s) => s.field >= 1 && s.field <= 2000)) asMsg = sub;
        } catch (e) {}
      }
      if (asMsg && !isPrintable(b)) {
        console.log(pad + 'field ' + f.field + ' (msg, ' + b.length + 'B)');
        dump(b, indent + 1, depth + 1);
      } else if (isPrintable(b)) {
        console.log(pad + 'field ' + f.field + ' (str) = ' + preview(b));
      } else {
        console.log(pad + 'field ' + f.field + ' (bytes, ' + b.length + 'B) = ' + b.subarray(0, 24).toString('hex') + (b.length > 24 ? '…' : ''));
      }
    } else if (f.wire === 0) {
      console.log(pad + 'field ' + f.field + ' (varint) = ' + f.value.toString());
    } else {
      console.log(pad + 'field ' + f.field + ' (fixed ' + (f.wire === 1 ? 64 : 32) + ')');
    }
  }
}

// 在树中按"叶子值等于 needle"查找路径，返回 [{path, field, wire}]（打补丁定位用）
function findPaths(buf, needle, path = []) {
  const hits = [];
  let fields;
  try {
    fields = decodeFields(buf);
  } catch (e) {
    return hits;
  }
  for (const f of fields) {
    const p = path.concat(f.field);
    if (f.wire === 2) {
      if (f.value.toString('utf8') === needle) hits.push({ path: p, field: f.field, wire: f.wire });
      if (f.value.length > 0) {
        try {
          hits.push(...findPaths(f.value, needle, p));
        } catch (e) {}
      }
    }
  }
  return hits;
}

// 按路径替换叶子的 len 字段值（新值长度可变，父层长度自动重算——因为是整树重编码）
function replaceAtPath(buf, path, newValue) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    for (let i = 0; i < fields.length; i++) {
      const f = fields[i];
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        if (f.wire !== 2) throw new Error('目标叶子不是 len 字段');
        fields[i] = { field: f.field, wire: 2, value: Buffer.from(newValue, 'utf8') };
      } else if (f.wire === 2) {
        fields[i] = { field: f.field, wire: 2, value: rec(f.value, depth + 1) };
      }
    }
    return encodeFields(fields);
  }
  return rec(buf, 0);
}

// 变长 varint 叶子替换（如 conversationShortId 这类数值）
function replaceVarintAtPath(buf, path, newValue) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    for (let i = 0; i < fields.length; i++) {
      const f = fields[i];
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        if (f.wire !== 0) throw new Error('目标叶子不是 varint 字段');
        fields[i] = { field: f.field, wire: 0, value: BigInt(newValue) };
      } else if (f.wire === 2) {
        fields[i] = { field: f.field, wire: 2, value: rec(f.value, depth + 1) };
      }
    }
    return encodeFields(fields);
  }
  return rec(buf, 0);
}

// 在重复 key-value 子消息（field1=键名, field2=值）里按键名替换值
// path: 到达重复字段的路径（如 [8,100,5] 或 [15]）；遍历该字段的所有出现项
function replaceKvValue(buf, path, keyName, newValue) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    for (let i = 0; i < fields.length; i++) {
      const f = fields[i];
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        if (f.wire !== 2) continue;
        let kv;
        try {
          kv = decodeFields(f.value);
        } catch (e) {
          continue;
        }
        const k = kv.find((x) => x.field === 1 && x.wire === 2);
        if (!k || k.value.toString('utf8') !== keyName) continue;
        for (let j = 0; j < kv.length; j++) {
          if (kv[j].field === 2) kv[j] = { field: 2, wire: 2, value: Buffer.from(String(newValue), 'utf8') };
        }
        fields[i] = { field: f.field, wire: 2, value: encodeFields(kv) };
      } else if (f.wire === 2) {
        fields[i] = { field: f.field, wire: 2, value: rec(f.value, depth + 1) };
      }
    }
    return encodeFields(fields);
  }
  return rec(buf, 0);
}

// 读取重复 key-value 子消息里某键的值
function readKvValue(buf, path, keyName) {
  let found = null;
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    for (const f of fields) {
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        if (f.wire !== 2) continue;
        try {
          const kv = decodeFields(f.value);
          const k = kv.find((x) => x.field === 1 && x.wire === 2);
          const v = kv.find((x) => x.field === 2 && x.wire === 2);
          if (k && v && k.value.toString('utf8') === keyName) found = v.value.toString('utf8');
        } catch (e) {}
      } else if (f.wire === 2) rec(f.value, depth + 1);
    }
  }
  rec(buf, 0);
  return found;
}

// 按顺序替换重复 varint 字段（如 CreateSessionRequest.user 列表）
function replaceRepeatedVarintsAtPath(buf, path, values) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    let idx = 0;
    for (let i = 0; i < fields.length; i++) {
      const f = fields[i];
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        if (f.wire !== 0) continue;
        if (idx < values.length) fields[i] = { field: f.field, wire: 0, value: BigInt(values[idx]) };
        idx++;
      } else if (f.wire === 2) {
        fields[i] = { field: f.field, wire: 2, value: rec(f.value, depth + 1) };
      }
    }
    return encodeFields(fields);
  }
  return rec(buf, 0);
}

// 收集树中所有可打印字符串与大数值（响应解析启发式用）
function collectStrings(buf, out = []) {
  let fields;
  try {
    fields = decodeFields(buf);
  } catch (e) {
    return out;
  }
  for (const f of fields) {
    if (f.wire === 2) {
      const s = f.value.toString('utf8');
      if (isPrintable(f.value) && s.trim()) out.push(s);
      else {
        try {
          collectStrings(f.value, out);
        } catch (e) {}
      }
    } else if (f.wire === 0) {
      out.push(f.value.toString());
    }
  }
  return out;
}

// 按路径读叶子值（2=字符串/字节，0=BigInt）
function readLeafAtPath(buf, path) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    for (const f of fields) {
      if (f.field !== target) continue;
      if (depth === path.length - 1) {
        return f.wire === 2 ? f.value : f.value;
      }
      if (f.wire === 2) {
        const r = rec(f.value, depth + 1);
        if (r !== undefined) return r;
      }
    }
    return undefined;
  }
  return rec(buf, 0);
}

// 删除路径上的字段（path 长度即深度；同号字段全部删除）
function removeFieldsAtPath(buf, path) {
  function rec(cur, depth) {
    const fields = decodeFields(cur);
    const target = path[depth];
    const out = [];
    for (const f of fields) {
      if (f.field !== target) {
        out.push(f);
        continue;
      }
      if (depth === path.length - 1) continue; // 删掉
      if (f.wire === 2) out.push({ field: f.field, wire: 2, value: rec(f.value, depth + 1) });
      else out.push(f);
    }
    return encodeFields(out);
  }
  return rec(buf, 0);
}

module.exports = {
  decodeFields,
  encodeFields,
  readVarint,
  writeVarint,
  dump,
  findPaths,
  replaceAtPath,
  replaceVarintAtPath,
  replaceKvValue,
  readKvValue,
  replaceRepeatedVarintsAtPath,
  collectStrings,
  readLeafAtPath,
  removeFieldsAtPath,
};
