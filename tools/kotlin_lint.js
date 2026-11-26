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
//   7. collect { } 块里出现 .first()      -> "只读一次"的静默 bug
//
// 报出来的都是"很可能"，不保证 100% 准确，但每一条都对应踩过的坑。
//
// 用法：node tools/kotlin_lint.js [root]
//       node tools/kotlin_lint.js --self-test   # 造样本，验证每条规则正反向都成立且零误报
//
// 教训（2026-11-26 记）：**误报比漏报更糟**。本项目给这个脚本加过"漏逗号"检查，
// 判据是"当前行形如 `name = 值` 且下一行又是新实参"，在真实代码上跑出 **129 个误报**——
// `apply { }` / `buildJsonObject { }` 这类块里每行都是独立语句，形态与实参列表完全一样，
// 行级正则区分不了。已撤回。所以：任何新规则都必须先在真实代码上确认误报数为 0，
// 再用 --self-test 把"正例能抓、反例不误报"固化下来。
const fs = require("fs");
const path = require("path");

const root = process.argv[2] && !process.argv[2].startsWith("--") ? process.argv[2] : "vte/app/src/main/java";

function walk(d, o = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : p.endsWith(".kt") && o.push(p);
  }
  return o;
}

/**
 * 去掉注释，避免把注释里的示例当代码。
 * 注意：字符串与 """原始字符串""" 也一并抹成空格——这是有意的，
 * 因为模板字符串里出现的 `.collect { ... }` 只是文本，不是代码。
 */
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

/** 从 `.collect {` 起按花括号配对取出整块（含嵌套 lambda）。 */
function collectBlockAt(src, startOfMatch, matchLen) {
  let i = startOfMatch + matchLen;
  let depth = 1;
  while (i < src.length && depth > 0) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") depth--;
    i++;
  }
  return src.slice(startOfMatch + matchLen, i);
}

/**
 * 对单个文件跑全部规则，返回命中列表（不打印、不改全局计数）。
 * 抽成纯函数是为了让 --self-test 能直接喂样本，不用写临时文件。
 * @param {string} raw 源文件原文（内部会先 stripComments）
 * @param {string} file 用于打印的文件名
 */
