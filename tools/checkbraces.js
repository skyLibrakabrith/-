// 全工程括号平衡检查（去掉字符串与注释后再数）
//
// 已知坑（2026-11-25 修复）：原版只删双引号字符串，**不处理单引号字符字面量**。
// 于是 Kotlin 里写 `'}'`、`'{'`、`'('` 这类字符常量时会被当成真括号，
// 导致误报（实测：AiJson.kt 里 `val close = if (open == '{') '}' else ']'`
// 被判成 braces=1，而用完整词法分析结果是 0，代码本身没问题）。
// 下面改为逐字符扫描，正确跳过 """多行"""、"..."; '...'、// 和 /* */。
const fs = require("fs"), path = require("path");
const root = process.argv[2] || "vte/app/src/main/java";

function walk(d, o = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : p.endsWith(".kt") && o.push(p);
  }
  return o;
}

/** 返回去掉字符串与注释后的代码。 */
function strip(src) {
  let out = "", i = 0;
  const n = src.length;
  while (i < n) {
    // 多行原始字符串 """..."""（注意要排在 " 前面判断）
    if (src.startsWith('"""', i)) {
      const j = src.indexOf('"""', i + 3);
      i = j < 0 ? n : j + 3;
      continue;
    }
    const c = src[i];
    if (c === '"' || c === "'") {
      // 普通字符串或字符字面量：跳过转义
      i++;
      while (i < n) {
        if (src[i] === "\\") { i += 2; continue; }
        if (src[i] === c) { i++; break; }
        if (c === '"' && src[i] === "\n") break; // 未闭合，防死循环
        i++;
      }
      continue;
    }
    if (src.startsWith("//", i)) {
      const j = src.indexOf("\n", i);
      i = j < 0 ? n : j;
      continue;
    }
    if (src.startsWith("/*", i)) {
      const j = src.indexOf("*/", i + 2);
      i = j < 0 ? n : j + 2;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

let bad = 0;
for (const f of walk(root)) {
  const s = strip(fs.readFileSync(f, "utf8"));
  const b = (s.match(/\{/g) || []).length - (s.match(/\}/g) || []).length;
  const p = (s.match(/\(/g) || []).length - (s.match(/\)/g) || []).length;
  if (b || p) { bad++; console.log("  !! " + f + "  braces=" + b + " parens=" + p); }
}
console.log(bad ? "括号异常 " + bad + " 个文件" : "括号全部平衡");
