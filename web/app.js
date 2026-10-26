const seed = {
  hr: [['Geethika','HR Administrator','geethika@tardus.com','Active'],['Daniel Kim','HR Manager','daniel@tardus.com','Active'],['Avery Stone','HR Specialist','avery@tardus.com','Pending']],
  organization: [['Tardus Inc','Company profile','Chicago, IL','Active'],['Biweekly','Payroll period','Aug 12 – Aug 25','Active']],
  employees: [['Maya Rodriguez','Product Design','Daniel Kim','Active'],['Noah Williams','Engineering','Sarah Chen','Active'],['Emma Davis','Sales','Olivia Brown','Active']],
  timesheets: [['Maya Rodriguez','Aug 12 – Aug 18','40 hours','Approved'],['Noah Williams','Aug 12 – Aug 18','38 hours','Pending'],['Emma Davis','Aug 12 – Aug 18','40 hours','Approved']],
  payroll: [['August 2026 payroll','248 employees','$1,284,600','Ready'],['July 2026 payroll','246 employees','$1,270,410','Completed']],
  reports: [['Workforce summary','Workforce','Aug 20, 2026','Ready'],['Timesheet exceptions','Timesheets','Aug 19, 2026','Ready']],
  jobs: [['Senior Product Designer','Design','Chicago, IL','Pending'],['Data Engineer','Engineering','Remote','Active'],['HR Business Partner','People','Dallas, TX','Active']],
  applications: [['Jordan Lee','Senior Product Designer','Interview','Active'],['Taylor Smith','Data Engineer','Screening','Pending'],['Morgan Davis','HR Business Partner','Review','Pending']],
  events: [['Fall Career Fair','Recruiting','Sep 14, 2026','Published'],['Manager Workshop','Training','Sep 22, 2026','Draft']],
  registrations: [['Alex Morgan','Fall Career Fair','alex@example.com','Confirmed'],['Jamie Patel','Manager Workshop','jamie@example.com','Pending']],
  audit: [['Geethika','Created HR user','Avery Stone','Aug 21, 10:42 AM'],['Daniel Kim','Approved timesheet','Noah Williams','Aug 20, 4:15 PM']]
};

const modules = {
  hr:{icon:'HR',title:'HR Management',copy:'Create HR users and manage HR accounts.',action:'Create a New HR',columns:['Name','Role','Email','Status'],fields:[['Name','text'],['Role','select','HR Administrator|HR Manager|HR Specialist'],['Email','email'],['Status','select','Active|Pending|Inactive']]},
  organization:{icon:'ORG',title:'Organization Settings',copy:'Configure organization information and the company payroll period.',action:'Edit organization',columns:['Name','Setting','Detail','Status'],fields:[['Name','text'],['Setting','text'],['Detail','text'],['Status','select','Active|Inactive']]},
  employees:{icon:'EMP',title:'Employee Management',copy:'View employees and their reporting relationships.',action:'Add employee',columns:['Name','Department','Manager','Status'],fields:[['Name','text'],['Department','text'],['Manager','text'],['Status','select','Active|Inactive']]},
  timesheets:{icon:'TIME',title:'Timesheets',copy:'View HR and employee timesheets across the organization.',action:'Add timesheet',columns:['Employee','Period','Hours','Status'],fields:[['Employee','text'],['Period','text'],['Hours','text'],['Status','select','Pending|Approved|Rejected']]},
  payroll:{icon:'PAY',title:'Payroll',copy:'Run HR payroll and review organization payroll records.',action:'Run payroll',columns:['Run','Scope','Total','Status'],fields:[['Run','text'],['Scope','text'],['Total','text'],['Status','select','Ready|Processing|Completed']]},
  reports:{icon:'RPT',title:'Reports',copy:'Generate workforce, timesheet, and payroll reports.',action:'Generate report',columns:['Report','Type','Created','Status'],fields:[['Report','text'],['Type','select','Workforce|Timesheets|Payroll'],['Created','date'],['Status','select','Ready|Processing']]},
  jobs:{icon:'JOB',title:'Jobs',copy:'Manage organization job postings and recruiting activity.',action:'Create job',columns:['Title','Department','Location','Status'],fields:[['Title','text'],['Department','text'],['Location','text'],['Status','select','Draft|Pending|Active|Closed']]},
  applications:{icon:'APP',title:'Applications',copy:'View applicants and update application statuses.',action:'Add application',columns:['Applicant','Job','Stage','Status'],fields:[['Applicant','text'],['Job','text'],['Stage','select','Review|Screening|Interview|Offer'],['Status','select','Pending|Active|Rejected|Hired']]},
  events:{icon:'EVT',title:'Events',copy:'Create and manage organization events.',action:'Create event',columns:['Event','Type','Date','Status'],fields:[['Event','text'],['Type','text'],['Date','date'],['Status','select','Draft|Published|Completed']]},
  registrations:{icon:'REG',title:'Registrations',copy:'View event registrations and participant information.',action:'Export registrations',columns:['Participant','Event','Email','Status'],fields:[['Participant','text'],['Event','text'],['Email','email'],['Status','select','Pending|Confirmed|Cancelled']],export:true},
  audit:{icon:'LOG',title:'Audit History',copy:'Review significant My HR actions across the organization.',action:'Export audit history',columns:['User','Action','Record','Date'],export:true}
};

