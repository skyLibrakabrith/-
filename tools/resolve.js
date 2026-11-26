// 推送前必跑 #1：工程内部 import 能不能解析到真实声明。
//
// 用法：
//   node tools/resolve.js                 # 默认检查 vte/app/src/main/java
//   node tools/resolve.js <root>          # 指定根目录
//   node tools/resolve.js --self-test     # 自检：造样本，证明"坏 import 一定被报"，且干净代码不会被误报
//
// 判定方法：收集每个 package 里的**顶层**声明名（fun/class/interface/object/val/var/typealias），
// 然后对每条 `import at.cwiesner.android.visualtimer.ext...` 从最长前缀往回找最近的已知 package，
// 看该 package 里有没有这个名字。
//
// ── 静默放过的历史（2026-11-26 自查）────────────────────────────────────────
// 旧版的正则 `^import\s+(at\.cwiesner\.android\.visualtimer\.ext\.[\w.]+)\s*$` 有两处会**静默放过**：
//   1. **带 `as` 的 import 一律匹配不上**（`X as Y` 尾部多一个名字），于是这类坏 import 永远不检查。
//      真实代码里 0 处，所以这不是"今天正好没问题"，而是"这条路由没人走过"；
//   2. 行尾带**行注释**的 import 同样匹配不上（正则要求以 `$` 立刻结束）；
//   3. 以上情况还会被**静默跳过**，输出里与"检查过且没问题"长得一模一样。
// 现在：`as` 按"原名"检查（`import a.B as C` 解析的是 `a.B`），行尾注释/`\r`（Windows 换行）先剥掉，
// 并且**所有内部 import 都必须进入检查**——漏看会把 `有 N 条没检查到` 单独打出来。
// 另外：根目录不存在时旧版直接抛 ENOENT 崩掉；现在会明确报错并以退出码 1 结束。
const fs = require('fs'), path = require('path');

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, out) : p.endsWith('.kt') && out.push(p);
  }
  return out;
}

// 把一条 import 行解析成 { full, alias? }；不是 import 行则返回 null。
// 为什么要显式吃掉 `as` 与行尾注释：见文件头"静默放过的历史"。
function parseImport(line) {
  const l = line.replace(/\r$/, '').replace(/\s+\/\/.*$/, '').trim();
  const m = l.match(/^import\s+([\w.]+)(?:\s+as\s+(\w+))?$/);
  if (!m) return null;
  return { full: m[1], alias: m[2] || null };
}

// 把 src 里的顶层声明收进 packages（package 名 -> Set(声明名)）
const declRe =
  /^(?:@\w+\s*)?(?:(?:public|internal|private|abstract|open|sealed|data|enum|inline|suspend|annotation|value|fun)\s+)*(fun|class|interface|object|val|var|typealias)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)/;

function collect(decls, src) {
  const lines = src.split('\n');
  let pkg = '';
  for (const l of lines) {
    const m = l.match(/^package\s+([\w.]+)/);
    if (m) { pkg = m[1]; break; }
  }
  if (!decls.has(pkg)) decls.set(pkg, new Set());
  const set = decls.get(pkg);
  let depth = 0;
  for (const l of lines) {
    if (depth === 0) {
      const m = l.match(declRe);
      if (m) set.add(m[2]);
    }
    depth += (l.match(/\{/g) || []).length - (l.match(/\}/g) || []).length;
    if (depth < 0) depth = 0;
  }
  return pkg;
}

const PREFIX = 'at.cwiesner.android.visualtimer.ext';

