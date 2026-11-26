#!/usr/bin/env node
// 推送前必跑 #2：未使用的 import（本机没有 JDK，这是"多余 import / 跨文件留下的死引用"的兜底检查）。
//
// 用法：
//   node tools/unused2.js            # 正常检查（默认 vte/app/src/main/java）
//   node tools/unused2.js --self-test  # 自检：在临时目录造样本，先证明"不修就一直绿"，再证明修好了
//
// 为什么要写这么长的一段注释：这个脚本曾经**会撒谎**，而且撒得很安静（ARCHITECTURE_REVIEW §2.22）。
// 下面每一个判断都写清了"为什么这样判"，改判据前先读对应段落。
//
// ── 历史 ────────────────────────────────────────────────────────────────────
// 旧版第 18 行是 `if (unused.length > 1) console.log(...)`：
//   * **只报"这个文件里 ≥2 个未使用 import"**。恰好只有 1 个未使用 import 的文件，永远静默通过。
//     判定发生在**文件级**，所以任何文件都可能中招（"1 个没用 import 的文件"= 漏报 1 处）。
//     2026-11-26 复查时真实代码刚好全绿，于是用 --self-test 里的 OnlyImport.kt 固化了这个反例。
//   * 同一个 `if` 还把**逐文件明细**关在里面：总计数只累加、却只在"该文件 ≥2 个"时才打印明细。
//     加上下面那条"注释算用过"的漏报，末尾那句 "REAL unused imports" 在有漏报时并不可信。
//     这就是"会撒谎的门槛比没有门槛更危险"。
//   * 旧版把**注释里的名字**也算"用过了"：`/** 提到 [AlertProfile] */`、`// 以前是 Box(...)`
//     都算使用。实测 4 个这类文件被它放过（MainActivity.fillMaxHeight 等）。
// 所以修复是两步：① 去掉 `> 1`；② 判定只认"代码区"，并且**把 import 行本身抹掉**
// （否则 `import android.app.Activity` 这一行自己就"用到了" Activity）。
//
// ── 误报比漏报更糟 ──────────────────────────────────────────────────────────
// 本项目给 kotlin_lint.js 加过"漏逗号"检查，判据是"当前行形如 `name = 值` 且下一行是新实参"，
// 结果在真实代码上跑出 129 个误报（`apply { }` / `buildJsonObject { }` 块里每行都是独立语句，
// 形态与实参列表完全一样，行级正则区分不了），只能撤回。
// 所以本脚本每次改判据都**必须在真实代码上确认误报数为 0**；`--self-test` 把这件事固化成可跑的自检。
//   当前判据在真实代码上的结果（2026-11-26）：
//     75 个文件 / 1454 条 import → 报 2 条，**误报 0**（两条都逐行核对过，
//     名字只出现在注释里：MainActivity.kt:19 fillMaxHeight、PanelDialogs.kt:23 LaunchedEffect）。
'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

// ── 1. 两个豁免：不是"漏报"，是"看起来没用、其实必须有" ───────────────────────
//    `val x by remember { mutableStateOf(..) }` 依赖 `androidx.compose.runtime.getValue`，
//    `var x by remember { mutableStateOf(..) }` 依赖 `setValue`。这两个名字在正文里
//    一次都不会出现，删掉 import 会直接编译失败（委托语法靠 import 提供的 operator）。
//    所以它们必须白名单，而且只白名单**这两个全限定名**，不是"任何叫 getValue 的 import"。
const SELF_PROVIDED = new Set([
  'androidx.compose.runtime.getValue',
  'androidx.compose.runtime.setValue',
]);