let data = JSON.parse(localStorage.getItem('waypointAdminData') || 'null') || structuredClone(seed);
data = Object.fromEntries(Object.entries(data).map(([key, rows]) => [key, rows.map(row => row.map(value => typeof value === 'string' ? value.replaceAll('Pragna Rao','Geethika').replaceAll('pragna@tardus.com','geethika@tardus.com') : value))]));
localStorage.setItem('waypointAdminData', JSON.stringify(data));
let currentKey = null;
const home=document.querySelector('#home'), detail=document.querySelector('#detail'), title=document.querySelector('#detail-title'), copy=document.querySelector('#detail-copy'), content=document.querySelector('#detail-content'), primary=document.querySelector('#primary-action');

document.querySelectorAll('.admin-card').forEach((card) => {
  const module = modules[card.dataset.page];
  card.insertAdjacentHTML('afterbegin', `<span class="icon" aria-hidden="true">${module.icon}</span>`);
});

function save(){ localStorage.setItem('waypointAdminData', JSON.stringify(data)); }
function safe(value){ const node=document.createElement('span'); node.textContent=value; return node.innerHTML; }
function statusClass(value){ return /pending|draft|processing|ready/i.test(value) ? 'pending' : /inactive|rejected|cancelled|closed/i.test(value) ? 'inactive' : ''; }

function openModule(key){
  if(!modules[key]) return;
  currentKey=key; home.classList.remove('active'); detail.classList.add('active');
  title.textContent=modules[key].title; copy.textContent=modules[key].copy; primary.textContent=modules[key].action;
  renderModule(); window.scrollTo({top:0,behavior:'smooth'});
}

function renderModule(query=''){
  const module=modules[currentKey];
  const rows=(data[currentKey] || []).map((row,index)=>({row,index})).filter(({row})=>row.join(' ').toLowerCase().includes(query.toLowerCase()));
  content.innerHTML=`<div class="table-tools"><label class="search"><span>Search</span><input id="table-search" type="search" value="${safe(query)}" placeholder="Search ${safe(module.title.toLowerCase())}..."></label><span>${rows.length} record${rows.length===1?'':'s'}</span></div>
  <div class="table-wrap"><table><thead><tr>${module.columns.map(c=>`<th>${safe(c.toUpperCase())}</th>`).join('')}<th>ACTIONS</th></tr></thead><tbody>${rows.length?rows.map(({row,index})=>`<tr>${row.map((cell,i)=>`<td>${i===3?`<span class="status ${statusClass(cell)}">${safe(cell)}</span>`:safe(cell)}</td>`).join('')}<td class="row-actions"><select class="action-select" data-index="${index}" aria-label="Actions for ${safe(row[0])}"><option value="">Choose action...</option><option value="view">View details</option>${module.fields?`<option value="edit">Edit</option><option value="toggle">Change status</option><option value="delete">Delete</option>`:''}</select></td></tr>`).join(''):`<tr><td colspan="${module.columns.length+1}" class="no-results">No matching records.</td></tr>`}</tbody></table></div>`;
  document.querySelector('#table-search').addEventListener('input',e=>renderModule(e.target.value));
}

function openForm(index=null){
  const module=modules[currentKey], row=index===null?[]:data[currentKey][index];
  showModal(index===null?module.action:`Edit ${module.title.slice(0,-1)}`, `<form id="record-form" class="record-form">${module.fields.map(([label,type,options],i)=>`<label><span>${safe(label)}</span>${type==='select'?`<select required name="field-${i}">${options.split('|').map(v=>`<option ${row[i]===v?'selected':''}>${safe(v)}</option>`).join('')}</select>`:`<input required name="field-${i}" type="${type}" value="${safe(row[i]||'')}">`}</label>`).join('')}<div class="modal-actions"><button type="button" class="secondary" data-close>Cancel</button><button class="primary" type="submit">${index===null?'Create':'Save changes'}</button></div></form>`);
  document.querySelector('#record-form').addEventListener('submit',e=>{e.preventDefault();const values=module.fields.map((_,i)=>new FormData(e.target).get(`field-${i}`));if(index===null)data[currentKey].unshift(values);else data[currentKey][index]=values;save();closeModal();renderModule();updateCounts();notify(index===null?'Record created successfully.':'Changes saved successfully.');});
}

