const https=require("https");
const T=process.env.GH_TOKEN, ID=process.argv[2];
function get(){return new Promise((res,rej)=>{https.request({host:"api.github.com",path:"/repos/skyLibrakabrith/-/actions/runs/"+ID,headers:{"User-Agent":"d",Accept:"application/vnd.github+json",Authorization:"Bearer "+T},method:"GET"},r=>{const c=[];r.on("data",x=>c.push(x));r.on("end",()=>{const j=JSON.parse(Buffer.concat(c).toString());res(j)})}).on("error",rej).end()})}
(async()=>{
 for(let i=0;i<40;i++){
   const j=await get();
   console.log("["+i+"] "+j.status+"/"+(j.conclusion||"-"));
   if(j.status==="completed"){ process.exit(j.conclusion==="success"?0:1); }
   await new Promise(r=>setTimeout(r,20000));
 }
 console.log("超时未完成"); process.exit(2);
})();
