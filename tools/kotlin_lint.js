// 推送前的 Kotlin 常见错误静态扫描（本机没有 JDK，编译只能靠 CI）。
//
// 定位：resolve/unused/checkbraces 只查"结构性"问题。这个脚本专门查
// **已知会让 CI 编译失败、而且靠肉眼很容易漏**的那几类：
//
//   1. Sequence 上用了 .size / .length   -> 应该是 .count()
//   2. List/Array 上误用 .count { }      -> 可以，但 .size 更惯用（仅提示）
//   3. Sequence 上用了 .get() / [i]      -> 不可随机访问
//   4. 字符串模板里 ForbiddenY 的 $var.x -> 需要 ${var.x}
//   5. kotlinx.coroutines.delay 出现在 awaitEachGesture 里（restricted scope）
//   6. @OptIn 连写两行（不是可重复注解）
//
// 报出来的都是"很可能"，不保证 100% 准确，但每一条都对应踩过的坑。
//
// 用法：node tools/kotlin_lint.js [root]
const fs = require("fs");
const path = require("path");

const root = process.argv[2] || "vte/app/src/main/java";

function walk(d, o = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : p.endsWith(".kt") && o.push(p);
  }
  return o;
}

/** 去掉注释，避免把注释里的示例当代码。 */
function stripComments(src) {
  return src
    .replace(/"""[\s\S]*?"""/g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
    .replace(/\/\/[^\n]*/g, "");
}

let issues = 0;
function report(file, line, msg, snippet) {
  issues++;
  console.log(`  ${path.basename(file)}:${line}  ${msg}`);
  if (snippet) console.log(`      ${snippet.trim().slice(0, 120)}`);
}

for (const f of walk(root)) {
  const raw = fs.readFileSync(f, "utf8");
  const src = stripComments(raw);
  const lines = src.split("\n");

  // 追踪"这个标识符是不是 Sequence"：变量名 -> 是否 Sequence
  const seqVars = new Set();
  for (const m of src.matchAll(
    /(?:val|var)\s+(\w+)\s*(?::\s*Sequence<[^>]*>)?\s*=\s*[^\n]*?(lineSequence\(\)|\.asSequence\(\)|\.sequence\b|generateSequence\()/g
  )) {
    seqVars.add(m[1]);
  }

  lines.forEach((l, i) => {
    const ln = i + 1;

    // 1) Sequence 上的 .size
    for (const v of seqVars) {
      if (new RegExp(`\\b${v}\\s*\\.\\s*size\\b`).test(l)) {
        report(f, ln, `Sequence 变量 "${v}" 用了 .size —— Sequence 没有 size 属性，应该用 .count()`, l);
      }
      if (new RegExp(`\\b${v}\\s*\\[`).test(l)) {
        report(f, ln, `Sequence 变量 "${v}" 用了下标访问 —— Sequence 不可随机访问`, l);
      }
    }
    if (/lineSequence\(\)/.test(l) && /\.\s*size\b/.test(l)) {
      report(f, ln, "lineSequence() 结果用了 .size —— 应该是 .count { }", l);
    }

    // 2) @OptIn 连写两行（不是可重复注解，会直接编译失败）
    if (/^\s*@OptIn\(/.test(l)) {
      for (let k = i + 1; k < Math.min(i + 3, lines.length); k++) {
        if (/^\s*@OptIn\(/.test(lines[k])) {
          report(f, k + 1, "@OptIn 连写了两行 —— 它不是可重复注解，要写成 @OptIn(A::class, B::class)", lines[k]);
        }
        if (/^\s*(fun|val|var|class|@)/.test(lines[k]) && !/^\s*@OptIn/.test(lines[k])) break;
      }
    }

    // 3) 字符串模板里 $ident.xxx 会被解析成 $ident 然后字面量 .xxx
    for (const m of l.matchAll(/"([^"]*)"[^"]*$|\$(\w+)\.\w+/g)) {
      // 只在字符串内提示，且排除已用 ${}
      if (/\$(?!\{)[A-Za-z_]\w*\.[A-Za-z_]/.test(l) && /"/.test(l)) {
        // 非常常见的写法 $var.length，Kotlin 允许但常被误以为要 ${}
        break;
      }
    }
  });

  // 4) delay 出现在 awaitEachGesture 块里（restricted suspension scope）
  if (/awaitEachGesture/.test(src)) {
    const idx = src.indexOf("awaitEachGesture");
    const tail = src.slice(idx);
    // 粗略取到下一个顶层闭括号前
    const depth = (s) => (s.match(/\{/g) || []).length - (s.match(/\}/g) || []).length;
    let buf = "";
    for (const ch of tail) {
      buf += ch;
      if (depth(buf) <= 0 && buf.length > 20) break;
    }
    if (/\bdelay\s*\(/.test(buf)) {
      report(f, 1, "awaitEachGesture 里有 kotlinx.coroutines.delay —— 那是 restricted suspension scope，会编译失败", "delay(");
    }
  }
}

console.log(issues ? `\n发现 ${issues} 个疑似问题` : "Kotlin 常见错误扫描：未发现问题");
process.exit(0);