function showModal(heading,body){document.querySelector('#modal-title').textContent=heading;document.querySelector('#modal-body').innerHTML=body;document.querySelector('#modal').classList.add('open');document.querySelector('#modal').setAttribute('aria-hidden','false');document.querySelector('#modal-body input, #modal-body select, #modal-body button')?.focus();}
function closeModal(){document.querySelector('#modal').classList.remove('open');document.querySelector('#modal').setAttribute('aria-hidden','true');}
function viewRow(index){const module=modules[currentKey],row=data[currentKey][index];showModal(`${module.title} details`,`<dl class="details">${module.columns.map((column,i)=>`<div><dt>${safe(column)}</dt><dd>${safe(row[i])}</dd></div>`).join('')}</dl><div class="modal-actions"><button class="primary" data-close>Done</button></div>`);}
function exportCsv(){const module=modules[currentKey];const csv=[module.columns,...data[currentKey]].map(row=>row.map(v=>`"${String(v).replaceAll('"','""')}"`).join(',')).join('\n');const link=document.createElement('a');link.href=URL.createObjectURL(new Blob([csv],{type:'text/csv'}));link.download=`${currentKey}-export.csv`;link.click();URL.revokeObjectURL(link.href);notify('CSV export downloaded.');}

primary.addEventListener('click',()=>modules[currentKey].export?exportCsv():openForm());
content.addEventListener('change',e=>{const dropdown=e.target.closest('.action-select');if(!dropdown)return;const index=Number(dropdown.dataset.index),action=dropdown.value;if(action==='view')viewRow(index);if(action==='edit')openForm(index);if(action==='toggle'){const values=currentKey==='timesheets'?['Pending','Approved','Rejected']:['Active','Pending','Inactive'];const row=data[currentKey][index];row[3]=values[(values.indexOf(row[3])+1)%values.length];save();renderModule();notify(`Status changed to ${row[3]}.`);}if(action==='delete'&&confirm('Delete this mock record?')){data[currentKey].splice(index,1);save();renderModule();updateCounts();notify('Record deleted.');}else dropdown.value='';});
document.querySelectorAll('[data-page]').forEach(button=>button.addEventListener('click',()=>openModule(button.dataset.page)));
document.querySelector('#back').addEventListener('click',()=>{detail.classList.remove('active');home.classList.add('active');currentKey=null;window.scrollTo({top:0,behavior:'smooth'});});
document.querySelector('.brand').addEventListener('click',e=>{e.preventDefault();document.querySelector('#back').click();});

const menu=document.querySelector('#profile-menu'),profile=document.querySelector('#profile-button');
profile.addEventListener('click',()=>{const open=menu.classList.toggle('open');profile.setAttribute('aria-expanded',open);});
document.addEventListener('click',event=>{if(!event.target.closest('.profile')&&!event.target.closest('.profile-menu'))menu.classList.remove('open');});
document.querySelector('[data-action="profile"]').addEventListener('click',()=>showModal('My profile','<dl class="details"><div><dt>Name</dt><dd>Geethika</dd></div><div><dt>Role</dt><dd>Administrator</dd></div><div><dt>Organization</dt><dd>Tardus Inc</dd></div><div><dt>Email</dt><dd>geethika@tardus.com</dd></div></dl><div class="modal-actions"><button class="primary" data-close>Done</button></div>'));
document.querySelector('[data-action="signout"]').addEventListener('click',()=>showModal('Sign out?','<p class="modal-copy">This is a mock workspace. Confirming will return you to the dashboard.</p><div class="modal-actions"><button class="secondary" data-close>Cancel</button><button class="primary" id="confirm-signout">Sign out</button></div>'));
document.querySelector('#modal').addEventListener('click',e=>{if(e.target.matches('[data-close], .modal-backdrop'))closeModal();if(e.target.id==='confirm-signout'){closeModal();if(currentKey)document.querySelector('#back').click();notify('Signed out of the mock session.');}});
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeModal();});

function updateCounts(){document.querySelector('[data-page="employees"] strong').textContent=data.employees.length;document.querySelector('[data-page="timesheets"] strong').textContent=data.timesheets.filter(r=>r[3]==='Pending').length;document.querySelector('[data-page="jobs"] strong').textContent=data.jobs.filter(r=>r[3]==='Active').length;}
function notify(message){const toast=document.querySelector('#toast');toast.textContent=message;toast.classList.add('show');clearTimeout(window.toastTimer);window.toastTimer=setTimeout(()=>toast.classList.remove('show'),2500);}
updateCounts();
