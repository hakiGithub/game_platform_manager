const fs=require('fs');
let html=fs.readFileSync('index.html','utf8');
const m=html.match(/<script>([\s\S]*)<\/script>/);
let code=m[1];
// stub DOM
const store={};
global.document={
  getElementById:(id)=>({set innerHTML(v){store[id]=v;}, get innerHTML(){return store[id]||'';}}),
  querySelectorAll:()=>[]
};
global.window={scrollTo:()=>{}};
code=code.replace(/\nrender\(\);\s*$/,'\n');
const sandbox={};
const fn=new Function('document','window',code+'\nreturn {SC:SC, screen:screen, logDialog:logDialog, versionBlock:versionBlock, stateCard:stateCard, annot:annot};');
const api=fn(global.document,global.window);
const SC=api.SC;
console.log('SCREEN COUNT', SC.length);
console.log('IDS', SC.map(s=>s.id).join(' '));
const tagList=['div','span','li','ul','ol','table','tbody','tr','th','td','button','small','dl','dt','dd'];
let leaks=[],unpaired=[],srInfo=[],bandViol=[],closeInfo=[];
for(const s of SC){
  let full;
  try{ full=api.stateCard(s)+api.screen(s)+api.annot(s); }catch(e){ console.log('RENDER ERR',s.id,e.message); continue; }
  const scr=api.screen(s);
  ['undefined','NaN','[object Object]','null'].forEach(p=>{ if(scr.indexOf(p)>=0) leaks.push(s.id+':'+p); });
  for(const t of tagList){
    const o=(scr.match(new RegExp('<'+t+'[\s>]','g'))||[]).length;
    const c=(scr.match(new RegExp('</'+t+'>','g'))||[]).length;
    if(o!==c) unpaired.push(s.id+' '+t+' '+o+'/'+c);
  }
  // sr-only
  const sr=scr.match(/<div class="sr-only"[^>]*>([\s\S]*?)<\/div><\/div>/);
  if(s.screen==='log'){
    const at=(scr.match(/aria-atomic="false"/g)||[]).length;
    const role=(scr.match(/role="status"/g)||[]).length;
    const live=(scr.match(/aria-live="polite"/g)||[]).length;
    // count nodes inside sr-only: extract content between > and </div></div>
    let nodes=0,texts=[];
    const sm=scr.match(/<div class="sr-only"[^>]*>([\s\S]*?)<\/div><\/div>/);
    if(sm){ const parts=sm[1].match(/<div>[\s\S]*?<\/div>/g)||[]; nodes=parts.length; texts=parts.map(p=>p.replace(/<\/?div>/g,'')); }
    srInfo.push({id:s.id,at,role,live,nodes,texts});
  }
}
console.log('LEAKS',JSON.stringify(leaks));
console.log('UNPAIRED',JSON.stringify(unpaired));
console.log('--- sr-only per log screen ---');
srInfo.forEach(x=>console.log(x.id,'at='+x.at,'role='+x.role,'live='+x.live,'nodes='+x.nodes, JSON.stringify(x.texts)));
