import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Dropdown, TextArea, TextInput } from '../../../../components';
import { getMyHrSession } from '../../../../auth/myHrSession';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import { formatDateOnly } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import { DOCUMENT_CATEGORIES } from './documentCategories';
import './documentRequests.css';

// Re-exported for backward compatibility - documentCategories.js is now the
// canonical source (also used by DocumentRequestsPage.jsx's filter).
export { DOCUMENT_CATEGORIES };

const initialForm = { category: '', requestedForUserId: '', priority: 'NORMAL', requiredBy: '', instructions: '', documents: [] };
const today = () => new Date().toISOString().slice(0, 10);
const OTHER_DOCUMENT = 'Other document';

export default function RequestDocumentsForm({ role }) {
  const navigate = useNavigate();
  const session = getMyHrSession();
  const basePath = role === 'COMPANY_ADMIN' ? '/myhr/admin' : '/myhr/hr';
  const [step, setStep] = useState(1); const [form, setForm] = useState(initialForm); const [employees, setEmployees] = useState([]);
  const [loading, setLoading] = useState(true); const [submitting, setSubmitting] = useState(false); const [errors, setErrors] = useState({}); const [serverError, setServerError] = useState('');
  const [documentSearch, setDocumentSearch] = useState(''); const [pickerOpen, setPickerOpen] = useState(false);
  useEffect(() => { myHrDocumentRequestApi.requestableEmployees().then((response) => setEmployees(response.data.data || [])).catch((error) => setServerError(error?.response?.data?.message || 'Employees could not be loaded.')).finally(() => setLoading(false)); }, []);
  const available = [...(DOCUMENT_CATEGORIES[form.category]?.documents || []), OTHER_DOCUMENT];
  const options = available.filter((document) => !form.documents.includes(document) && document.toLowerCase().includes(documentSearch.toLowerCase()));
  const employee = employees.find((item) => item.userId === form.requestedForUserId);
  const setField = (field) => (event) => { const value = event.target.value; setForm((current) => ({ ...current, [field]: value, ...(field === 'category' ? { documents: [] } : {}) })); if (field === 'category') { setDocumentSearch(''); setPickerOpen(false); } setErrors((current) => ({ ...current, [field]: undefined, ...(field === 'category' ? { documents: undefined } : {}) })); };
  const addDocument = (document) => { setForm((current) => current.documents.includes(document) ? current : { ...current, documents: [...current.documents, document] }); setDocumentSearch(''); setPickerOpen(false); setErrors((current) => ({ ...current, documents: undefined })); };
  const removeDocument = (document) => setForm((current) => ({ ...current, documents: current.documents.filter((item) => item !== document) }));
  const continueToReview = (event) => { event.preventDefault(); const next = {}; if (!form.category) next.category = 'Select a document category.'; if (!form.requestedForUserId) next.requestedForUserId = 'Select an employee.'; if (!form.documents.length) next.documents = 'Select at least one document.'; if (form.requiredBy && form.requiredBy < today()) next.requiredBy = 'Required-by date cannot be in the past.'; setErrors(next); if (!Object.keys(next).length) setStep(2); };
  const submit = async () => { setSubmitting(true); setServerError(''); try { await myHrDocumentRequestApi.create({ ...form, requiredBy: form.requiredBy || null, instructions: form.instructions.trim() }); navigate(`${basePath}/document-requests`, { replace: true, state: { message: `${form.documents.length} document request${form.documents.length === 1 ? '' : 's'} sent successfully.` } }); } catch (error) { setServerError(error?.response?.data?.message || 'Document requests could not be sent.'); } finally { setSubmitting(false); } };
  return <MyHrFeaturePage className="phase-six" title="Request Documents" description="Request onboarding, tax, benefits, immigration and offboarding documents from employees." fullWidth backTo={`${basePath}/document-requests`}>
    <section className="request-documents__card"><header><div><span>HUMAN RESOURCES</span><h2>REQUEST FROM EMPLOYEE</h2></div><em className="request-documents__role-badge">{role === 'COMPANY_ADMIN' ? 'Admin Request' : 'HR Request'}</em></header>
      <div className="request-documents__steps"><span className={step === 1 ? 'active' : 'complete'}><b>1</b> Details</span><i /><span className={step === 2 ? 'active' : ''}><b>2</b> Review</span></div>{serverError && <p className="document-requests__error">{serverError}</p>}
      {step === 1 ? <form onSubmit={continueToReview} className="request-documents__form" noValidate><div className="request-documents__grid">
        <Dropdown name="category" label="Document Category" required value={form.category} onChange={setField('category')} placeholder="Select a category" error={errors.category}
          options={Object.entries(DOCUMENT_CATEGORIES).map(([value, item]) => ({ value, label: item.label }))} />
        <Dropdown name="requestedForUserId" label="Employee" required value={form.requestedForUserId} onChange={setField('requestedForUserId')} disabled={loading}
          placeholder={loading ? 'Loading employees...' : 'Select an employee'} error={errors.requestedForUserId}
          options={employees.map((item) => ({ value: item.userId, label: `${item.employeeId || item.loginId} - ${item.name} - ${item.role === 'HR' ? 'HR' : 'Employee'}` }))} />
        <Dropdown name="priority" label="Priority" value={form.priority} onChange={setField('priority')}
          options={[{ value: 'NORMAL', label: 'Normal' }, { value: 'HIGH', label: 'High' }, { value: 'URGENT', label: 'Urgent' }]} />
        <TextInput name="requiredBy" label="Required By (optional)" type="date" min={today()} value={form.requiredBy} onChange={setField('requiredBy')} error={errors.requiredBy} /></div>
        <label className="request-documents__wide"><span className="request-documents__label">Documents Required <span className="wp-form-label__required" aria-hidden="true">*</span></span><div className="request-documents__picker"><input type="search" value={documentSearch} disabled={!form.category} onFocus={() => setPickerOpen(true)} onClick={() => setPickerOpen(true)} onChange={(event) => { setDocumentSearch(event.target.value); setPickerOpen(true); }} placeholder="Type to search documents in this category..." />{form.category && pickerOpen && <div className="request-documents__options" role="listbox">{options.length ? options.map((document) => <button type="button" role="option" key={document} onMouseDown={(event) => event.preventDefault()} onClick={() => addDocument(document)}>{document}</button>) : <span>No matching options</span>}</div>}</div>{form.documents.length > 0 && <div className="request-documents__chips">{form.documents.map((document) => <span key={document}>{document}<button type="button" aria-label={`Remove ${document}`} onClick={() => removeDocument(document)}>×</button></span>)}</div>}{errors.documents && <small>{errors.documents}</small>}</label>
        {form.category && pickerOpen && !options.length && documentSearch.trim() && <button type="button" className="request-documents__add-other" onClick={() => addDocument(`Other document: ${documentSearch.trim()}`)}>Add “{documentSearch.trim()}” as another document</button>}
        <div className="request-documents__wide"><TextArea name="instructions" label="Instructions to Employee (optional)" value={form.instructions} onChange={setField('instructions')} placeholder="Add any details the employee should know." error={errors.instructions} /></div>
        <footer><Button type="button" variant="secondary" onClick={() => navigate(`${basePath}/document-requests`)}>Cancel</Button><Button type="submit">Continue</Button></footer></form>
        : <div className="request-documents__review"><h3>REVIEW REQUEST</h3><div className="request-documents__summary"><dl><div><dt>CATEGORY</dt><dd>{DOCUMENT_CATEGORIES[form.category].label}</dd></div><div><dt>PRIORITY</dt><dd>{form.priority}</dd></div><div><dt>REQUESTED BY</dt><dd>{session?.loginId || 'Authenticated user'}</dd></div></dl><dl><div><dt>EMPLOYEE</dt><dd>{employee ? `${employee.employeeId || employee.loginId} - ${employee.name}` : '—'}</dd></div><div><dt>REQUIRED BY</dt><dd>{form.requiredBy ? formatDateOnly(form.requiredBy) : '—'}</dd></div><div><dt>DOCUMENTS</dt><dd>{form.documents.length}</dd></div></dl></div><section><h4>DOCUMENTS REQUIRED</h4><ul>{form.documents.map((document) => <li key={document}><b>✓</b>{document}</li>)}</ul></section><section><h4>INSTRUCTIONS TO EMPLOYEE</h4><p>{form.instructions}</p></section><footer><Button type="button" variant="secondary" onClick={() => setStep(1)}>Edit</Button><Button loading={submitting} onClick={submit}>Send</Button></footer></div>}
    </section></MyHrFeaturePage>;
}