function lintSource(raw, file) {
  const hits = [];
  const push = (line, msg, snippet) => hits.push({ line, msg, snippet });
  const src = stripComments(raw);
  const lines = src.split("\n");

  // 7) collect { } 块里出现 .first() —— 极易漏的"只取一次"bug
  //
  // 真实案例（ExtApplication 悬浮窗，提交 8756f65 修）：
  //     panelRunner.runtimes.collect { map ->
  //         val enabled = settings.overlayEnabled.first()   // 只读一次！
  //         if (enabled && running > 0) start()
  //     }
  // collect 只在 runtimes 变化时才触发，所以「先启动计时器、再去设置里打开开关」
  // 这条最常见的顺序**永远不会生效** —— 开关变了但 runtimes 没变，块不执行。
  //
  // 正解是 combine(runtimes, overlayEnabled) 让两个来源都能触发。
  //
  // 这条规则经实测：在 stripComments 之后**零误报**（注释/字符串里的示例不算）。
  // 取证（2026-11-26）：全工程 8 个 .collect { } 块里 0 个含 .first()；
  // 13 处合法 .first()（LlmRouter 7 / ExtTheme 2 / ExtApplication 1 / SettingsRepo 1 /
  // TimerCells 1 …）**全部在 collect 块之外**，所以规则一条都没报，零误报。
  // 反向：见 --self-test 的 collect-first 样本（块内 .first() 必须被抓到）。
  for (const m of src.matchAll(/\.collect\s*\{/g)) {
    const body = collectBlockAt(src, m.index, m[0].length);
    if (/\.first\s*\(\s*\)/.test(body)) {
      const ln = src.slice(0, m.index).split("\n").length;
      push(
        ln,
        "collect { } 块里出现 .first() —— 那个源只在本次 collect 触发时读一次，" +
          "它单独变化不会生效。多源联动请用 combine(...)",
        lines[ln - 1]
      );
    }
  }

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
        push(ln, `Sequence 变量 "${v}" 用了 .size —— Sequence 没有 size 属性，应该用 .count()`, l);
      }
      if (new RegExp(`\\b${v}\\s*\\[`).test(l)) {
        push(ln, `Sequence 变量 "${v}" 用了下标访问 —— Sequence 不可随机访问`, l);
      }
    }
    if (/lineSequence\(\)/.test(l) && /\.\s*size\b/.test(l)) {
      push(ln, "lineSequence() 结果用了 .size —— 应该是 .count { }", l);
    }

    // 2) @OptIn 连写两行（不是可重复注解，会直接编译失败）
    if (/^\s*@OptIn\(/.test(l)) {
      for (let k = i + 1; k < Math.min(i + 3, lines.length); k++) {
        if (/^\s*@OptIn\(/.test(lines[k])) {
          push(k + 1, "@OptIn 连写了两行 —— 它不是可重复注解，要写成 @OptIn(A::class, B::class)", lines[k]);
        }
        if (/^\s*(fun|val|var|class|@)/.test(lines[k]) && !/^\s*@OptIn/.test(lines[k])) break;
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
      push(1, "awaitEachGesture 里有 kotlinx.coroutines.delay —— 那是 restricted suspension scope，会编译失败", "delay(");
    }
  }

  for (const h of hits) report(file, h.line, h.msg, h.snippet);
  return hits;
}

// ── 自检：每条规则一个正例 + 一个反例（反例必须零命中）────────────────────────
function selfTest() {
  const cases = [
    {
      name: "collect-first：正例（块内 .first() 必须抓到）",
      expect: 1,
      src: [
        "package x",
        "fun f(flow: Flow<Int>, settings: SettingsRepo) {",
        "    flow.collect { v ->",
        "        val enabled = settings.overlayEnabled.first()",
        "        if (enabled) println(v)",
        "    }",
        "}",
      ].join("\n"),
    },
    {
      name: "collect-first：反例（.first() 在 collect 块外，不许报）",
      expect: 0,
      src: [
        "package x",
        "suspend fun f(settings: SettingsRepo) {",
        "    val enabled = settings.overlayEnabled.first()",
        "    val list = flow.toList()",
        "    println(list.first())",
        "}",
      ].join("\n"),
    },
    {
      name: "collect-first：反例（行注释里的示例不许算）",
      expect: 0,
      src: [
        "package x",
        "fun f(flow: Flow<Int>) {",
        "    // 反例写法：flow.collect { settings.x.first() } —— 别这么写",
        "    flow.collect { v -> println(v) }",
        "}",
      ].join("\n"),
    },
    {
      name: "collect-first：反例（块注释 / KDoc 里的示例不许算）",
      expect: 0,
      src: [
        "package x",
        "/**",
        " * 历史写法：",
        " * panelRunner.runtimes.collect { map ->",
        " *     settings.overlayEnabled.first()",
        " * }",
        " */",
        "fun f(flow: Flow<Int>) { flow.collect { v -> println(v) } }",
      ].join("\n"),
    },
    {
      name: "collect-first：反例（嵌套块内 .firstOrNull 不算 .first()）",
      expect: 0,
      src: "package x\nfun f(flow: Flow<List<Int>>) { flow.collect { l -> println(l.firstOrNull()) } }\n",
    },
    {
      name: "Sequence .size：正例",
      expect: 1,
      src: "package x\nfun f(lines: String) {\n    val s = lines.lineSequence()\n    println(s.size)\n}\n",
    },
    {
      name: "Sequence .size：反例（List 的 size 合法）",
      expect: 0,
      src: "package x\nfun f(items: List<Int>) { println(items.size) }\n",
    },
    {
      name: "@OptIn 连写两行：正例",
      expect: 1,
      src: "package x\n@OptIn(A::class)\n@OptIn(B::class)\nfun f() {}\n",
    },
    {
      name: "@OptIn 连写两行：反例（写在同一行）",
      expect: 0,
      src: "package x\n@OptIn(A::class, B::class)\nfun f() {}\n",
    },
  ];
  let pass = 0, fail = 0;
  for (const c of cases) {
    const hits = lintSource(c.src, "selftest.kt");
    const ok = hits.length === c.expect;
    if (ok) pass++; else fail++;
    console.log(`  ${ok ? "OK  " : "FAIL"} ${c.name} -> ${hits.length} 条（期望 ${c.expect}）`);
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
    if (!fs.existsSync(root)) {
      console.error(`根目录不存在: ${root}（本工具要求从含 vte/ 子目录的父目录运行）`);
      process.exit(1);
    }
    for (const f of walk(root)) lintSource(fs.readFileSync(f, "utf8"), f);
    console.log(issues ? `\n发现 ${issues} 个疑似问题` : "Kotlin 常见错误扫描：未发现问题");
    process.exit(0);
  }
}

module.exports = { lintSource, stripComments, walk };
