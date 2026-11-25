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
node resolve.js      # 工程内部 import 反查声明是否存在
node unused2.js      # 未使用的 import
node checkbraces.js  # 全工程括号平衡
```

这三个脚本挡不住 Kotlin 语义错误，但能挡住一整类常见问题：
文件被删/搬家后 import 没跟着改、花括号数错、多余的 import。

历史上 CI 的失败里有一半是这三类，跑一遍能省掉一整轮 4 分钟的等待。
