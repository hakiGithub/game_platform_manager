const fs=require('fs');
let html=fs.readFileSync('index.html','utf8');
const code=html.match(/<script>([\s\S]*)<\/script>/)[1].replace(/\nrender\(\);\s*$/,'\n');
const store={};
const doc={getElementById:(id)=>({set innerHTML(v){store[id]=v;},get innerHTML(){return store[id]||'';}}),querySelectorAll:()=>[]};
const api=new Function('document','window',code+'\nreturn {SC, screen};')(doc,{scrollTo(){}});
const SC=api.SC;
const RE=/<div class="sr-only"[^>]*>((?:<div>[^<]*<\/div>)*)<\/div>/;
const rows=[];
for(const s of SC){
  const scr=api.screen(s);
  const m=scr.match(RE);
  const body=m?m[1]:null;
  const nodes=body?(body.match(/<div>/g)||[]).length:null;
  const texts=body?(body.match(/<div>([^<]*)<\/div>/g)||[]).map(x=>x.slice(5,-6)):[];
  rows.push({id:s.id,screen:s.screen,ok:!!m,nodes,texts,
    at:(scr.match(/aria-atomic="false"/g)||[]).length,
    role:(scr.match(/role="status"/g)||[]).length,
    live:(scr.match(/aria-live="polite"/g)||[]).length,
    nonBand:(s.lines||[]).filter(l=>l.m).length});
}
console.log('id  r/l/a  nodes/nonBand');
rows.filter(r=>r.screen==='log').forEach(r=>console.log(r.id.padEnd(4),r.role+'/'+r.live+'/'+r.at,' ',r.nodes+'/'+r.nonBand));
console.log('\nmissing match:',JSON.stringify(rows.filter(r=>r.screen==='log'&&!r.ok).map(r=>r.id)));
console.log('attr violations:',JSON.stringify(rows.filter(r=>r.screen==='log'&&(r.at!==1||r.role!==1||r.live!==1)).map(r=>r.id)));
console.log('\nLK queue:',JSON.stringify(rows.find(r=>r.id==='lk').texts));
console.log('LI queue:',JSON.stringify(rows.find(r=>r.id==='l8').texts));
console.log('LJ queue:',JSON.stringify(rows.find(r=>r.id==='l9').texts));