// ── 2. 代码区提取 ─────────────────────────────────────────────────────────────
// 为什么不能像 kotlin_lint.js 那样直接删掉注释：那样会改变字符偏移，行号对不上。
// 这里把注释字符替换成等量空格（换行保留），于是**行号、列号都不变**，
// 既能只搜"代码区"，又能准确报出 import 在第几行。
// 字符串字面量**不清空**（`"Activity"` 算不算用了 Activity 见 §3 的保守取舍）；
// 但其中的 `/*` `//` 不能当注释起点，所以必须逐字符扫描，不能用全局正则。
function codeMask(src) {
  const out = src.split('');
  const n = src.length;
  const blank = (a, b) => { for (let k = a; k < b && k < n; k++) if (out[k] !== '\n') out[k] = ' '; };
  let i = 0;
  while (i < n) {
    // """ 多行原始字符串是**代码**（可以有 ${} 模板），整段跳过、不做注释处理
    if (src.startsWith('"""', i)) {
      const j = src.indexOf('"""', i + 3);
      i = j < 0 ? n : j + 3;
      continue;
    }
    const c = src[i];
    if (c === '"' || c === "'") {
      // 普通字符串 / 字符字面量：跳过转义，防止 "\\"" 被当成字符串结束
      i++;
      while (i < n) {
        if (src[i] === '\\') { i += 2; continue; }
        if (src[i] === c) { i++; break; }
        if (c === '"' && src[i] === '\n') break; // 未闭合，防死循环
        i++;
      }
      continue;
    }
    if (src.startsWith('//', i)) {
      const j = src.indexOf('\n', i);
      const e = j < 0 ? n : j;
      blank(i, e);
      i = e;
      continue;
    }
    if (src.startsWith('/*', i)) {
      // Kotlin 的块注释**可以嵌套**（`/* /* */ */`），按深度配对；
      // 否则 `/** 说明一下 /* 内层 */ 还有 */` 这种注释会被提前结束，后半截当代码。
      let depth = 0, k = i;
      while (k < n) {
        if (src.startsWith('/*', k)) { depth++; k += 2; continue; }
        if (src.startsWith('*/', k)) { depth--; k += 2; if (depth === 0) break; continue; }
        k++;
      }
      blank(i, Math.min(k, n));
      i = k;
      continue;
    }
    i++;
  }
  return out.join('');
}

/** 去掉注释，避免把注释里的示例当代码（供脚本内部与自检使用）。 */
function stripComments(src) {
  return codeMask(src);
}

