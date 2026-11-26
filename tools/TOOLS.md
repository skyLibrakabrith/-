# tools/ 脚本说明

这些脚本是**在手机上开发**时的替代工具链（没有 git、没有 JDK、没有 Gradle）。

所有脚本都用 `node` 直接跑，只依赖 Node 内置模块。

## 推送

```bash
GH_TOKEN=<fine-grained PAT，需 Contents: Read and write> \
node push2.js "skyLibrakabrith/-" "vte" "提交信息"
```

增量推送：只上传变化文件的 blob，用 `base_tree` 复用远端已有的 git 对象。
首次推空仓库会自动走 Contents API 做引导提交（否则 409）。

内置 401/5xx 重试（GitHub 偶发瞬时 401，重试即可恢复；
如果 5 次都失败则是 token 真失效）。

## 查看状态

```bash
node diff.js            # 本地 vs 远端 HEAD 的差异
node runs.js 5          # 最近 5 次 workflow 运行
node watch.js <runId>   # 等构建结束；成功 exit 0，失败 exit 1
```

## 取日志 / 取产物

```bash
node getlog.js <runId>      # 只打印 e: file / error: / FAILED 等行
node dl.js <artifactId>     # 下载 artifact 并解出 app-debug.apk
```

artifactId 从下面这条拿：

```bash
GH_TOKEN=xxx node -e '
const https=require("https");
https.request({host:"api.github.com",
  path:"/repos/skyLibrakabrith/-/actions/runs/<runId>/artifacts",
  headers:{"User-Agent":"d",Accept:"application/vnd.github+json",
           Authorization:"Bearer "+process.env.GH_TOKEN}},r=>{
  const c=[];r.on("data",x=>c.push(x));r.on("end",()=>{
    const j=JSON.parse(Buffer.concat(c).toString());
    console.log(j.artifacts[0].id);})}).end();'
```

⚠️ `dl.js` 里有个坑已经处理好了：GitHub 会 302 到 Azure Blob 的签名 URL，
**跟随时必须去掉 `Authorization` 头**，否则 Azure 返回
`InvalidAuthenticationInfo`。

## 推送前必跑（相当于"本地编译"的替代品）

```bash
node resolve.js      # 工程内部 import 反查声明是否存在（--self-test 自带自检）
node unused2.js      # 未使用的 import（--self-test 自带自检）
node checkbraces.js  # 全工程括号平衡（--self-test 自带自检）
node kotlin_lint.js  # 已知会让 CI 编译失败的几类写法（--self-test 自带自检）
```

这四个脚本挡不住 Kotlin 语义错误，但能挡住一整类常见问题：
文件被删/搬家后 import 没跟着改、花括号数错、多余的 import。

历史上 CI 的失败里有一半是这三类，跑一遍能省掉一整轮 4 分钟的等待。

改这些判据后**必须**做两件事：① 跑 `--self-test` 全绿；② 真实代码输出逐条人工核对误报。

### 2026-11-26 的一次自查：四个脚本里三处"会撒谎"的地方

| 脚本 | 问题 | 后果 |
|---|---|---|
| `unused2.js` | `if (unused.length > 1)` 才打印 | 只有 1 个未使用 import 的文件永远静默通过（判定是文件级的，任何文件都可能中招） |
| `resolve.js` | 内部 import 正则 `\s*$` 匹配不上 `import X as Y` / 行尾带注释的行 | 这类坏 import **从来没被检查过**；根目录不存在时直接崩 |
| `checkbraces.js` | 块注释用 `indexOf("*/")` 不配对嵌套 | Kotlin 允许嵌套块注释；`/** 外层 /* 内层 */ 外层 } */` 会让真缺的 `}` 被当成注释 → **漏报** |
| `kotlin_lint.js` | `@OptIn` 连写后总会 `break`，第 3 行起的连写漏报 | 3 行以上 `@OptIn` 连写抓不到（另有个空循环的模板检查，从未生效） |

四处都已修，且每个脚本现在都带 `--self-test`。

### 两条判据教训（改判据前必读）

1. **`getValue` / `setValue` 的豁免是"按全限定名"的白名单**：
   `var x by remember { mutableStateOf(..) }` 靠它们提供 `operator`，正文里一次都不会出现这两个名字，
   删了就编译失败。豁免必须是精确的全限定名，不能放宽成"名字叫 getValue 就放过"。
   `unused2.js --self-test` 里有反向验证：关掉豁免后这两条**必须**被抓到。
2. **误报比漏报更糟**。本项目给 `kotlin_lint.js` 加"漏逗号"检查，判据是"当前行形如 `name = 值`
   且下一行是新实参"，在真实代码上跑出 **129 个误报**（`apply { }` / `buildJsonObject { }`
   里每行都是独立语句，形态与实参列表一模一样，行级正则区分不了），已撤回。
   所以任何新判断都要先报出"真实代码上的误报数"，>0 就得改判据或撤回。
