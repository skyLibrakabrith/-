// 全工程括号平衡检查（去掉字符串与注释后再数）
//
// 用法：
//   node tools/checkbraces.js [root]       # 默认 vte/app/src/main/java
//   node tools/checkbraces.js --self-test  # 自检：造样本，覆盖"该报的"和"不该报的"
//
// 已知坑（2026-11-25 修复）：原版只删双引号字符串，**不处理单引号字符字面量**。
// 于是 Kotlin 里写 `'}'`、`'{'`、`'('` 这类字符常量时会被当成真括号，
// 导致误报（实测：AiJson.kt 里 `val close = if (open == '{') '}' else ']'`
// 被判成 braces=1，而用完整词法分析结果是 0，代码本身没问题）。
// 下面改为逐字符扫描，跳过 """多行"""、"..."; '...'、// 和 /* */。
//
// 已知坑（2026-11-26 修复）：块注释 `/* ... */` **不配对嵌套**（用的是 indexOf("*/")）。
// Kotlin 允许嵌套块注释，`/** 外层 /* 内层 */ 外层继续说 */` 在这台实现里会在**内层**的 `*/`
// 处就收尾，把 ` 外层继续说 */` 当成代码。实测两个方向都错：
//   * 误报：残留片段里带一个 `{`（真实代码是平衡的）→ 判成 braces=1；
//   * **漏报**（更危险）：残留片段里带一个 `}`（真实代码少一个右括号）→ 判成"括号全部平衡"。
// 现在按深度配对，扫描逻辑与 unused2.js / kotlin_lint.js 的 codeMask 保持一致（同一套词法）。
const fs = require("fs"), path = require("path");

function walk(d, o = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : p.endsWith(".kt") && o.push(p);
  }
  return o;
}

/** 返回去掉字符串与注释后的代码（只保留"真的会被编译器看到的"字符）。 */
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
      // 块注释按**深度**配对（Kotlin 允许嵌套）。不配对的话会把内层 */ 之后的内容当成代码。
      let depth = 0, k = i;
      while (k < n) {
        if (src.startsWith("/*", k)) { depth++; k += 2; continue; }
        if (src.startsWith("*/", k)) { depth--; k += 2; if (depth === 0) break; continue; }
        k++;
      }
      i = k;
      continue;
    }
    out += c;
    i++;
  }
  return out;
}

/** 同一个文件里 { } 与 ( ) 的净数量；否则 null。 */
function imbalanceOf(src) {
  const s = strip(src);
  const b = (s.match(/\{/g) || []).length - (s.match(/\}/g) || []).length;
  const p = (s.match(/\(/g) || []).length - (s.match(/\)/g) || []).length;
  return b || p ? { b, p } : null;
}

function run(root) {
  if (!fs.existsSync(root)) {
    console.error(`根目录不存在: ${root}（本工具要求从含 vte/ 子目录的父目录运行）`);
    process.exit(1);
  }
  let bad = 0;
  for (const f of walk(root)) {
    const m = imbalanceOf(fs.readFileSync(f, "utf8"));
    if (m) { bad++; console.log("  !! " + f + "  braces=" + m.b + " parens=" + m.p); }
  }
  console.log(bad ? "括号异常 " + bad + " 个文件" : "括号全部平衡");
  return bad;
}

// ── 自检：把上面每一个"曾经踩过的坑"都变成一条样本 ────────────────────────────
// 断言方式：对每个样本检查"是否被判为不平衡"，与"真实结论"对照。
// 真实结论来自**手写**的期望值（不是用同一套实现算出来的），所以能抓住实现回退。
function selfTest() {
  const cases = [
    // [名称, 源码, 期望: 是否不平衡]
    ["干净的代码", "package x\nfun f() { val a = (1 + 2) }\n", false],
    ["字符字面量里的 } { (", "package x\nfun f() { val c = '}'; val o = '{'; val q = '(' }\n", false],
    ["字符串里的括号", 'package x\nfun f() { val s = "}}} ((( " }\n', false],
    ["多行原始字符串里的括号", 'package x\nfun f() { val s = """\n}}}\n""" }\n', false],
    ["行注释里的括号", "package x\n// }}}\nfun f() { }\n", false],
    ["块注释里的括号", "package x\n/* }}} ((( */\nfun f() { }\n", false],
    ["嵌套块注释 + 残留里的 {（旧版误报）", "package x\n/** 外层 /* 内层 */ 外层 { */\nfun ok() {\n}\n", false],
    ["嵌套块注释 + 残留里的 }（旧版漏报）", "package x\n/** 外层 /* 内层 */ 外层 } */\nfun bad() {\n  if (true) {\n}\n", true],
    ["真的少一个右括号", "package x\nfun bad() {\n  if (true) {\n}\n", true],
    ["真的少一个右括号（圆括号）", "package x\nfun bad() = (1 + 2\n", true],
    ["多余一个右括号", "package x\nfun bad() { }\n}\n", true],
    ["未闭合的单行字符串直到行尾（不应死循环）", 'package x\nfun f() { val s = "abc\n}\n', false],
  ];
  let pass = 0, fail = 0;
  for (const [name, src, expectBad] of cases) {
    const m = imbalanceOf(src);
    const gotBad = m !== null;
    const ok = gotBad === expectBad;
    if (ok) pass++; else fail++;
    console.log(`  ${ok ? "OK  " : "FAIL"} ${name} -> ${gotBad ? `braces=${m.b} parens=${m.p}` : "平衡"}（期望${expectBad ? "不平衡" : "平衡"}）`);
  }
  console.log("");
  console.log(fail ? `自检失败 ${fail} 项` : `自检通过：${pass}/${pass}`);
  if (fail) process.exitCode = 1;
  return fail === 0;
}

if (require.main === module) {
  if (process.argv.includes("--self-test")) {
    selfTest();
  } else {
    const arg = process.argv.slice(2).find((a) => !a.startsWith("--"));
    run(arg || "vte/app/src/main/java");
  }
}

module.exports = { strip, imbalanceOf, run };
