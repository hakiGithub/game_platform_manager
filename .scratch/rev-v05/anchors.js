const fs=require('fs');
let html=fs.readFileSync('index.html','utf8');
const code=html.match(/<script>([\s\S]*)<\/script>/)[1].replace(/\nrender\(\);\s*$/,'\n');
const doc={getElementById:function(){return {set innerHTML(v){}};},querySelectorAll:function(){return [];}};
const api=new Function('document','window',code+'\nreturn {SC, screen};')(doc,{scrollTo(){}});
const SC=api.SC;
const BANDM='部署扩展';
const isBand=l=>!l.m;
const CLOSE_OK='部署扩展阶段收尾 · 容器已恢复到运行态 · 成功';
const CLOSE_FAIL='部署扩展阶段收尾 · 容器未能恢复到运行态 · 失败';
const DONE='部署扩展阶段完成';
const HANDOFF='部署扩展阶段结束，进入健康检查与启动';
const TERM=['致命步骤失败，部署终止','实例停止失败','部署终止：本次所选版本','部署终止：实例配置要求的版本'];
const STEPTERM=l=>/^步骤 \d+\/\d+ .* · (成功|失败)/.test(l.m)||/失败（非致命）/.test(l.m);
let vA=[],vB=[],vC=[],vD=[],v1=[],orphan=[];
for(const s of SC){
  if(!s.lines) continue;
  const ls=s.lines;
  const ms=ls.map(l=>l.m);
  // anchor1
  ms.forEach((m,i)=>{ if(m==='进入部署扩展阶段'){ if(!(i>0&&isBand(ls[i-1]))) v1.push(s.id+'@'+i); } });
  // anchor2 A
  ms.forEach((m,i)=>{ if(m&&m.startsWith(DONE)){ if(!(i>=2 && ms[i-1]&&ms[i-1].startsWith(CLOSE_OK) && ms[i-2]&&STEPTERM({m:ms[i-2]}))) vA.push(s.id+'@'+i); } });
  // anchor2 B
  ms.forEach((m,i)=>{ if(m&&m.startsWith(CLOSE_FAIL)){ const after=ms.slice(i+1).filter(Boolean); if(after.length) vB.push(s.id+'@'+i+':'+after.join('|')); } });
  // anchor2 C
  const hasTerm=ms.some(m=>m&&TERM.some(t=>m.startsWith(t)));
  const hasClose=ms.some(m=>m&&(m.startsWith(CLOSE_OK)||m.startsWith(CLOSE_FAIL)));
  if(hasTerm&&hasClose) vC.push(s.id);
  // anchor2 D
  const hasBand=ms.some((m,i)=>isBand(ls[i]));
  if(hasBand&&ms.some(m=>m&&m.startsWith(DONE))){
    const n=ms.filter(m=>m&&m.startsWith(CLOSE_OK)).length;
    if(n!==1) vD.push(s.id+':'+n);
  }
  // orphan: close-success without DONE row
  if(ms.some(m=>m&&m.startsWith(CLOSE_OK)) && !ms.some(m=>m&&m.startsWith(DONE))) orphan.push(s.id);
}
console.log('A violations',JSON.stringify(vA));
console.log('B violations',JSON.stringify(vB));
console.log('C violations',JSON.stringify(vC));
console.log('D violations',JSON.stringify(vD));
console.log('anchor1 violations',JSON.stringify(v1));
console.log('orphan close-success without done',JSON.stringify(orphan));
// positive inventory
for(const s of SC){ if(!s.lines) continue; const ms=s.lines.map(l=>l.m).filter(Boolean);
  const cl=ms.filter(m=>m.startsWith(CLOSE_OK)||m.startsWith(CLOSE_FAIL));
  const dn=ms.filter(m=>m.startsWith(DONE));
  if(cl.length||dn.length) console.log(s.id,'close='+cl.length,'done='+dn.length, JSON.stringify(cl.map(x=>x.slice(0,20))));
}
