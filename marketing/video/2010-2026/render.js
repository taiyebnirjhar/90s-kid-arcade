const {chromium}=require('playwright');
const [,, from, to, out] = process.argv;
(async()=>{
  const b=await chromium.launch();
  const p=await b.newPage({viewport:{width:1080,height:1920}});
  await p.goto('file://'+process.cwd()+'/compose.html');
  await p.waitForTimeout(2500); await p.evaluate(()=>document.fonts.ready);
  require('fs').mkdirSync(out,{recursive:true});
  for(let f=+from; f<+to; f++){
    await p.evaluate(f=>window.render(f), f);
    await p.screenshot({path:`${out}/${String(f).padStart(5,'0')}.jpg`, type:'jpeg', quality:92});
  }
  await b.close();
})();
