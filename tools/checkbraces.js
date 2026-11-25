// 全工程括号平衡检查（去掉字符串与注释后再数）
const fs = require("fs"), path = require("path");
const root = process.argv[2] || "vte/app/src/main/java";
function walk(d, o = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    e.isDirectory() ? walk(p, o) : p.endsWith(".kt") && o.push(p);
  }
  return o;
}
let bad = 0;
for (const f of walk(root)) {
  const s = fs.readFileSync(f, "utf8")
    .replace(/"""[\s\S]*?"""/g, "")
    .replace(/"(?:[^"\\\n]|\\.)*"/g, "")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");
  const b = (s.match(/\{/g) || []).length - (s.match(/\}/g) || []).length;
  const p = (s.match(/\(/g) || []).length - (s.match(/\)/g) || []).length;
  if (b || p) { bad++; console.log("  !! " + f + "  braces=" + b + " parens=" + p); }
}
console.log(bad ? "括号异常 " + bad + " 个文件" : "括号全部平衡");