// ── 3. 每个 import 去哪段文字里找"有没有被用到" ──────────────────────────────
// 为什么是"**本文件**代码区，且把 import 行抹掉"，而不是别的写法（都实测过）：
//   * 全文连 import 行一起搜 → `import android.app.Activity` 这一行自己就"用到了" Activity，
//     **脚本永远全绿**；
//   * 只搜"最后一条 import 之后" → 行号干净，但**实测 47 个 import 会被误报**：名字只出现在
//     它自己那条 import 行上（别名 `import X as Y` 里的 Y、或正文其实没写的 `import a.B` 的 B），
//     被当成"用过"；
//   * **跨文件搜**（旧版其实就是这么干的：`walk(root)` 里每次 readFileSync 的都是当次文件，
//     但判定用正则的 `\b` 在中文/`$` 处失效，等于把中文注释也当成"用过"）会重复犯错；
//     真实代码里 314 个不重复 import 名中 166 个出现在不止一个文件里，其中 15 个还正好是本工程
//     自己的文件名（`TimerItem` / `ExtApplication` 这类），跨文件判定必然失真；
//   * 最终做法：在**本文件**把注释抹成空格（§2）、把 import 行抹成等长空格（行号列号都不变），
//     再在这段代码里搜名字。每个 import 只靠"本文件里、自己那条 import 之外"出现的同名文字判定，
//     与 Kotlin"这个 import 提供的名字在本文件正文里是否被引用"的语义一致。
// 用子串而不是 `\b` 正则：`\b` 只把 [A-Za-z0-9_] 当词字符，**中文和 `$` 都算分隔符**，
// 于是 `${items.size}`、`中文里的 Foo` 都会被误判为"用到了 items/Foo"。
// 唯一跳过的是反引号标识符（``utils.`when` `` / `` `in`() ``）：那是方法名，不是被 import 的类型名，
// 真实代码 0 处；跳过比"猜"安全。
// 已知取舍：字符串字面量里的同名文字（`"Activity"`）也算用过 —— 宁可漏报，不制造误报。
const BACKTICKED_RE = /`[^`\n]*`/g;
function isUsed(name, code) {
  return code.replace(BACKTICKED_RE, ' ').includes(name);
}

// ── 4. 单文件分析 ────────────────────────────────────────────────────────────
const IMPORT_RE = /^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$/;
const IMPORT_LINE_RE = /^\s*import\s+\S/;

function analyze(src, opts) {
  // opts.strictExempt：把 getValue/setValue 也当普通 import 判（只给 --self-test 用，
  // 用来证明"豁免确实在挡东西"，否则白名单就成了新的静默放过）
  const strictExempt = !!(opts && opts.strictExempt);
  const rawLines = src.split('\n');
  const codeLines = codeMask(src).split('\n');

  // 把 import 行原地抹成等长空格（见 §3）：否则"名字有没有被用到"会被 import 行自己满足
  for (let i = 0; i < codeLines.length; i++) {
    if (IMPORT_LINE_RE.test(rawLines[i] || '')) codeLines[i] = ' '.repeat(codeLines[i].length);
  }
  const body = codeLines.join('\n');

  const unused = [];
  const exemptUsed = [];
  const wildcard = [];
  rawLines.forEach((line, i) => {
    const m = line.match(IMPORT_RE);
    if (!m) return;
    const fq = m[1];
    const name = m[2] || fq.split('.').pop();
    // 通配符 import 无法静态判断用没用到：不报、也不计入总数
    if (name === '*') { wildcard.push({ line: i + 1, text: line.trim() }); return; }
    // 豁免项只统计、不报错（见 §1）
    if (!strictExempt && SELF_PROVIDED.has(fq)) { exemptUsed.push({ line: i + 1, text: line.trim() }); return; }
    // 别名 import 按别名判；普通 import 按末段名判（正文里引用的就是这个名字）
    if (isUsed(name, body)) return;
    unused.push({ line: i + 1, text: line.trim(), name });
  });

  return { unused, exemptUsed, wildcard };
}

// ── 5. 遍历与主流程 ─────────────────────────────────────────────────────────
function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.kt')) out.push(p);
  }
  return out;
}

function scan(root) {
  const files = walk(root);
  let total = 0;
  const details = [];
  for (const f of files) {
    const { unused } = analyze(fs.readFileSync(f, 'utf8'));
    if (!unused.length) continue;
    // 关键：**不管几个都报**。旧版的 `> 1` 就是在这里吞掉单个未使用 import 的。
    total += unused.length;
    details.push({ file: path.relative(root, f).replace(/\\/g, '/'), unused });
  }
  return { total, files: files.length, filesWithUnused: details.length, details };
}

function run(root) {
  const r = scan(root);
  for (const d of r.details) {
    console.log(`${d.unused.length}  ${d.file}`);
    for (const u of d.unused) console.log(`     ${String(u.line).padStart(4)}: ${u.text}`);
  }
  console.log('');
  console.log(`REAL unused imports (excl. getValue/setValue): ${r.total}`);
  if (r.filesWithUnused) {
    console.log(`涉及 ${r.filesWithUnused} 个文件；删掉 import 后重跑本脚本。`);
  }
  return r;
}

// ── 6. 自检：先证明"旧判据会放过"，再证明"新判据抓得住、且不误报" ──────────────
// 这是唯一的自动化证据，改动判据后必须跑：node tools/unused2.js --self-test
function selfTest() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'unused2-selftest-'));
  const pkg = path.join(tmp, 'vte/app/src/main/java/at/cwiesner/android/visualtimer/ext/t');
  fs.mkdirSync(pkg, { recursive: true });
  const write = (name, body) => fs.writeFileSync(path.join(pkg, name), body, 'utf8');

  // A. 只有 1 个 import，而且没被用 —— 旧版 `> 1` 下**永远静默通过**（这就是漏报主案例）
  write('OnlyImport.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import android.os.Build',
    '',
    'class OnlyImport {',
    '    val x = 1',
    '}',
    '',
  ].join('\n'));

  // B. 2 个 import 都没用（对照组，旧版能报；总数应当 = 1 + 2）
  write('TwoImports.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import android.os.Build',
    'import android.app.Activity',
    '',
    'class TwoImports',
    '',
  ].join('\n'));

  // C. 名字**只出现在注释里**（KDoc 链接 / 行注释）—— 不能算"用过"
  write('CommentOnly.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import kotlinx.coroutines.CoroutineScope',
    '',
    '/**',
    ' * 这里提到 [CoroutineScope] 只是文档链接。',
    ' * 也用 [LaunchedEffect] 举例。',
    ' */',
    '// 老代码是 CoroutineScope(Dispatchers.Main)',
    'class CommentOnly',
    '',
  ].join('\n'));

  // D. 必须保留的委托语法：正文里没有 getValue/setValue 字样，但删了就编译失败 —— 不能报
  write('Delegated.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import androidx.compose.runtime.getValue',
    'import androidx.compose.runtime.setValue',
    'import androidx.compose.runtime.mutableStateOf',
    'import androidx.compose.runtime.remember',
    '',
    'fun Delegated() {',
    '    var v by remember { mutableStateOf(0) }',
    '    v = v + 1',
    '}',
    '',
  ].join('\n'));

  // E. 别名 import：别名被用就算用过；只 import 了、正文没用的别名要被抓到
  write('Aliased.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import androidx.compose.runtime.collectAsState as cas',
    'import androidx.compose.runtime.SnapshotStateList',
    '',
    'fun Aliased(flow: Any) {',
    '    val a = cas',
    '    val b: SnapshotStateList<Int>? = null',
    '}',
    '',
  ].join('\n'));

  // E2. 别名在正文里完全没用到 —— 必须报（名字只出现在 import 行上，若 import 行不抹掉就会漏）
  write('AliasUnused.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import androidx.compose.runtime.collectAsState as cas',
    '',
    'class AliasUnused',
    '',
  ].join('\n'));

  // F. 嵌套块注释 `/* 内层 */` 不能把后面的代码吃成注释（Kotlin 允许嵌套块注释）
  write('NestedComment.kt', [
    'package at.cwiesner.android.visualtimer.ext.t',
    '',
    'import android.os.Build',
    '',
    '/** 说明 /* 内层 */ 结束 */',
    'fun buildInt(): Int = Build.VERSION.SDK_INT',
    '',
  ].join('\n'));

  // ── 旧判据（原样复刻，用于证明它真的会放过）────────────────────────────────
  //   旧逻辑：① 拼"去掉 import 行"的全文当正文（注释、字符串都在里面）；
  //          ② `\b名字\b` 正则判有没有出现；③ **只有 unused.length > 1 才打印**。
  function oldScan() {
    const printed = []; // 旧版真的会打印出来的行
    let realTotal = 0; // 旧版内部累加到的总数（不一定会被打印）
    for (const f of walk(path.join(tmp, 'vte'))) {
      const lines = fs.readFileSync(f, 'utf8').split('\n');
      const body = lines.filter((l) => !l.trim().startsWith('import ')).join('\n');
      const unused = [];
      for (const l of lines) {
        const m = l.match(IMPORT_RE);
        if (!m) continue;
        const name = m[2] || m[1].split('.').pop();
        if (name === '*' || name === 'getValue' || name === 'setValue') continue;
        if (!new RegExp('\\b' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\b').test(body)) unused.push(name);
      }
      if (unused.length) {
        realTotal += unused.length;
        if (unused.length > 1) printed.push(path.basename(f) + ': ' + unused.join(', '));
      }
    }
    // 旧版末尾打印的"REAL unused imports"：注意它是 realTotal 全量，但因为上面吞了单条，
    // 真实的"未使用 import 有几条"它给不出来 —— 这里用它来对比即可。
    return { printed, realTotal };
  }

  const old = oldScan();
  const neu = scan(tmp);

  // 期望：新判据报 5 条（OnlyImport 1 + TwoImports 2 + CommentOnly 1 + AliasUnused 1）
  const EXPECT_TOTAL = 5;
  const EXPECT_FILES = ['AliasUnused.kt', 'CommentOnly.kt', 'OnlyImport.kt', 'TwoImports.kt'];
  const gotFiles = neu.details.map((d) => d.file.split('/').pop()).sort();

  console.log('自检样本:');
  console.log(`  旧判据: 会打印的行=${JSON.stringify(old.printed)}（漏掉了不在里头的那些文件）`);
  console.log(`  新判据: 计数=${neu.total}, 涉及文件=${JSON.stringify(gotFiles)}`);

  const ok = [];
  // ④ 核心回归：`> 1` 必须先被证明"真的会漏"
  push(ok, '旧判据漏掉 OnlyImport.kt（文件里只有 1 个未使用 import）', !old.printed.some((h) => h.startsWith('OnlyImport')));
  push(ok, '旧判据漏掉 AliasUnused.kt（同样只有 1 个）', !old.printed.some((h) => h.startsWith('AliasUnused')));
  push(ok, '旧判据被注释骗过（CommentOnly.kt 只在注释里提到名字，没被报告）', !old.printed.some((h) => h.startsWith('CommentOnly')));
  push(ok, '旧判据只打印了 TwoImports.kt 一行（对照：≥2 个才报）', old.printed.length === 1 && old.printed[0].startsWith('TwoImports'));
  // 新判据必须全部抓到
  for (const f of EXPECT_FILES) push(ok, `新判据抓到 ${f}`, gotFiles.includes(f));
  push(ok, '新判据计数 = ' + EXPECT_TOTAL, neu.total === EXPECT_TOTAL);
  push(ok, `涉及文件正好是 ${JSON.stringify(EXPECT_FILES)}`, JSON.stringify(gotFiles) === JSON.stringify(EXPECT_FILES));
  // 误报回归（这几条都必须"不被报"）
  push(ok, '豁免项 Delegated.kt 未被报告（getValue/setValue 是 by 委托必需）', !gotFiles.includes('Delegated.kt'));
  push(ok, '别名 import 未被误报（Aliased.kt）', !gotFiles.includes('Aliased.kt'));
  push(ok, '嵌套块注释之后的 Build 算用过（NestedComment.kt）', !gotFiles.includes('NestedComment.kt'));
  // ⑤ 反向验证豁免不是"白名单式静默放过"：关掉豁免后 must 抓到 Delegated.kt 的两条
  const strict = analyze(fs.readFileSync(path.join(pkg, 'Delegated.kt'), 'utf8'), { strictExempt: true });
  push(
    ok,
    '关掉豁免后 getValue/setValue 确实会被判未使用（证明豁免在挡东西）',
    strict.unused.map((u) => u.name).sort().join(',') === 'getValue,setValue'
  );

  fs.rmSync(tmp, { recursive: true, force: true });
  const bad = ok.filter((x) => !x.pass);
  console.log('');
  console.log(bad.length ? `自检失败 ${bad.length} 项` : `自检通过：${ok.length}/${ok.length}`);
  process.exitCode = bad.length ? 1 : 0;
  return bad.length === 0;
}

function push(list, desc, pass) {
  list.push({ desc, pass });
  console.log(`  ${pass ? 'OK  ' : 'FAIL'} ${desc}`);
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const arg = process.argv.slice(2).find((a) => !a.startsWith('--'));
    run(arg || 'vte/app/src/main/java');
  }
}

module.exports = { analyze, codeMask, stripComments, walk, scan, run, SELF_PROVIDED };