/** 检查一条内部 import；能解析返回 null，否则返回原因。 */
function checkOne(decls, full) {
  const segs = full.split('.');
  for (let i = segs.length - 1; i >= 4; i--) {
    const p = segs.slice(0, i).join('.');
    if (!decls.has(p)) continue;
    const name = segs[i];
    return decls.get(p).has(name) ? null : `package ${p} 里没有 "${name}"`;
  }
  // 找不到任何已知 package 前缀。有两种可能，**不能**一视同仁（否则会把"只检查了部分目录"
  // 误报成一片 BROKEN —— 实测用只含 ui/panels 的样本跑，70 条 import 全被误报成"包名拼错"）：
  //   a) 被扫的包里至少有一个带子包（如 ui/wheel），却找不到这条 import 的父包 → 包名真的拼错了，必须报；
  //   b) 被扫的包全是叶子包（没有子包）→ 很可能只跑了子目录，
  //      目标包根本没加载，**无法判定**，单独计数提示，不算 BROKEN。
  const roots = [...decls.keys()].filter((p) => p === PREFIX || p.startsWith(PREFIX + '.'));
  if (!roots.length) return null; // 一个工程包都没加载到，整个问题不该由这条 import 承担
  const hasChild = roots.some((q) => roots.some((r) => r !== q && r.startsWith(q + '.')));
  if (!hasChild) return `无法判定：${full.slice(0, full.lastIndexOf('.'))} 这个包不在扫描范围（是否只跑了子目录？）`;
  return `包名可能拼错（找不到 ${full.slice(0, full.lastIndexOf('.'))}）`;
}
function run(root) {
  if (!fs.existsSync(root)) {
    console.error(`根目录不存在: ${root}（本脚本要求从含 vte/ 子目录的父目录运行）`);
    process.exit(1);
  }
  const files = walk(root);
  const decls = new Map();
  const filePkg = [];
  for (const f of files) filePkg.push([f, collect(decls, fs.readFileSync(f, 'utf8'))]);

  let bad = 0, checked = 0, unknown = 0;
  for (const [f, pkg] of filePkg) {
    for (const l of fs.readFileSync(f, 'utf8').split('\n')) {
      const imp = parseImport(l);
      if (!imp) continue;
      if (!imp.full.startsWith(PREFIX + '.')) continue; // 只查工程内部 import；三方库不在本脚本职责内
      checked++;
      const why = checkOne(decls, imp.full);
      if (!why) continue;
      if (why.startsWith('无法判定')) {
        // 目标包这次没被扫到（只跑了部分目录）。不冒充 BROKEN，也不静默吞掉。
        unknown++;
        console.log(`UNKNOWN ${path.relative(root, f).replace(/\\/g, '/')}  ${l.trim()}`);
        console.log(`        -> ${why}`);
        continue;
      }
      bad++;
      console.log('BROKEN  ' + path.relative(root, f).replace(/\\/g, '/'));
      console.log(`        ${l.trim()}${imp.alias ? `   (注意：带 as ${imp.alias}，判的是原名)` : ''}`);
      console.log(`        -> ${why}`);
    }
  }
  console.log(`\n内部 import 检查 ${checked} 条；broken internal imports: ${bad}`);
  if (unknown) {
    console.log(`${unknown} 条无法判定（目标包不在本次扫描范围）。全量跑 vte/app/src/main/java 时应为 0。`);
  }
  return { bad, checked, unknown, files: files.length, decls };
}

// ── 自检 ─────────────────────────────────────────────────────────────────────
// 只跑"解析 + 判定"这一层（不写临时目录也能覆盖全部判据），保证：
//   * 干净的 import 一条都不报；
//   * 坏 import 一定被报 —— 包括**带 `as`**、**行尾带注释**、**Windows 换行**这三种旧版会静默放过的情况。
function selfTest() {
  const decls = new Map();
  collect(decls, 'package at.cwiesner.android.visualtimer.ext.probe\n\nclass Exists\n\nobject Pack\n');
  collect(decls, 'package at.cwiesner.android.visualtimer.ext.data\n\nclass TimerItem\n');
  const P = 'at.cwiesner.android.visualtimer.ext';
  const cases = [
    [`import ${P}.probe.Exists`, true],
    [`import ${P}.probe.Exists as Alias`, true],        // 旧版匹配不上 → 不检查
    [`import ${P}.probe.Exists // 说明`, true],          // 旧版匹配不上 → 不检查
    [`import ${P}.probe.Exists\r`, true],                // Windows 换行
    [`import ${P}.probe.Missing`, false],
    [`import ${P}.probe.Missing as Alias`, false],       // 旧版会**静默放过**
    [`import ${P}.probe.Missing // 说明`, false],
    // 顶层包（ext 本身）：旧版 `i >= 4` 的循环根本走不到 i=4 以下，这类 import 从来没被检查过
    [`import ${P}.ExtApplication`, false],
    [`import ${P}.data.TimerItem`, true],                // 对照组：干净的要放过
  ];
  let pass = 0, fail = 0;
  for (const [line, shouldOk] of cases) {
    const imp = parseImport(line);
    const parsed = !!imp;
    const why = imp ? checkOne(decls, imp.full) : '解析失败';
    const ok = parsed && (shouldOk ? why === null : why !== null);
    if (ok) pass++; else fail++;
    console.log(`  ${ok ? 'OK  ' : 'FAIL'} ${JSON.stringify(line)} -> ${parsed ? (why || '可解析') : 'line 未被识别为 import'}`);
  }
  console.log('');
  console.log(fail ? `自检失败 ${fail} 项` : `自检通过：${pass}/${pass}`);
  if (fail) process.exitCode = 1;
  return fail === 0;
}

if (require.main === module) {
  if (process.argv.includes('--self-test')) {
    selfTest();
  } else {
    const arg = process.argv.slice(2).find((a) => !a.startsWith('--'));
    run(arg || 'vte/app/src/main/java');
  }
}

module.exports = { run, parseImport, checkOne, collect };
