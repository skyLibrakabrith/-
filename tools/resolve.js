const fs=require('fs'),path=require('path');
const root='vte/app/src/main/java';
const BASE='at.cwiesner.android.visualtimer.ext';
function walk(d,out=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);e.isDirectory()?walk(p,out):p.endsWith('.kt')&&out.push(p)}return out}
const files=walk(root);
// package -> set of top-level declaration names
const decls=new Map();
const filePkg=[];
const declRe=/^(?:@\w+\s*)?(?:(?:public|internal|private|abstract|open|sealed|data|enum|inline|suspend|annotation)\s+)*(fun|class|interface|object|val|var|typealias)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)/;
for(const f of files){
  const src=fs.readFileSync(f,'utf8');
  const lines=src.split('\n');
  let pkg='';
  for(const l of lines){ const m=l.match(/^package\s+([\w.]+)/); if(m){pkg=m[1];break} }
  filePkg.push([f,pkg]);
  if(!decls.has(pkg)) decls.set(pkg,new Set());
  const set=decls.get(pkg);
  let depth=0;
  for(const l of lines){
    if(depth===0){
      const m=l.match(declRe);
      if(m) set.add(m[2]);
    }
    depth += (l.match(/\{/g)||[]).length - (l.match(/\}/g)||[]).length;
    if(depth<0) depth=0;
  }
}
let bad=0;
for(const [f,pkg] of filePkg){
  const src=fs.readFileSync(f,'utf8');
  for(const l of src.split('\n')){
    const m=l.match(/^import\s+(at\.cwiesner\.android\.visualtimer\.ext\.[\w.]+)\s*$/);
    if(!m) continue;
    const full=m[1];
    // try longest package prefix that is a known package
    const segs=full.split('.');
    let ok=false, tried=null;
    for(let i=segs.length-1;i>=4;i--){
      const p=segs.slice(0,i).join('.');
      const name=segs[i];
      if(decls.has(p)){ tried=p; if(decls.get(p).has(name)) ok=true; break; }
    }
    if(tried && !ok){ bad++; console.log('BROKEN  '+f.replace(root+'/',''));console.log('        import '+full+'   (package '+tried+' has no "'+segs[segs.length-1]+'")') }
  }
}
console.log('\nbroken internal imports: '+bad);
