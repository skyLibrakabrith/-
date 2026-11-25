const fs=require('fs'),path=require('path');
const root='vte/app/src/main/java/at/cwiesner/android/visualtimer/ext';
function walk(d,out=[]){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);e.isDirectory()?walk(p,out):p.endsWith('.kt')&&out.push(p)}return out}
const skip=new Set(['getValue','setValue']);
let totalUnused=0;
for(const f of walk(root)){
  const src=fs.readFileSync(f,'utf8');
  const lines=src.split('\n');
  const body=lines.filter(l=>!l.trim().startsWith('import ')).join('\n');
  const unused=[];
  for(const l of lines){
    const m=l.match(/^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$/);
    if(!m) continue;
    const name=m[2]||m[1].split('.').pop();
    if(name==='*'||skip.has(name)) continue;
    if(!new RegExp('\\b'+name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\b').test(body)) unused.push(name);
  }
  if(unused.length){totalUnused+=unused.length; if(unused.length>1) console.log(unused.length+'  '+f.replace(root+'/','')+': '+unused.join(', '))}
}
console.log('\nREAL unused imports (excl. getValue/setValue): '+totalUnused);
