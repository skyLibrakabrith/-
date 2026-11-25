// 增量推送：只上传变化的 blob，用 base_tree 复用其余对象
// 用法: GH_TOKEN=xxx node push2.js <owner/repo> <本地目录> "<提交信息>"
const fs=require("fs"),path=require("path"),https=require("https"),crypto=require("crypto");
const [REPO,DIR,MSG]=process.argv.slice(2);
const TOKEN=process.env.GH_TOKEN;
if(!TOKEN){console.error("缺少 GH_TOKEN");process.exit(2)}
if(!REPO||!DIR){console.error("usage: GH_TOKEN=... node push2.js <owner/repo> <dir> \"<msg>\"");process.exit(2)}
function apiOnce(method,p,body){const d=body?Buffer.from(JSON.stringify(body)):null;
 return new Promise((res,rej)=>{const h={"User-Agent":"dsh-push","Accept":"application/vnd.github+json",Authorization:"Bearer "+TOKEN,"X-GitHub-Api-Version":"2022-11-28"};
  if(d){h["Content-Type"]="application/json";h["Content-Length"]=d.length;}
  const q=https.request({host:"api.github.com",path:p,method,headers:h,timeout:90000},r=>{const c=[];r.on("data",x=>c.push(x));r.on("end",()=>{const t=Buffer.concat(c).toString();
   if(r.statusCode>=200&&r.statusCode<300){try{res(JSON.parse(t))}catch{res(t)}}else rej(new Error(method+" "+p+" -> "+r.statusCode+" "+t.slice(0,400)))})});
  q.on("error",rej);q.on("timeout",()=>q.destroy(new Error("timeout")));if(d)q.write(d);q.end();})}
// GitHub 偶发瞬时 401 / 5xx，重试几次即可恢复；真·凭据失效会在几次后仍然失败
async function api(method,p,body){
  let last;
  for(let i=0;i<5;i++){
    try { return await apiOnce(method,p,body); }
    catch(e){
      last=e;
      const m=/-> (\d{3})/.exec(e.message);
      const code=m?Number(m[1]):0;
      const retryable = code===401||code===429||code>=500||code===0;
      if(!retryable||i===4) break;
      await new Promise(r=>setTimeout(r,1200*(i+1)));
      console.error("  · 重试 "+(i+1)+" ("+code+")");
    }
  }
  throw last;
}
const gitSha=b=>crypto.createHash("sha1").update("blob "+b.length+"\0").update(b).digest("hex");
function walk(dir,base=""){const o=[];for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.name===".git")continue;
 const rel=base?base+"/"+e.name:e.name,full=path.join(dir,e.name);
 if(e.isDirectory())o.push(...walk(full,rel));else o.push({rel,full,sha:gitSha(fs.readFileSync(full))})}return o}
(async()=>{
 const ref=await api("GET","/repos/"+REPO+"/git/ref/heads/main");
 const head=ref.object.sha;
 const commit=await api("GET","/repos/"+REPO+"/git/commits/"+head);
 const remoteTree=await api("GET","/repos/"+REPO+"/git/trees/"+commit.tree.sha+"?recursive=1");
 const remote=new Map(remoteTree.tree.filter(e=>e.type==="blob").map(e=>[e.path,e.sha]));
 const local=walk(DIR);
 const localMap=new Map(local.map(f=>[f.rel,f.sha]));
 const mod=[],add=[],del=[];
 for(const f of local){ if(!remote.has(f.rel)) add.push(f); else if(remote.get(f.rel)!==f.sha) mod.push(f); }
 for(const rel of remote.keys()) if(!localMap.has(rel)) del.push(rel);
 console.log("变更: 修改 "+mod.length+" / 新增 "+add.length+" / 删除 "+del.length+"  (HEAD "+head.slice(0,8)+")");
 if(mod.length+add.length+del.length===0){console.log("无变化，跳过");return}
 const tree=[];
 for(const f of [...mod,...add]){
   const blob=await api("POST","/repos/"+REPO+"/git/blobs",{content:fs.readFileSync(f.full).toString("base64"),encoding:"base64"});
   tree.push({path:f.rel,mode:"100644",type:"blob",sha:blob.sha});
   process.stdout.write(".");
 }
 for(const p of del){ tree.push({path:p,mode:"100644",type:"blob",sha:null}); process.stdout.write("x"); }
 console.log("\nblob 完成");
 const newTree=await api("POST","/repos/"+REPO+"/git/trees",{base_tree:commit.tree.sha,tree});
 const newCommit=await api("POST","/repos/"+REPO+"/git/commits",{message:MSG,tree:newTree.sha,parents:[head]});
 await api("PATCH","/repos/"+REPO+"/git/refs/heads/main",{sha:newCommit.sha,force:false});
 console.log("已提交 "+newCommit.sha.slice(0,10));
 console.log("https://github.com/"+REPO+"/commit/"+newCommit.sha);
})().catch(e=>{console.error("FAIL:",e.message);process.exit(1)});
