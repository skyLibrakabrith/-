const https=require("https"),fs=require("fs"),z=require("zlib");
const T=process.env.GH_TOKEN, RUN=process.argv[2];
function get(url){return new Promise((res,rej)=>{https.request(url,{method:"GET",headers:{"User-Agent":"d",Accept:"application/vnd.github+json",Authorization:"Bearer "+T},timeout:60000},r=>{
 if(r.statusCode>=300&&r.statusCode<400&&r.headers.location){res(get(r.headers.location));return}
 const c=[];r.on("data",x=>c.push(x));r.on("end",()=>res(Buffer.concat(c)))}).on("error",rej).end()})}
(async()=>{
 const buf=await get("https://api.github.com/repos/skyLibrakabrith/-/actions/runs/"+RUN+"/logs");
 const eocd=buf.lastIndexOf(Buffer.from([0x50,0x4b,0x05,0x06]));
 const count=buf.readUInt16LE(eocd+10), cdOff=buf.readUInt32LE(eocd+16);
 let p=cdOff, all="";
 for(let i=0;i<count;i++){
   if(buf.readUInt32LE(p)!==0x02014b50) break;
   const method=buf.readUInt16LE(p+10), csize=buf.readUInt32LE(p+20);
   const nlen=buf.readUInt16LE(p+28), elen=buf.readUInt16LE(p+30), clen=buf.readUInt16LE(p+32);
   const lho=buf.readUInt32LE(p+42);
   const name=buf.slice(p+46,p+46+nlen).toString();
   const lnlen=buf.readUInt16LE(lho+26), lelen=buf.readUInt16LE(lho+28);
   const d0=lho+30+lnlen+lelen;
   try{ const t=method===0?buf.slice(d0,d0+csize).toString():z.inflateRawSync(buf.slice(d0,d0+csize)).toString();
     if(/Build debug|build debug/i.test(name)) all+=t;
   }catch(e){}
   p+=46+nlen+elen+clen;
 }
 const lines=all.split("\n").filter(l=>/^\S*\s*e: file|error:|Unresolved|What went wrong|FAILED/.test(l));
 console.log(lines.slice(0,30).join("\n") || "(没匹配到错误行，原始长度 "+all.length+")");
 fs.writeFileSync("build_last.txt",all);
})();
