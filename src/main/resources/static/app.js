'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const active = new Set(['QUEUED', 'RUNNING', 'REVIEWING']);
  const labels = {QUEUED:'排队中',RUNNING:'创作中',REVIEWING:'独立审稿中',READY:'待人工确认的交付稿',DEMO_READY:'演示完成',NEEDS_REVIEW:'需要人工复核',NEEDS_INPUT:'等待补充资料',PAUSED:'已暂停',CANCELLED:'已停止',FAILED:'执行失败'};
  const toolLabels = {save_plan:'建立创作计划',patch_script:'保存稿件修改',evaluate_candidate:'请求独立审稿',read_context:'读取原始资料',search_history:'检索历史片段',inspect_sections:'检查正文与段落关系',submit_review:'提交证据审稿报告',finish_run:'申请交付检查',MODEL:'模型决策',CHECKPOINT:'整理上下文'};
  const state = {config:null,offline:false,tasks:[],task:null,templates:[],documentTab:'draft',inspectorTab:'trace',poll:null,replay:null,replayIndex:0,replayTimer:null,loadEpoch:0,diffFrom:null,diffTo:null};
  const h = (tag, attrs, ...children) => {
    const node = document.createElement(tag);
    for (const [k,v] of Object.entries(attrs || {})) {
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) node.setAttribute(k, String(v));
    }
    for (const c of children.flat(Infinity)) if(c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return node;
  };
  const arr = value => Array.isArray(value) ? value : [];
  const num = value => Number(value) || 0;
  const fmt = value => new Intl.NumberFormat('zh-CN').format(num(value));
  const pretty = value => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  const jsonObject = value => {if(typeof value !== 'string') return value || {}; try{return JSON.parse(value);}catch{return {message:value};}};
  const shortDate = value => {if(!value)return '';const d=new Date(value);return Number.isNaN(d.getTime())?String(value):d.toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});};
  const uid = () => typeof crypto.randomUUID==='function'?crypto.randomUUID():`web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const token = () => {try{return sessionStorage.getItem('scriptforge-token')||'';}catch{return '';}};
  const headers = () => ({'Content-Type':'application/json',...(token()?{'Authorization':`Bearer ${token()}`}:{})});
  function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,4300);}
  async function api(path, options = {}) {
    const response = await fetch(`/api${path}`,{...options,headers:{...headers(),...options.headers},signal:options.signal||AbortSignal.timeout(12000)});
    const content = await response.text();
    let data;try{data=content?JSON.parse(content):null;}catch{throw new Error('服务器响应不是有效 JSON。请检查后端连接。');}
    if(!response.ok){const error=new Error(data?.message||data?.error||`请求失败（${response.status}）`);error.status=response.status;throw error;}
    return data;
  }
  function showDialog(id){if(!$(id).open)$(id).showModal();}
  function inlineEmpty(message){return h('div',{class:'inline-empty'},h('span',{class:'empty-icon'},'◇'),message);}
  function detail(title, value){const d=h('details',{class:'trace-detail'},h('summary',{},title),h('pre',{},pretty(value)));return d;}
  function setMeter(id,value,max){$(id).style.width=`${Math.min(100,Math.max(0,num(value)/Math.max(1,num(max))*100))}%`;}
  function replace(id,...nodes){$(id).replaceChildren(...nodes.flat());}
  function statusClass(status){return ['FAILED','CANCELLED'].includes(status)?'danger':['PAUSED','NEEDS_REVIEW','NEEDS_INPUT'].includes(status)?'warning':'';}
  function sidebar(){
    $('task-count').textContent=state.tasks.length;
    replace('task-list',state.tasks.map((task,i)=>h('button',{class:`task-card ${state.task?.id===task.id?'active':''}`,onclick:()=>selectTask(task.id)},h('span',{class:'task-card-index'},String(i+1).padStart(2,'0')),h('span',{class:'task-card-name'},task.title||'未命名任务'),h('small',{},`${labels[task.status]||task.status} · ${task.mode==='DEMO'?'固定样例': '模型任务'}`))));
  }
  function render(){
    sidebar();const task=state.task;$('empty-state').hidden=!!task;$('task-workspace').hidden=!task;if(!task)return;
    $('task-title').textContent=task.title;$('task-status').textContent=labels[task.status]||task.status;$('task-status').className=`status-label ${statusClass(task.status)}`;
    $('task-time').textContent=`目标 ${Math.round(num(task.targetSeconds))} 秒 · 非合成实测`;$('task-language').textContent=task.language==='zh-CN'?'简体中文':task.language;
    $('stat-version').textContent=`v${num(task.draftRevision)}`;$('stat-reviews').textContent=String(num(task.reviewRound)).padStart(2,'0');
    replace('stat-calls',String(num(task.modelCalls)),h('small',{},` / ${num(task.maxModelCalls)}`));
    replace('stat-tokens',fmt(task.usedTokens),h('small',{},` / ${num(task.maxTokens)>=1000?`${Math.round(num(task.maxTokens)/1000)}k`:fmt(task.maxTokens)}`));
    $('token-kind').textContent=task.mode==='DEMO'?'样例估算':'记录值';setMeter('calls-meter',task.modelCalls,task.maxModelCalls);setMeter('tokens-meter',task.usedTokens,task.maxTokens);
    $('cancel-button').hidden=state.offline||state.config?.readOnly||!active.has(task.status);
    $('resume-button').hidden=state.offline||state.config?.readOnly||task.status!=='PAUSED';
    $('export-button').disabled=!arr(task.sections).length;$('task-error').hidden=!task.error;$('task-error').textContent=typeof task.error==='string'?task.error:pretty(task.error);
    const last=arr(task.versions).find(v=>num(v.revision)===num(task.draftRevision));$('draft-hash').textContent=last?.hash?`${String(last.hash).slice(0,12)}`:'';
    $('draft-footnote').textContent=task.mode==='DEMO'?'DEMO 固定样例 · 仅展示工程流程，不代表模型能力':'正文版本已保存 · 质量结果仍需结合人工复核';
    renderDocument();renderInspector();
  }
  function planFor(id){return arr(state.task?.plan).find(p=>(p.id||p.sectionId)===id)||{};}
  function renderDocument(){
    const task=state.task;if(!task)return;
    document.querySelectorAll('[data-document-tab]').forEach(b=>{b.classList.toggle('active',b.dataset.documentTab===state.documentTab);b.setAttribute('aria-selected',b.dataset.documentTab===state.documentTab);});
    const content=$('document-content');const scroll=content.scrollTop;content.replaceChildren();
    if(state.documentTab==='draft'){
      const sections=arr(task.sections);if(!sections.length){content.append(inlineEmpty('Writer 正在构思。稿件保存后会出现在这里。'));return;}
      content.append(h('div',{class:'document-subtitle'},h('span',{},`CANDIDATE / v${task.draftRevision}`),h('span',{},`${sections.length} 个段落 · ${task.mode==='DEMO'?'演示稿件':'候选稿件'}`)));
      sections.forEach((s,i)=>{const p=planFor(s.sectionId);content.append(h('article',{class:'script-section'},h('h4',{},h('span',{class:'number'},String(i+1).padStart(2,'0')),p.title||s.sectionId,h('span',{class:'duration'},p.targetSeconds?`计划 ${p.targetSeconds}s`:'')),h('div',{class:'script-text'},s.text),h('div',{class:'fact-tags'},arr(s.factIds).map(f=>h('span',{class:'tag'},`事实 ${f}`)))));});
    }else if(state.documentTab==='plan'){
      if(!arr(task.plan).length)content.append(inlineEmpty('还没有保存创作计划。'));
      arr(task.plan).forEach((p,i)=>content.append(h('article',{class:'plan-card'},h('h4',{},`${String(i+1).padStart(2,'0')} / ${p.title||p.id}`),h('p',{},p.purpose),h('dl',{},h('dt',{},'切入角度'),h('dd',{},p.angle||'—'),h('dt',{},'承接内容'),h('dd',{},p.entryContext||'—'),h('dt',{},'信息增量'),h('dd',{},p.newInformation||'—'),h('dt',{},'后续衔接'),h('dd',{},p.exitExpectation||'—'),h('dt',{},'依赖段落'),h('dd',{},arr(p.dependsOn).join('、')||'无显式依赖')),h('div',{class:'fact-tags'},arr(p.factIds).map(f=>h('span',{class:'tag'},f)),h('span',{class:'tag'},`${p.targetSeconds||0}s`)))));
    }else if(state.documentTab==='brief'){
      content.append(h('div',{class:'brief-block'},h('h4',{},'创作目标'),h('p',{},task.brief),h('h4',{},'原始事实 · 创建时冻结'),h('ul',{},arr(task.facts).map(f=>h('li',{},`${f.id} / ${f.text}`,h('small',{},f.source||'未注明来源')))),h('h4',{},'保留约束'),h('ul',{},arr(task.constraints).map(c=>h('li',{},c)))));
    }else renderDiff(content);
    content.scrollTop=scroll;
  }
  function renderDiff(content){
    const versions=arr(state.task.versions).filter(v=>Array.isArray(v.sections)).sort((a,b)=>num(a.revision)-num(b.revision));
    if(versions.length<2){content.append(inlineEmpty('保存两个稿件版本后，即可查看修改。'));return;}
    const from=versions.find(v=>String(v.revision)===String(state.diffFrom))||versions[versions.length-2];
    const to=versions.find(v=>String(v.revision)===String(state.diffTo))||versions[versions.length-1];
    const select=(value,key)=>h('select',{'aria-label':key==='diffFrom'?'对比起始版本':'对比目标版本',onchange:e=>{state[key]=e.target.value;renderDocument();}},versions.map(v=>h('option',{value:v.revision,selected:v.revision===value},`v${v.revision}`)));
    content.append(h('div',{class:'diff-selectors'},'比较',select(from.revision,'diffFrom'),h('span',{},'→'),select(to.revision,'diffTo')));
    const ids=[...new Set([...from.sections,...to.sections].map(s=>s.sectionId))];let changed=0;
    for(const id of ids){const a=from.sections.find(s=>s.sectionId===id)?.text||'',b=to.sections.find(s=>s.sectionId===id)?.text||'';if(a===b)continue;changed++;
      content.append(h('article',{class:'diff-entry'},h('h4',{},planFor(id).title||id),a?h('div',{class:'diff-text removed'},`− ${a}`):null,b?h('div',{class:'diff-text added'},`＋ ${b}`):null));}
    if(!changed)content.append(inlineEmpty('这两个版本的正文一致。'));
  }
  function renderInspector(){
    const task=state.task;if(!task)return;document.querySelectorAll('[data-inspector-tab]').forEach(b=>{b.classList.toggle('active',b.dataset.inspectorTab===state.inspectorTab);b.setAttribute('aria-selected',b.dataset.inspectorTab===state.inspectorTab);});
    const content=$('inspector-content'),scroll=content.scrollTop;content.replaceChildren();
    if(state.inspectorTab==='trace'){
      content.append(h('div',{class:'trace-legend'},h('span',{},h('i',{class:'agent-dot'}),'Writer'),h('span',{},h('i',{class:'agent-dot reviewer'}),'Reviewer'),h('span',{},h('i',{class:'agent-dot host'}),'Java 宿主')));
      const steps=arr(task.steps);if(!steps.length){content.append(inlineEmpty('等待第一步执行…'));return;}
      const list=h('div',{class:'trace-list'});steps.forEach((step,i)=>{
        const role=String(step.role||'HOST').toUpperCase(),cls=role==='REVIEWER'?'reviewer':role==='WRITER'?'':'host';const output=jsonObject(step.output),input=jsonObject(step.input);
        const title=step.label||toolLabels[step.name]||toolLabels[step.kind]||step.name||step.kind;
        const result=output.data||output;const description=step.description||result.summary||result.message||result.reason||input.reason||(step.kind==='MODEL'?'请求模型选择下一步行动；执行结果由宿主保存。':step.name==='patch_script'?`按预期版本保存修改，未修改段落保留。`:step.name==='evaluate_candidate'?'固定稿件快照，Writer 等待独立审稿结果。':step.name==='finish_run'?'宿主核对版本、报告、覆盖与任务状态。':`${role} · ${step.name||step.kind}`);
        list.append(h('article',{class:'trace-item'},h('span',{class:`trace-marker ${cls}`},role==='REVIEWER'?'R':role==='WRITER'?'W':'H'),h('div',{},h('div',{class:'trace-top'},h('span',{class:'trace-name'},title),h('span',{class:'trace-time'},String(i+1).padStart(2,'0'),h('span',{class:'step-status'},step.status==='SUCCEEDED'?'✓':['ERROR','FAILED','UNKNOWN'].includes(step.status)?'!':step.status==='WAITING'?'等待':step.status==='PREPARED'?'待执行':''))),h('div',{class:'trace-description'},description),detail(`${step.name||step.kind} · 查看输入与结果`,{executionId:step.executionId,status:step.status,input,output}))));
      });content.append(list);
    }else if(state.inspectorTab==='review')renderReview(content);else renderContext(content);
    content.scrollTop=scroll;
  }
  function requiredEdgeCount(){const plan=arr(state.task?.plan),edges=new Set();plan.forEach((p,i)=>{if(i>0)edges.add(`${plan[i-1].id}->${p.id}`);arr(p.dependsOn).forEach(d=>edges.add(`${d}->${p.id}`));});return edges.size;}
  function renderReview(content){
    const task=state.task,report=task.report;if(!report){content.append(inlineEmpty('Reviewer 提交报告后，会展示检查范围与具体证据。'));return;}
    const verdict=report.verdict||report.overallStatus,stale=num(report.draftRevision)!==num(task.draftRevision),pass=verdict==='PASS'&&!stale;
    content.append(h('div',{class:`report-summary ${pass?'':'warning'}`},h('strong',{},stale?`v${report.draftRevision} · 旧版报告，不可用于当前稿`:pass?`v${report.draftRevision||task.draftRevision} · 本轮检查通过`:`v${report.draftRevision||task.draftRevision} · ${verdict==='NEEDS_REVIEW'?'需要人工复核':'有待修订的问题'}`),h('p',{},report.summary||'查看下方检查记录。')));
    const coverage=report.coverage||task.coverage||{},total=arr(task.sections).length,sections=arr(coverage.inspectedSections||coverage.sections),edges=arr(coverage.inspectedEdges||coverage.edges);
    content.append(h('div',{class:'coverage'},h('div',{class:'coverage-item'},h('span',{},'已读段落'),h('strong',{},`${sections.length} / ${total}`)),h('div',{class:'coverage-item'},h('span',{},'已检查关系'),h('strong',{},`${edges.length} / ${num(coverage.requiredEdges)||requiredEdgeCount()}`))));
    const issues=arr(report.issues);if(!issues.length)content.append(h('p',{class:'context-intro'},'本轮没有提交阻断问题。该结论不代表语义零错误；仍建议人工试听与复核。'));
    issues.forEach(issue=>content.append(h('article',{class:'issue'},h('h4',{},h('span',{class:'severity'},issue.severity==='BLOCKER'?'需修订':'建议'),`${issue.sectionId||'全稿'} · ${issue.type||'内容问题'}`),issue.quote?h('blockquote',{},issue.quote):null,h('p',{},h('b',{},'依据 / '),issue.evidence||''),h('p',{},h('b',{},'修改目标 / '),issue.repair||issue.message||''))));
    content.append(detail('查看版本、覆盖和原始报告',report));
  }
  function renderContext(content){
    content.append(h('p',{class:'context-intro'},'每轮按角色、当前版本与待办动态组装。原始事实、未解决问题和相邻关系保留；旧往返可被整理，完整原文继续持久化。'));
    const checkpoints=arr(state.task.checkpoints);if(!checkpoints.length){content.append(inlineEmpty('运行后会出现上下文快照和预算记录。'));return;}
    const list=h('div',{class:'context-list'});checkpoints.slice().reverse().slice(0,20).forEach((c,i)=>{const body=jsonObject(c.state||c.checkpoint);list.append(h('article',{class:'context-card'},h('header',{},h('span',{},`${c.role||body.role||body.scope?.role||'Agent'} · ${c.compacted?'已整理历史':'按需组装'}`),h('span',{},`≈ ${fmt(c.estimatedTokens)} tokens`)),h('p',{},`检查点 ${c.id||checkpoints.length-i} · ${shortDate(c.createdAt)}`),detail('查看上下文摘要与来源',body)));});content.append(list);
  }
  async function refreshTasks(){state.tasks=arr(await api('/tasks'));sidebar();}
  function stopPoll(){clearTimeout(state.poll);state.poll=null;}
  async function selectTask(id){
    stopPoll();state.diffFrom=null;state.diffTo=null;
    if(state.offline){applyFrame(state.replayIndex);return;}
    const epoch=++state.loadEpoch;
    try{const task=await api(`/tasks/${encodeURIComponent(id)}`);if(epoch!==state.loadEpoch)return;state.task=task;render();schedulePoll();}catch(e){handleError(e);}
  }
  function schedulePoll(){stopPoll();if(state.task&&active.has(state.task.status)&&!state.offline)state.poll=setTimeout(poll,1300);}
  async function poll(){const id=state.task?.id,epoch=state.loadEpoch;if(!id)return;
    try{const task=await api(`/tasks/${encodeURIComponent(id)}`);if(epoch!==state.loadEpoch)return;state.task=task;const idx=state.tasks.findIndex(t=>t.id===id);if(idx>=0)state.tasks[idx]=task;render();schedulePoll();}catch(e){handleError(e);state.poll=setTimeout(poll,5000);}
  }
  function handleError(error){if(error.status===401||error.status===403){toast('需要工作区访问令牌，请在连接设置中填写。');if(!$('settings-dialog').open)showDialog('settings-dialog');}else toast(error.message||'操作失败，请重试。');}
  async function init(){
    stopPoll();$('mode-notice').hidden=true;
    if(new URLSearchParams(location.search).get('demo')==='1'||location.protocol==='file:'){await initOffline('离线演示 · 固定样例，不调用模型。点击「播放流程」观察写稿、查证和修订。');return;}
    try{
      state.config=await api('/config',{signal:AbortSignal.timeout(5000)});state.offline=false;$('replay-controls').hidden=true;
      $('connection-state').textContent='本地后端已连接';$('mode-pill').textContent=state.config.mode==='MODEL'?'MODEL · 真实模型':'DEMO · 确定性替身';
      if(state.config.mode!=='MODEL'){$('mode-notice').textContent='本地 DEMO · 确定性模型替身运行真实执行引擎，演示数据与报告不代表模型效果。';$('mode-notice').hidden=false;}
      else{$('mode-notice').textContent='MODEL 模式 · 将请求服务端配置的模型并产生用量。交付稿建议人工复核。';$('mode-notice').hidden=false;}
      await refreshTasks();await loadTemplates();if(state.tasks.length)await selectTask(state.tasks[0].id);else{state.task=null;render();}
    }catch(e){
      if(e.status===401||e.status===403){handleError(e);return;}
      if(state.config){toast(e.message);return;}
      await initOffline('当前未连接后端，展示离线固定样例；这不是实时运行结果。运行本地服务后刷新即可连接。');
    }
  }
  async function initOffline(message){
    state.offline=true;state.config={mode:'DEMO',readOnly:true};$('connection-state').textContent='离线样例 · 只读';$('mode-pill').textContent='DEMO · 固定样例';$('mode-notice').textContent=message;$('mode-notice').hidden=false;$('replay-controls').hidden=false;
    try{const response=await fetch('./demo.json');if(!response.ok)throw new Error('无法读取演示数据');state.replay=await response.json();state.templates=state.replay.templates||[];state.replayIndex=state.replay.frames.length-1;applyFrame(state.replayIndex);}catch(e){replace('empty-state',h('h2',{},'演示数据暂时无法读取'),h('p',{},'请通过 HTTP 静态服务打开页面，不要直接双击 HTML 文件。'));$('empty-state').hidden=false;toast(e.message);}
  }
  function applyFrame(index){
    if(!state.replay)return;state.replayIndex=Math.max(0,Math.min(index,state.replay.frames.length-1));const frame=state.replay.frames[state.replayIndex];state.task=structuredClone(frame.task);state.tasks=[state.task];$('replay-caption').textContent=`${state.replayIndex+1} / ${state.replay.frames.length} · ${frame.caption}`;$('replay-next').disabled=state.replayIndex>=state.replay.frames.length-1;render();
  }
  function pauseReplay(){clearInterval(state.replayTimer);state.replayTimer=null;$('replay-play').textContent='▶ 播放流程';}
  function toggleReplay(){if(state.replayTimer){pauseReplay();return;}if(state.replayIndex>=state.replay.frames.length-1)applyFrame(0);$('replay-play').textContent='Ⅱ 暂停';state.replayTimer=setInterval(()=>{if(state.replayIndex>=state.replay.frames.length-1){pauseReplay();return;}applyFrame(state.replayIndex+1);},1800);}
  async function loadTemplates(){try{state.templates=arr(await api('/templates'));}catch(e){if(e.status===401||e.status===403)throw e;state.templates=[];}}
  function templateOptions(){replace('template-select',h('option',{value:''},'使用默认创作规则'),state.templates.map(t=>h('option',{value:t.id},`${t.name} · v${t.version}`)));}
  function openCreate(){
    templateOptions();$('task-form').dataset.requestId=uid();const demo=state.config?.mode==='DEMO';$('create-mode-note').textContent=state.offline?'当前为只读离线展示。克隆项目并启动本地服务后，可创建任务、接入模型。':demo?'DEMO 只演示预设的虚构收纳盒场景；自由创作请切换服务端 MODEL 模式。':'资料将发送至服务端配置的模型。请提供明确、可引用的事实。';$('create-submit').disabled=state.offline||state.config?.readOnly;$('create-error').hidden=true;showDialog('task-dialog');
  }
  async function submitTask(event){event.preventDefault();const form=event.currentTarget,data=new FormData(form),button=$('create-submit');button.disabled=true;$('create-error').hidden=true;
    const facts=String(data.get('facts')).split('\n').map(x=>x.trim()).filter(Boolean).map((line,i)=>{const parts=line.split('|');return {id:`f${i+1}`,text:parts[0].trim(),source:parts.slice(1).join('|').trim()||'运营输入'};});
    const body={requestId:form.dataset.requestId||uid(),title:String(data.get('title')),brief:String(data.get('brief')),language:String(data.get('language')),targetSeconds:Number(data.get('targetSeconds')),facts,constraints:String(data.get('constraints')).split('\n').map(x=>x.trim()).filter(Boolean),maxModelCalls:Number(data.get('maxModelCalls')),maxTokens:Number(data.get('maxTokens'))};if(data.get('templateId'))body.templateId=data.get('templateId');
    try{const task=await api('/tasks',{method:'POST',body:JSON.stringify(body)});$('task-dialog').close();await refreshTasks();state.task=task;state.diffFrom=null;state.diffTo=null;render();schedulePoll();toast('任务已创建，开始执行。');}catch(e){$('create-error').textContent=e.message;$('create-error').hidden=false;if(e.status===401||e.status===403)handleError(e);}finally{button.disabled=false;}
  }
  async function command(name){const id=state.task?.id;if(!id)return;const button=$(name==='cancel'?'cancel-button':'resume-button');button.disabled=true;try{state.task=await api(`/tasks/${encodeURIComponent(id)}/${name}`,{method:'POST',body:JSON.stringify({requestId:uid()})});render();schedulePoll();toast(name==='cancel'?'任务已停止，已保存产物仍可查看。':'已请求恢复执行。');}catch(e){handleError(e);}finally{button.disabled=false;}}
  function download(text,name){const url=URL.createObjectURL(new Blob([text],{type:'text/markdown;charset=utf-8'}));const a=h('a',{href:url,download:name});document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  async function exportDraft(){const task=state.task;if(!task)return;try{let text;if(state.offline){text=`# ${task.title}\n\n> 离线固定演示样例，不是实时 AI 生成或质量评测结果。\n> 状态：${labels[task.status]} · v${task.draftRevision}\n\n`+arr(task.sections).map(s=>`## ${planFor(s.sectionId).title||s.sectionId}\n\n${s.text}\n`).join('\n');}else{const response=await fetch(`/api/tasks/${encodeURIComponent(task.id)}/export`,{headers:headers()});if(!response.ok)throw new Error('导出失败，请检查任务与访问权限。');text=await response.text();}download(text,`scriptforge-v${task.draftRevision}.md`);}catch(e){handleError(e);}}
  function openTemplates(){replace('template-list',state.templates.length?state.templates.map(t=>h('article',{class:'template-item'},h('h4',{},t.name,h('span',{class:'template-version'},`v${t.version}`)),h('p',{},t.instructions))):inlineEmpty('尚未创建模板。'));$('template-form').hidden=state.offline||state.config?.readOnly;$('template-error').hidden=true;showDialog('templates-dialog');}
  $('new-task').addEventListener('click',openCreate);$('empty-create').addEventListener('click',openCreate);$('task-form').addEventListener('submit',submitTask);$('task-form').addEventListener('input',()=>{$('task-form').dataset.requestId=uid();});
  $('cancel-button').addEventListener('click',()=>command('cancel'));$('resume-button').addEventListener('click',()=>command('resume'));$('export-button').addEventListener('click',exportDraft);
  $('replay-play').addEventListener('click',toggleReplay);$('replay-next').addEventListener('click',()=>{pauseReplay();applyFrame(state.replayIndex+1);});$('replay-reset').addEventListener('click',()=>{pauseReplay();applyFrame(0);});
  document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.close).close()));
  document.querySelectorAll('[data-document-tab]').forEach(b=>b.addEventListener('click',()=>{state.documentTab=b.dataset.documentTab;renderDocument();}));
  document.querySelectorAll('[data-inspector-tab]').forEach(b=>b.addEventListener('click',()=>{state.inspectorTab=b.dataset.inspectorTab;renderInspector();}));
  $('about-button').addEventListener('click',()=>showDialog('about-dialog'));$('mobile-about').addEventListener('click',()=>showDialog('about-dialog'));$('mobile-templates').addEventListener('click',openTemplates);$('templates-button').addEventListener('click',openTemplates);$('settings-button').addEventListener('click',()=>{document.querySelector('#settings-form [name=token]').value=token();showDialog('settings-dialog');});
  $('settings-form').addEventListener('submit',async e=>{e.preventDefault();const value=new FormData(e.currentTarget).get('token');try{sessionStorage.setItem('scriptforge-token',value);}catch{toast('浏览器禁用了会话存储，无法保存访问令牌。');return;}$('settings-dialog').close();state.config=null;await init();});
  $('template-form').addEventListener('submit',async e=>{e.preventDefault();const data=new FormData(e.currentTarget);const button=e.currentTarget.querySelector('button');button.disabled=true;try{await api('/templates',{method:'POST',body:JSON.stringify({name:data.get('name'),instructions:data.get('instructions')})});await loadTemplates();openTemplates();toast('模板新版本已保存。');}catch(error){$('template-error').textContent=error.message;$('template-error').hidden=false;}finally{button.disabled=false;}});
  document.addEventListener('keydown',e=>{if(e.key.toLowerCase()==='n'&&!e.ctrlKey&&!e.metaKey&&!e.altKey&&!['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName)&&!document.querySelector('dialog[open]')){e.preventDefault();openCreate();}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopPoll();else schedulePoll();});
  init().catch(e=>toast(e.message));
})();
