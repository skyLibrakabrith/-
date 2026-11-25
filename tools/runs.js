const https=require("https");
const TOKEN=process.env.GH_TOKEN,REPO="skyLibrakabrith/-";
function api(p){return new Promise((res,rej)=>{https.request({host:"api.github.com",path:p,method:"GET",timeout:30000,headers:{"User-Agent":"d",Accept:"application/vnd.github+json",Authorization:"Bearer "+TOKEN,"X-GitHub-Api-Version":"2022-11-28"}},r=>{const c=[];r.on("data",x=>c.push(x));r.on("end",()=>{const t=Buffer.concat(c).toString();if(r.statusCode>=200&&r.statusCode<300){try{res(JSON.parse(t))}catch{res(t)}}else rej(new Error(r.statusCode+" "+t.slice(0,200)))})}).on("error",rej).end()})}
(async()=>{
 const j=await api("/repos/"+REPO+"/actions/runs?per_page="+(process.argv[2]||3));
 for(const w of j.workflow_runs||[]){
   console.log("run "+w.id+"  "+w.status+"/"+(w.conclusion||"-")+"  sha="+String(w.head_sha).slice(0,8)+"  "+w.created_at+"\n   https://github.com/"+REPO+"/actions/runs/"+w.id);
 }
})().catch(e=>{console.error("FAIL:",e.message);process.exit(1)});
