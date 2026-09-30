const fs=require('fs');
let html=fs.readFileSync('index.html','utf8');
const code=html.match(/<script>([\s\S]*)<\/script>/)[1].replace(/\nrender\(\);\s*$/,'\n');
const store={};
const doc={getElementById:(id)=>({set innerHTML(v){store[id]=v;},get innerHTML(){return store[id]||'';}}),querySelectorAll:()=>[]};
const api=new Function('document','window',code+'\nreturn {SC, screen, logDialog, versionBlock};')(doc,{scrollTo(){}});
const SC=api.SC;
function srExtract(scr){
  const start=scr.indexOf('<div class="sr-only"');
  if(start<0) return null;
  const open=scr.indexOf('>',start);
  // find matching close for the sr div
  let i=open+1, depth=1, out='';
  while(i<scr.length){
    const n=scr.indexOf('<div', i+1);
    const c=scr.indexOf('</div>', i+1);
    if(c<0) break;
    if(n>=0 && n<c){ depth++; i=n; }
    else { depth--; i=c; if(depth===0){ break; } out+=scr.slice(c, c+6); i=c+5; }
  }
  return scr.slice(open+1, i).trim();
}
const rows=[];
for(const s of SC){
  const scr=api.screen(s);
  const body=srExtract(scr)||'';
  const nodes=(body.match(/<div>/g)||[]).length;
  const texts=(body.match(/<div>(.*?)<\/div>/g)||[]).map(x=>x.slice(5,-6));
  const at=(scr.match(/aria-atomic="false"/g)||[]).length;
  const role=(scr.match(/role="status"/g)||[]).length;
  const live=(scr.match(/aria-live="polite"/g)||[]).length;
  const lines=(s.lines||[]).map(l=>l.m).filter(Boolean);
  rows.push({id:s.id,screen:s.screen,at,role,live,nodes,texts,nonBand:lines.length});
}
console.log('id | role/live/atomic | queueNodes/nonBandLines');
rows.filter(r=>r.screen==='log').forEach(r=>console.log(r.id.padEnd(3),r.role+r.live+r.at, r.nodes+'/'+r.nonBand, '|', r.texts.join(' || ')));
console.log('\nMISSING-ATTR screens:',JSON.stringify(rows.filter(r=>r.screen==='log'&&(r.at!==1||r.role!==1||r.live!==1)).map(r=>r.id)));
