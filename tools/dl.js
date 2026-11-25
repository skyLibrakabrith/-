const https=require("https"),fs=require("fs"),z=require("zlib");
const T=process.env.GH_TOKEN, ID=process.argv[2];
function req(url,headers){return new Promise((res,rej)=>{
  const u=new URL(url);
  https.request({host:u.host,path:u.pathname+u.search,method:"GET",headers,timeout:180000},r=>{
    const c=[];r.on("data",x=>c.push(x));
    r.on("end",()=>res({code:r.statusCode,headers:r.headers,buf:Buffer.concat(c)}));
  }).on("error",rej).end();
})}
(async()=>{
 const r1=await req("https://api.github.com/repos/skyLibrakabrith/-/actions/artifacts/"+ID+"/zip",
   {"User-Agent":"dsh",Accept:"application/vnd.github+json",Authorization:"Bearer "+T});
 if(!r1.headers.location){ console.log("no redirect:",r1.code); return; }
 // Azure 签名 URL 不能带 Authorization
 const r2=await req(r1.headers.location,{"User-Agent":"dsh",Accept:"*/*"});
 console.log("artifact HTTP",r2.code,r2.buf.length);
 const buf=r2.buf;
 const eocd=buf.lastIndexOf(Buffer.from([0x50,0x4b,0x05,0x06]));
 const count=buf.readUInt16LE(eocd+10), cdOff=buf.readUInt32LE(eocd+16);
 let p=cdOff;
 for(let i=0;i<count;i++){
   if(buf.readUInt32LE(p)!==0x02014b50) break;
   const method=buf.readUInt16LE(p+10), csize=buf.readUInt32LE(p+20);
   const nlen=buf.readUInt16LE(p+28), elen=buf.readUInt16LE(p+30), clen=buf.readUInt16LE(p+32);
   const lho=buf.readUInt32LE(p+42);
   const name=buf.slice(p+46,p+46+nlen).toString();
   const lnlen=buf.readUInt16LE(lho+26), lelen=buf.readUInt16LE(lho+28);
   const d0=lho+30+lnlen+lelen;
   if(name.endsWith(".apk")){
     const out = method===0? buf.slice(d0,d0+csize) : z.inflateRawSync(buf.slice(d0,d0+csize));
     fs.writeFileSync("app-debug.apk", out);
     console.log("已解出", name, out.length, "bytes");
   }
   p+=46+nlen+elen+clen;
 }
})();
