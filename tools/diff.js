const fs=require("fs"),path=require("path"),https=require("https"),crypto=require("crypto");
const TOKEN=process.env.GH_TOKEN, REPO="skyLibrakabrith/-", DIR="vte";
function api(method,p,body){const d=body?Buffer.from(JSON.stringify(body)):null;
 return new Promise((res,rej)=>{const h={"User-Agent":"d","Accept":"application/vnd.github+json",Authorization:"Bearer "+TOKEN,"X-GitHub-Api-Version":"2022-11-28"};
  if(d){h["Content-Type"]="application/json";h["Content-Length"]=d.length;}
  const q=https.request({host:"api.github.com",path:p,method,headers:h,timeout:60000},r=>{const c=[];r.on("data",x=>c.push(x));r.on("end",()=>{const t=Buffer.concat(c).toString();
   if(r.statusCode>=200&&r.statusCode<300){try{res(JSON.parse(t))}catch{res(t)}}else rej(new Error(method+" "+p+" -> "+r.statusCode+" "+t.slice(0,300)))})});
  q.on("error",rej);q.on("timeout",()=>q.destroy(new Error("timeout")));if(d)q.write(d);q.end();})}
function gitSha(buf){return crypto.createHash("sha1").update("blob "+buf.length+"\0").update(buf).digest("hex")}
function walk(dir,base=""){const out=[];for(const e of fs.readdirSync(dir,{withFileTypes:true})){if(e.name===".git")continue;
 const rel=base?base+"/"+e.name:e.name,full=path.join(dir,e.name);
 if(e.isDirectory())out.push(...walk(full,rel));else out.push({rel,full})}return out}
(async()=>{
 const ref=await api("GET","/repos/"+REPO+"/git/ref/heads/main");
 const head=ref.object.sha;
 const commit=await api("GET","/repos/"+REPO+"/git/commits/"+head);
 const tree=await api("GET","/repos/"+REPO+"/git/trees/"+commit.tree.sha+"?recursive=1");
 console.log("remote HEAD "+head.slice(0,8)+"  ("+(commit.message||"").split("\n")[0]+")");
 const remote=new Map(tree.tree.filter(e=>e.type==="blob").map(e=>[e.path,e.sha]));
 const local=walk(DIR).map(f=>({rel:f.rel,sha:gitSha(fs.readFileSync(f.full))}));
 const localMap=new Map(local.map(f=>[f.rel,f.sha]));
 const mod=[],add=[],del=[];
 for(const [rel,sha] of localMap){ if(!remote.has(rel)) add.push(rel); else if(remote.get(rel)!==sha) mod.push(rel); }
 for(const rel of remote.keys()) if(!localMap.has(rel)) del.push(rel);
 console.log("\n本地较远端改动:");
 console.log("  修改 "+mod.length+": "+mod.join(", "));
 console.log("  新增 "+add.length+": "+add.join(", "));
 console.log("  删除 "+del.length+": "+del.join(", "));
 console.log("  相同 "+(local.length-add.length-mod.length)+" / 本地共 "+local.length+" 文件");
})().catch(e=>{console.error("FAIL:",e.message);process.exit(1)});
