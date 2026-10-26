import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, DataTable, DetailPanel, FilterPills, RouteLine, StatusBadge, DatePicker, Dropdown, EmailInput, EmptyState, Loader, Modal, PhoneInput, SearchInput, TextArea, TextInput, ValidationMessage } from '../../../../components';
import StrokeIcon from '../../../../components/global/StrokeIcon';
import useDialogFocus from '../../../../hooks/useDialogFocus';
import { searchAddressSuggestions } from '../../../../api/addressAutocompleteApi';
import myHrAccountApi from '../../../../api/myHrAccountApi';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import { formatDateOnly } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import { isMobileDevice } from '../../../../utils/deviceDetection';
import { downloadReportExcel, downloadReportPdf } from '../reports/reportExport';
import './employeeManagement.css';

const COLUMNS = [
  { key: 'employeeId', header: 'Employee ID' },
  { key: 'name', header: 'Name' },
  { key: 'personType', header: 'Person Type' },
  { key: 'designation', header: 'Designation' },
  { key: 'reportingManagerName', header: 'Reporting Manager' },
  { key: 'accountStatus', header: 'Status' },
];

const EMPLOYMENT_TYPES = [
  { value: 'FULL_TIME', label: 'Full Time' },
  { value: 'PART_TIME', label: 'Part Time' },
  { value: 'CONTRACT', label: 'Contract' },
  { value: 'INTERNSHIP', label: 'Internship' },
];
const ROLE_OPTIONS = [{ value: 'HR', label: 'HR' }, { value: 'EMPLOYEE', label: 'Employee' }];
const REQUIRED_FIELDS = ['firstName', 'lastName', 'personalEmail', 'phoneNumber', 'designation', 'employmentType', 'startDate', 'streetAddress', 'city', 'state', 'zipCode'];
const FIELD_LABELS = { firstName: 'First Name', lastName: 'Last Name', personalEmail: 'Personal Email', phoneNumber: 'Phone Number', designation: 'Designation', employmentType: 'Employment Type', startDate: 'Start Date', streetAddress: 'Street Address', city: 'City', state: 'State', zipCode: 'ZIP Code', accountRole: 'Role', reportingManagerUserId: 'Reporting Manager' };
const emptyForm = { accountRole: '', firstName: '', lastName: '', personalEmail: '', phoneNumber: '', designation: '', employmentType: '', startDate: '', streetAddress: '', city: '', state: '', zipCode: '', reportingManagerUserId: '' };
const ADDRESS_DEBOUNCE_MS = 400;

function errorMessage(error, fallback) {
  return error?.response?.data?.message || fallback;
}

// Same local-date convention as JobPostingPage/EmployerEventFormPage's date fields,
// so "today" lines up with the browser's local calendar, not UTC.
function getLocalDateString() {
  const now = new Date();
  const localDate = new Date(now.getTime() - now.getTimezoneOffset() * 60 * 1000);
  return localDate.toISOString().slice(0, 10);
}

function dayAfter(isoDate) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}

function laterDate(a, b) {
  return a > b ? a : b;
}

function formatDate(value) {
  return value ? formatDateOnly(value) : '—';
}

function personLabel(value) {
  return value === 'HR' ? 'HR' : 'Employee';
}

function initials(detail) {
  if (detail?.firstName && detail?.lastName) return `${detail.firstName[0]}${detail.lastName[0]}`.toUpperCase();
  const parts = (detail?.name || '').trim().split(/\s+/);
  return `${parts[0]?.[0] || ''}${parts[1]?.[0] || ''}`.toUpperCase() || '—';
}

function formatAddress(detail) {
  return [detail.streetAddress, detail.city, detail.state, detail.zipCode].filter(Boolean).join(', ') || '—';
}

function normalizedProfile(form) {
  return {
    firstName: form.firstName.trim(),
    lastName: form.lastName.trim(),
    personalEmail: form.personalEmail.trim().toLowerCase(),
    phoneNumber: form.phoneNumber.trim(),
    designation: form.designation.trim(),
    employmentType: form.employmentType,
    startDate: form.startDate,
    streetAddress: form.streetAddress.trim(),
    city: form.city.trim(),
    state: form.state.trim(),
    zipCode: form.zipCode.trim(),
  };
}

function accountErrors(form, actorRole) {
  const errors = {};
  REQUIRED_FIELDS.forEach((field) => {
    if (!String(form[field] || '').trim()) errors[field] = `${FIELD_LABELS[field]} is required`;
  });
  if (form.personalEmail.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.personalEmail.trim())) {
    errors.personalEmail = 'Enter a valid personal email address';
  }
  const personName = /^[\p{L}\p{M}]+(?: [\p{L}\p{M}]+)*$/u;
  if (form.firstName.trim() && !personName.test(form.firstName.trim())) errors.firstName = 'Enter a valid first name';
  if (form.lastName.trim() && !personName.test(form.lastName.trim())) errors.lastName = 'Enter a valid last name';
  if (form.phoneNumber && !/^\+1\d{10}$/.test(form.phoneNumber)) errors.phoneNumber = 'Enter a valid 10-digit phone number';
  if (form.zipCode.trim() && !/^\d{5}$/.test(form.zipCode.trim())) errors.zipCode = 'Enter a valid 5-digit ZIP code';
  const targetRole = actorRole === 'HR' ? 'EMPLOYEE' : form.accountRole;
  if (actorRole === 'COMPANY_ADMIN' && !form.accountRole) errors.accountRole = 'Role is required';
  if (targetRole === 'EMPLOYEE' && !form.reportingManagerUserId) {
    errors.reportingManagerUserId = 'Reporting Manager is required';
  }
  return errors;
}

function managerLabel(manager) {
  return [manager.firstName, manager.lastName].filter(Boolean).join(' ') || 'Name unavailable';
}

function accountSummary(account) {
  return {
    userId: account.userId,
    employeeProfileId: account.employeeProfileId,
    loginId: account.loginId,
    employeeId: account.employeeId,
    email: account.email,
    role: account.role,
    organizationId: account.organizationId,
    mustChangePassword: account.mustChangePassword,
    emailDelivered: account.emailDelivered === true,
  };
}

export default function EmployeeManagementPage({ role }) {
  const [listState, setListState] = useState({ loading: true, error: null, rows: [] });
  const [query, setQuery] = useState('');
  const [personType, setPersonType] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [triageFilter, setTriageFilter] = useState('ALL');
  const [pageSize, setPageSize] = useState(10);
  const [page, setPage] = useState(1);
  const [viewMode, setViewMode] = useState(() => {
    if (typeof window !== 'undefined') {
      if (isMobileDevice() || window.innerWidth <= 768) {
        return 'cards';
      }
    }
    return 'table';
  });

  const [selectedId, setSelectedId] = useState(null);
  const [selectedRows, setSelectedRows] = useState([]);
  const [detailState, setDetailState] = useState({ loading: false, error: null, data: null });
  const detailSequence = useRef(0);
  const filterMenuRef = useRef(null);
  const [notice, setNotice] = useState(null);
  const [copiedNotice, setCopiedNotice] = useState(null);

  const copyToClipboard = (text, label, e) => {
    if (e) e.stopPropagation();
    if (!text) return;
    if (navigator?.clipboard?.writeText) {
      navigator.clipboard.writeText(text);
    }
    setCopiedNotice(`Copied ${label || text}!`);
    setTimeout(() => setCopiedNotice(null), 2000);
  };

  const [dialog, setDialog] = useState(null); // 'manager' | 'status' | 'add'

  // Change Reporting Manager
  const [managerOptions, setManagerOptions] = useState({ loading: false, error: null, options: [] });
  const [managerForm, setManagerForm] = useState({ newReportingManagerUserId: '', effectiveDate: '', reason: '' });
  const [actionError, setActionError] = useState(null);
  const [actionBusy, setActionBusy] = useState(false);

  const [reassignOptions, setReassignOptions] = useState({ loading: false, error: null, options: [] });
  const [reassignManagerUserId, setReassignManagerUserId] = useState('');

  // Create Account (HR / Employee)
  const [form, setForm] = useState(emptyForm);
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState('');
  const [step, setStep] = useState(1);
  const [managers, setManagers] = useState([]);
  const [loadingManagers, setLoadingManagers] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [createdAccount, setCreatedAccount] = useState(null);
  const [createdName, setCreatedName] = useState('');
  const [addressSuggestions, setAddressSuggestions] = useState([]);
  const [addressSearching, setAddressSearching] = useState(false);
  const [addressMessage, setAddressMessage] = useState('');
  const addressRequestSequence = useRef(0);
  const suppressNextAddressLookup = useRef(false);
  const targetRole = role === 'HR' ? 'EMPLOYEE' : form.accountRole;

  const loadList = useCallback(() => {
    setListState((current) => ({ ...current, loading: true, error: null }));
    myHrEmployeeApi.list()
      .then((response) => setListState({ loading: false, error: null, rows: response.data.data || [] }))
      .catch((error) => setListState({ loading: false, error: errorMessage(error, 'Unable to load employees.'), rows: [] }));
  }, []);

  useEffect(() => { loadList(); }, [loadList]);

  useEffect(() => {
    const closeFilterOnOutsideClick = (event) => {
      if (filterMenuRef.current?.open && !filterMenuRef.current.contains(event.target)) {
        filterMenuRef.current.open = false;
      }
    };
    document.addEventListener('pointerdown', closeFilterOnOutsideClick);
    return () => document.removeEventListener('pointerdown', closeFilterOnOutsideClick);
  }, []);

  const loadDetail = useCallback((userId) => {
    const sequence = ++detailSequence.current;
    setDetailState({ loading: true, error: null, data: null });
    myHrEmployeeApi.get(userId)
      .then((response) => { if (sequence === detailSequence.current) setDetailState({ loading: false, error: null, data: response.data.data }); })
      .catch((error) => { if (sequence === detailSequence.current) setDetailState({ loading: false, error: errorMessage(error, 'Unable to load this employee.'), data: null }); });
  }, []);

  const openEmployee = (userId) => { setSelectedId(userId); setNotice(null); loadDetail(userId); };
  const closeEmployee = () => { detailSequence.current += 1; setSelectedId(null); setDetailState({ loading: false, error: null, data: null }); setNotice(null); };

  const resetAccountFlow = () => {
    setStep(1); setForm(emptyForm); setErrors({}); setFormError(''); setManagers([]);
    setCreatedAccount(null); setCreatedName(''); setSubmitting(false);
    setAddressSuggestions([]); setAddressSearching(false); setAddressMessage('');
  };
  const closeDialog = () => {
    setDialog(null); setActionError(null); setActionBusy(false);
    setReassignOptions({ loading: false, error: null, options: [] }); setReassignManagerUserId('');
    resetAccountFlow();
  };
  const openAccountDialog = () => { resetAccountFlow(); setDialog('add'); };
  useDialogFocus(dialog, closeDialog);

  useEffect(() => {
    if (dialog !== 'add' || targetRole !== 'EMPLOYEE') return undefined;
    let active = true;
    setLoadingManagers(true);
    myHrAccountApi.getEligibleReportingManagers()
      .then((response) => { if (active) setManagers(response.data.data || []); })
      .catch((error) => { if (active && error?.response?.status !== 401) setFormError(error?.response?.data?.message || 'Unable to load reporting managers.'); })
      .finally(() => { if (active) setLoadingManagers(false); });
    return () => { active = false; };
  }, [dialog, targetRole]);

  useEffect(() => {
    const search = form.streetAddress.trim();
    const sequence = ++addressRequestSequence.current;
    setAddressMessage('');

    if (suppressNextAddressLookup.current) {
      suppressNextAddressLookup.current = false;
      setAddressSuggestions([]);
      setAddressSearching(false);
      return undefined;
    }

    if (dialog !== 'add' || step !== 1 || search.length < 3) {
      setAddressSuggestions([]);
      setAddressSearching(false);
      return undefined;
    }

    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setAddressSearching(true);
      try {
        const suggestions = await searchAddressSuggestions(search, { signal: controller.signal });
        if (sequence === addressRequestSequence.current) setAddressSuggestions(suggestions);
      } catch (error) {
        if (error?.name !== 'AbortError' && sequence === addressRequestSequence.current) {
          setAddressSuggestions([]);
          setAddressMessage('Address suggestions unavailable. You can enter the address manually.');
        }
      } finally {
        if (sequence === addressRequestSequence.current) setAddressSearching(false);
      }
    }, ADDRESS_DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [dialog, form.streetAddress, step]);

  const setField = (name) => (event) => {
    const value = ['firstName', 'lastName'].includes(name)
      ? event.target.value.replace(/[^\p{L}\p{M} ]/gu, '')
      : event.target.value;
    setForm((current) => ({ ...current, [name]: value, ...(name === 'accountRole' && value === 'HR' ? { reportingManagerUserId: '' } : {}) }));
    setErrors((current) => ({ ...current, [name]: undefined }));
    setFormError('');
  };
  const continueToReview = () => {
    const nextErrors = accountErrors(form, role);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length === 0) setStep(2);
  };
  const selectAddressSuggestion = (suggestion) => {
    addressRequestSequence.current += 1;
    suppressNextAddressLookup.current = true;
    setForm((current) => ({
      ...current,
      streetAddress: suggestion.streetAddress || current.streetAddress,
      city: suggestion.city || current.city,
      state: suggestion.state || current.state,
      zipCode: suggestion.zipCode || current.zipCode,
    }));
    setErrors((current) => ({ ...current, streetAddress: undefined, city: undefined, state: undefined, zipCode: undefined }));
    setAddressSuggestions([]);
    setAddressSearching(false);
    setAddressMessage('');
  };
  const createAccount = async () => {
    const nextErrors = accountErrors(form, role);
    if (Object.keys(nextErrors).length) { setErrors(nextErrors); setStep(1); return; }
    setSubmitting(true); setFormError('');
    const profile = normalizedProfile(form);
    try {
      const response = targetRole === 'HR'
        ? await myHrAccountApi.createHrAccount(profile)
        : await myHrAccountApi.createEmployeeAccount(profile, form.reportingManagerUserId);
      setCreatedName(`${profile.firstName} ${profile.lastName}`);
      setCreatedAccount(accountSummary(response.data.data));
      loadList();
    } catch (error) {
      const data = error?.response?.data;
      if (data?.data && typeof data.data === 'object') {
        const fieldErrors = {};
        Object.entries(data.data).forEach(([field, message]) => { fieldErrors[field.split('.').pop()] = message; });
        setErrors(fieldErrors); setStep(1);
      } else if (error?.response?.status !== 401) setFormError(data?.message || 'Unable to create the account. Please try again.');
    } finally { setSubmitting(false); }
  };

  const counts = useMemo(() => {
    let active = 0;
    let inactive = 0;
    let hr = 0;
    let employee = 0;
    let unassignedManager = 0;
    listState.rows.forEach((p) => {
      if (p.accountStatus === 'ACTIVE') active++;
      else if (p.accountStatus === 'INACTIVE') inactive++;
      if (p.personType === 'HR') hr++;
      else employee++;
      if (!p.reportingManagerName && p.personType === 'EMPLOYEE') unassignedManager++;
    });
    return { total: listState.rows.length, active, inactive, hr, employee, unassignedManager };
  }, [listState.rows]);

  const rows = useMemo(() => listState.rows.filter((person) => {
    const haystack = `${person.employeeId || ''} ${person.name || ''} ${person.workEmail || ''} ${person.designation || ''}`.toLowerCase();
    const matchesQuery = haystack.includes(query.toLowerCase());
    const matchesPersonType = personType === 'ALL' || person.personType === personType;
    const matchesStatus = status === 'ALL' || person.accountStatus === status;
    const matchesTriage = triageFilter === 'ALL'
      || (triageFilter === 'ACTIVE' && person.accountStatus === 'ACTIVE')
      || (triageFilter === 'INACTIVE' && person.accountStatus === 'INACTIVE')
      || (triageFilter === 'HR' && person.personType === 'HR')
      || (triageFilter === 'UNASSIGNED_MANAGER' && !person.reportingManagerName && person.personType === 'EMPLOYEE');

    return matchesQuery && matchesPersonType && matchesStatus && matchesTriage;
  }), [listState.rows, query, personType, status, triageFilter]);

  useEffect(() => {
    const visibleIds = new Set(rows.map((person) => person.userId));
    setSelectedRows((current) => current.filter((userId) => visibleIds.has(userId)));
  }, [rows]);

  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageRows = rows.slice((safePage - 1) * pageSize, safePage * pageSize);
  useEffect(() => { setPage(1); }, [query, personType, status, triageFilter, pageSize]);

  const detail = detailState.data;
  const nextStatus = detail?.accountStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  const directReportCount = detail ? (detail.directReports || []).length : 0;
  const needsReassignment = Boolean(detail) && detail.personType === 'HR'
    && nextStatus === 'INACTIVE' && directReportCount > 0;
  const todayLocalDate = getLocalDateString();

  const exportRoster = (format) => {
    const exportRows = selectedRows.length > 0
      ? rows.filter((person) => selectedRows.includes(person.userId))
      : rows;
    const selectionLabel = selectedRows.length > 0
      ? `${exportRows.length} selected employee${exportRows.length === 1 ? '' : 's'}`
      : `${exportRows.length} matching record${exportRows.length === 1 ? '' : 's'}`;
    const options = {
      filename: `Employee_Roster_${todayLocalDate}.${format === 'pdf' ? 'pdf' : 'xls'}`,
      title: 'Employee Roster',
      subtitle: `Exported ${todayLocalDate} - ${selectionLabel}`,
      columns: COLUMNS,
      rows: exportRows,
      resolveValue: (column, person) => {
        if (column.key === 'personType') return personLabel(person.personType);
        if (column.key === 'accountStatus') return person.accountStatus === 'ACTIVE' ? 'Active' : 'Inactive';
        return person[column.key] || '—';
      },
    };
    if (format === 'pdf') downloadReportPdf(options);
    else downloadReportExcel(options);
  };
  const selectedNewManager = managerOptions.options.find(
    (option) => option.value === managerForm.newReportingManagerUserId,
  );
  const minEffectiveDate = [
    todayLocalDate,
    detail?.startDate ? dayAfter(detail.startDate) : todayLocalDate,
    selectedNewManager?.startDate ? dayAfter(selectedNewManager.startDate) : todayLocalDate,
  ].reduce(laterDate);

  const openManagerDialog = () => {
    setDialog('manager');
    setActionError(null);
    setManagerForm({ newReportingManagerUserId: '', effectiveDate: '', reason: '' });
    setManagerOptions({ loading: true, error: null, options: [] });
    myHrEmployeeApi.reportingManagers(selectedId)
      .then((response) => {
        const currentManagerId = detail?.reportingManager?.userId;
        const options = (response.data.data || [])
          .filter((manager) => manager.userId !== currentManagerId)
          .map((manager) => ({ value: manager.userId, label: `${manager.name} · ${manager.roleLabel}`, startDate: manager.startDate }));
        setManagerOptions({ loading: false, error: null, options });
      })
      .catch((error) => setManagerOptions({ loading: false, error: errorMessage(error, 'Unable to load reporting managers.'), options: [] }));
  };

  const submitManagerChange = () => {
    setActionBusy(true);
    setActionError(null);
    myHrEmployeeApi.changeReportingManager(selectedId, {
      newReportingManagerUserId: managerForm.newReportingManagerUserId,
      effectiveDate: managerForm.effectiveDate || null,
      reason: managerForm.reason.trim() || null,
    })
      .then((response) => {
        setDetailState({ loading: false, error: null, data: response.data.data });
        setDialog(null);
        setActionBusy(false);
        setNotice('Reporting manager updated.');
        loadList();
      })
      .catch((error) => { setActionError(errorMessage(error, 'Unable to update reporting manager.')); setActionBusy(false); });
  };

  const openStatusDialog = () => {
    setDialog('status');
    setActionError(null);
    setReassignManagerUserId('');
    const willNeedReassignment = detail.personType === 'HR' && nextStatus === 'INACTIVE'
      && (detail.directReports || []).length > 0;
    if (!willNeedReassignment) {
      setReassignOptions({ loading: false, error: null, options: [] });
      return;
    }
    setReassignOptions({ loading: true, error: null, options: [] });
    myHrEmployeeApi.reportingManagers()
      .then((response) => {
        const options = (response.data.data || [])
          .filter((manager) => manager.userId !== detail.userId)
          .map((manager) => ({ value: manager.userId, label: `${manager.name} · ${manager.roleLabel}` }));
        setReassignOptions({ loading: false, error: null, options });
      })
      .catch((error) => setReassignOptions({ loading: false, error: errorMessage(error, 'Unable to load reassignment options.'), options: [] }));
  };

  const submitStatusChange = () => {
    setActionBusy(true);
    setActionError(null);
    const payload = { status: nextStatus, ...(needsReassignment ? { reassignToUserId: reassignManagerUserId } : {}) };
    myHrEmployeeApi.updateStatus(selectedId, payload)
      .then((response) => {
        setDetailState({ loading: false, error: null, data: response.data.data });
        setDialog(null);
        setActionBusy(false);
        setNotice(nextStatus === 'ACTIVE' ? 'Account activated.' : 'Account deactivated.');
        loadList();
      })
      .catch((error) => { setActionError(errorMessage(error, 'Unable to update account status.')); setActionBusy(false); });
  };

  return <MyHrFeaturePage
    hideBack
    className="employee-management"
    title="Employee Management"
    description={role === 'COMPANY_ADMIN'
      ? 'Manage employees, HR access, and reporting relationships across the organization.'
      : 'View and manage the employees who report to you.'}
    headerAction={role !== 'EMPLOYEE' ? <Button type="button" variant="primary" onClick={openAccountDialog}><span aria-hidden="true">+</span> Create Account</Button> : undefined}
  >
    <div className="employee-management__controls-row">
      <div className="employee-management__search"><label htmlFor="employeeRosterSearch">Search people</label>
        <SearchInput id="employeeRosterSearch" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search by employee, ID, email, or designation" />
      </div>
      <FilterPills label="Quick triage filters" value={triageFilter} onChange={(value) => { setTriageFilter(value); setPage(1); }} options={[
        { value: 'ALL', label: 'All', count: !listState.loading && !listState.error ? counts.total : undefined },
        { value: 'ACTIVE', label: 'Active', count: !listState.loading && !listState.error ? counts.active : undefined },
        { value: 'INACTIVE', label: 'Inactive', count: !listState.loading && !listState.error ? counts.inactive : undefined },
        { value: 'HR', label: 'HR staff', count: !listState.loading && !listState.error ? counts.hr : undefined },
        ...(counts.unassignedManager > 0 ? [{ value: 'UNASSIGNED_MANAGER', label: 'Needs manager', count: counts.unassignedManager }] : []),
      ]} />
      <div className="employee-management__results-actions">
        <details ref={filterMenuRef} className="employee-management__filter-menu" onKeyDown={(event) => { if (event.key === 'Escape') { event.currentTarget.open = false; event.currentTarget.querySelector('summary').focus(); } }}>
          <summary><StrokeIcon name="filter" size={20} />Filters{(query || personType !== 'ALL' || status !== 'ALL' || triageFilter !== 'ALL') && <span className="employee-management__filter-dot" aria-label="Filters applied" />}</summary>
          <div className="employee-management__filter-content">

        <Dropdown name="personType" label="Person Type" value={personType} onChange={(event) => setPersonType(event.target.value)} options={[{ value: 'ALL', label: 'All people' }, { value: 'EMPLOYEE', label: 'Employees' }, { value: 'HR', label: 'HR' }]} />
        <Dropdown name="status" label="Account Status" value={status} onChange={(event) => setStatus(event.target.value)} options={[{ value: 'ALL', label: 'All statuses' }, { value: 'ACTIVE', label: 'Active' }, { value: 'INACTIVE', label: 'Inactive' }]} />
        <Button type="button" variant="secondary" className="employee-management__clear-btn" onClick={() => { setQuery(''); setPersonType('ALL'); setStatus('ALL'); setTriageFilter('ALL'); }} disabled={!query && personType === 'ALL' && status === 'ALL' && triageFilter === 'ALL'}>Clear filters</Button>
          </div>
        </details>
        <Button type="button" variant="secondary" className="employee-management__export-btn" onClick={() => exportRoster('pdf')} disabled={rows.length === 0} title="Export selected employees, or matching employees when none are selected, to PDF"><StrokeIcon name="download" size={20} /> Export PDF</Button>
        <Button type="button" variant="secondary" className="employee-management__export-btn" onClick={() => exportRoster('excel')} disabled={rows.length === 0} title="Export selected employees, or matching employees when none are selected, to Excel"><StrokeIcon name="download" size={20} /> Export Excel</Button>
        <div className="employee-management__view-toggle" role="group" aria-label="View format">
          <button
            type="button"
            className={`employee-management__view-toggle-btn ${viewMode === 'table' ? 'is-active' : ''}`}
            onClick={() => setViewMode('table')}
            aria-pressed={viewMode === 'table'}
            title="Table View"
          >
            <StrokeIcon name="table" size={20} /> Table
          </button>
          <button
            type="button"
            className={`employee-management__view-toggle-btn ${viewMode === 'cards' ? 'is-active' : ''}`}
            onClick={() => setViewMode('cards')}
            aria-pressed={viewMode === 'cards'}
            title="Cards View"
          >
            <StrokeIcon name="cards" size={20} /> Cards
          </button>
        </div>
      </div></div>

      {selectedRows.length > 0 && <div className="employee-management__selection" role="status">{selectedRows.length} selected <Button variant="secondary" onClick={() => setSelectedRows([])}>Clear selection</Button></div>}
      {listState.loading ? <Loader label="Loading employees…" />
        : listState.error ? <EmptyState title="Could not load employees" description={listState.error} action={<Button type="button" variant="secondary" onClick={loadList}>Try again</Button>} />
        : listState.rows.length === 0 ? <EmptyState title={role === 'COMPANY_ADMIN' ? 'No employees yet' : 'No employees report to you yet'} description={role === 'COMPANY_ADMIN' ? 'Employees and HR accounts in your organization will appear here.' : 'Employees assigned to you as their reporting manager will appear here.'} />
        : <>
          {viewMode === 'cards' ? (
            <div className="employee-management__cards-grid">
              {pageRows.map((person) => (
                <article key={person.userId} className={'employee-management__card' + (selectedRows.includes(person.userId) ? ' is-selected' : '')}>
                  <label className="employee-management__card-select"><input type="checkbox" aria-label={`Select ${person.name}`} checked={selectedRows.includes(person.userId)} onChange={() => setSelectedRows((current) => current.includes(person.userId) ? current.filter((id) => id !== person.userId) : [...current, person.userId])} /> Select person</label>
                  <div className="employee-management__card-header">
                    <div className="employee-management__card-identity">
                      <span className="employee-management__card-avatar">{initials(person)}</span>
                      <div>
                        <button
                          type="button"
                          className="employee-management__person-link"
                          onClick={() => openEmployee(person.userId)}
                        >
                          {person.name}
                        </button>
                        <span className="employee-management__card-sub">
                          <span className="employee-management__id-cell">
                            <span>{person.employeeId || 'No ID'}</span>
                            {person.employeeId && (
                              <button
                                type="button"
                                className="employee-management__copy-btn"
                                title={`Copy ${person.employeeId}`}
                                aria-label={`Copy ID ${person.employeeId}`}
                                onClick={(e) => copyToClipboard(person.employeeId, person.employeeId, e)}
                              >
                                <StrokeIcon name="copy" size={20} />
                              </button>
                            )}
                          </span> • <span>{person.designation || personLabel(person.personType)}</span>
                        </span>
                      </div>
                    </div>
                    <StatusBadge
                      status={person.accountStatus === 'ACTIVE' ? 'Active' : 'Inactive'}
                      tone={person.accountStatus === 'ACTIVE' ? 'success' : 'neutral'}
                    />
                  </div>
                  <div className="employee-management__card-body">
                    <div className="employee-management__card-row">
                      <span className="employee-management__card-label">Role</span>
                      <span>{personLabel(person.personType)}</span>
                    </div>
                    <div className="employee-management__card-row">
                      <span className="employee-management__card-label">Manager</span>
                      <span>{person.reportingManagerName || '—'}</span>
                    </div>
                  </div>
                  <div className="employee-management__card-footer">
                    <Button type="button" variant="secondary" onClick={() => openEmployee(person.userId)}>
                      View Profile
                    </Button>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <DataTable label="Employees" columns={[
              { key: 'person', header: 'Person', render: (value, person) => <div className="wp-data-table__person"><span className="wp-data-table__avatar" aria-hidden="true">{initials(person)}</span><span><button type="button" onClick={() => openEmployee(person.userId)}>{value.name || value.id}</button><small>{value.id}</small></span>{person.employeeId && <button type="button" aria-label={`Copy ID ${person.employeeId}`} onClick={(event) => copyToClipboard(person.employeeId, person.employeeId, event)}><StrokeIcon name="copy" size={20} /></button>}</div> },
              { key: 'personType', header: 'Type', render: (_, person) => personLabel(person.personType) },
              { key: 'designation', header: 'Designation' },
              { key: 'reportingManagerName', header: 'Manager' },
              { key: 'accountStatus', header: 'Status', type: 'status' },
            ]} rows={pageRows.map((person) => ({ ...person, id: person.userId, person: { name: person.name, id: person.employeeId } }))}
              selectedRowIds={selectedRows} onSelectionChange={setSelectedRows}
              getRowLabel={(person) => person.name || person.employeeId}
              onOpenRow={(person) => openEmployee(person.userId)}
              rowOpenable
              pagination={{ page: safePage, pageSize, total: rows.length, onPageChange: setPage }}
              emptyMessage="No people match these filters." />
          )}
          <div className="employee-management__pagination-bar">
            <div className="employee-management__page-size">
              <label htmlFor="employeePageSize">Per page:</label>
              <select
                id="employeePageSize"
                value={pageSize}
                onChange={(e) => { setPageSize(Number(e.target.value)); setPage(1); }}
              >
                <option value={10}>10</option>
                <option value={25}>25</option>
                <option value={50}>50</option>
                <option value={100}>100</option>
              </select>
            </div>
            {viewMode === 'cards' && <span>Showing {rows.length ? (safePage - 1) * pageSize + 1 : 0}–{Math.min(safePage * pageSize, rows.length)} of {rows.length}</span>}
            {viewMode === 'cards' && totalPages > 1 && <MyHrPagination currentPage={safePage} totalPages={totalPages} onPageChange={setPage} />}
          </div>
          {copiedNotice && (
            <div className="employee-management__copied-toast" role="status">
              <span>✓ {copiedNotice}</span>
            </div>
          )}
        </>}
    <DetailPanel open={Boolean(selectedId)} onClose={closeEmployee} title="Employee details" fullScreen className="employee-management__panel">
    <div className="employee-management__detail">
      <Button type="button" variant="secondary" onClick={closeEmployee}>Back to employees</Button>
      {notice && <p className="myhr-feature__notice">{notice}</p>}

      {detailState.loading ? <Loader label="Loading employee…" />
        : detailState.error ? <EmptyState title="Could not load this employee" description={detailState.error} action={<Button type="button" variant="secondary" onClick={() => loadDetail(selectedId)}>Try again</Button>} />
        : detail ? <>
          <section className="employee-management__identity">
            <span>{initials(detail)}</span>
            <div><p>{personLabel(detail.personType).toUpperCase()}</p><h2>{detail.name}</h2><small>{[detail.employeeId, detail.designation].filter(Boolean).join(' · ') || '—'}</small></div>
            <StatusBadge status={detail.accountStatus === 'ACTIVE' ? 'Active' : 'Inactive'} tone={detail.accountStatus === 'ACTIVE' ? 'success' : 'neutral'} />
            <div className="employee-management__identity-actions">
              <Button type="button" variant="secondary" onClick={openManagerDialog}>Change Reporting Manager</Button>
              <Button type="button" variant={detail.accountStatus === 'ACTIVE' ? 'danger' : 'secondary'} onClick={openStatusDialog}>{detail.accountStatus === 'ACTIVE' ? 'Deactivate Account' : 'Activate Account'}</Button>
            </div>
          </section>

          <div className="employee-management__summary-grid">
            <section>
              <h3>Profile Summary</h3>
              <dl>{[
                ['Work Email', detail.workEmail],
                ['Personal Email', detail.personalEmail],
                ['Phone Number', detail.phoneNumber],
                ['Employment Type', detail.employmentType],
                ['Start Date', detail.startDate ? formatDate(detail.startDate) : null],
                ['Address', formatAddress(detail)],
              ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
            </section>
            <section>
              <h3>Reports to</h3>
              {detail.reportingManager && <RouteLine orientation="vertical" label="Reporting relationship" steps={[
                ...(detail.reportingHistory || []).filter((assignment) => assignment.effectiveTo).map((assignment) => ({ label: assignment.managerName || 'Previous manager', sublabel: `${formatDate(assignment.effectiveFrom)} to ${formatDate(assignment.effectiveTo)}`, state: 'done' })),
                { label: detail.reportingManager.name, sublabel: 'Current reporting manager', state: 'current' },
              ]} />}
              {detail.reportingManager ? <>
                <strong>{detail.reportingManager.name}</strong>
                <p>Primary Reporting Manager</p>
                <dl>
                  <div><dt>Effective From</dt><dd>{formatDate(detail.reportingManager.effectiveFrom)}</dd></div>
                  <div><dt>Assigned By</dt><dd>{detail.reportingManager.assignedByName || '—'}</dd></div>
                </dl>
              </> : <p>No active reporting manager.</p>}
            </section>
          </div>

          <section className="employee-management__history">
            <h3>Reporting Assignment History</h3>
            <DataTable label="Reporting assignment history"
              columns={[{ key: 'managerName', header: 'Manager' }, { key: 'effectiveFrom', header: 'Effective From' }, { key: 'effectiveTo', header: 'Effective To' }]}
              rows={(detail.reportingHistory || []).map((item, index) => ({
                id: index,
                managerName: item.managerName || '—',
                effectiveFrom: formatDate(item.effectiveFrom),
                effectiveTo: item.effectiveTo ? formatDate(item.effectiveTo) : 'Current',
              }))}
              emptyMessage="No reporting history yet." />
          </section>

          {(detail.personType === 'HR' || (detail.directReports && detail.directReports.length > 0)) && (
            <section className="employee-management__history">
              <h3>Employees Reporting to {detail.firstName || detail.name}</h3>
              <DataTable label="Direct reports"
                columns={[{ key: 'person', header: 'Person', type: 'person' }, { key: 'designation', header: 'Designation' }, { key: 'accountStatus', header: 'Status', type: 'status' }]}
                rows={(detail.directReports || []).map((report) => ({ ...report, id: report.userId, person: { name: report.name, id: report.employeeId } }))}
                emptyMessage="No employees currently report to this person."
                onOpenRow={(report) => openEmployee(report.userId)} />
            </section>
          )}
        </> : null}
    </div>

    <Modal isOpen={dialog === 'manager'} title="Change Reporting Manager" onClose={closeDialog} footer={<>
      <Button type="button" variant="secondary" onClick={closeDialog}>Cancel</Button>
      <Button type="button" variant="primary" disabled={actionBusy || !managerForm.newReportingManagerUserId} onClick={submitManagerChange}>{actionBusy ? 'Saving…' : 'Confirm Change'}</Button>
    </>}>
      {managerOptions.loading ? <Loader label="Loading reporting managers…" /> : <form className="myhr-feature__form" onSubmit={(event) => event.preventDefault()}>
        {managerOptions.error && <p className="myhr-feature__error">{managerOptions.error}</p>}
        <Dropdown name="newReportingManagerUserId" label="New Reporting Manager" value={managerForm.newReportingManagerUserId} onChange={(event) => setManagerForm((current) => ({ ...current, newReportingManagerUserId: event.target.value, effectiveDate: '' }))} options={managerOptions.options} placeholder={managerOptions.options.length ? 'Select HR or Company Admin' : 'No eligible managers available'} />
        <DatePicker name="effectiveDate" label="Effective Date" min={minEffectiveDate} value={managerForm.effectiveDate} onChange={(event) => setManagerForm((current) => ({ ...current, effectiveDate: event.target.value }))} hint={`Must be on or after ${formatDate(minEffectiveDate)}`} />
        <TextArea name="reason" label="Reason for Change (Optional)" value={managerForm.reason} onChange={(event) => setManagerForm((current) => ({ ...current, reason: event.target.value }))} />
        {actionError && <p className="myhr-feature__error">{actionError}</p>}
      </form>}
    </Modal>

    <Modal isOpen={dialog === 'status'} title={nextStatus === 'ACTIVE' ? 'Activate Account?' : 'Deactivate Account?'} onClose={closeDialog} footer={<>
      <Button type="button" variant="secondary" onClick={closeDialog}>Cancel</Button>
      <Button type="button" variant="primary" disabled={actionBusy || (needsReassignment && !reassignManagerUserId)} onClick={submitStatusChange}>{actionBusy ? 'Saving…' : nextStatus === 'ACTIVE' ? 'Activate' : 'Deactivate'}</Button>
    </>}>
      <p>{nextStatus === 'ACTIVE'
        ? `${detail?.name || 'This employee'} will regain access to My HR.`
        : `${detail?.name || 'This employee'} will lose access to My HR until reactivated.`}</p>
      {needsReassignment && (reassignOptions.loading ? <Loader label="Loading eligible managers…" /> : <form className="myhr-feature__form" onSubmit={(event) => event.preventDefault()}>
        <p>{directReportCount} employee{directReportCount === 1 ? '' : 's'} currently report{directReportCount === 1 ? 's' : ''} to {detail.firstName || detail.name}. Choose who takes over before deactivating.</p>
        {reassignOptions.error && <p className="myhr-feature__error">{reassignOptions.error}</p>}
        <Dropdown name="reassignManagerUserId" label="Reassign Employees To" value={reassignManagerUserId} onChange={(event) => setReassignManagerUserId(event.target.value)} options={reassignOptions.options} placeholder={reassignOptions.options.length ? 'Select HR or Company Admin' : 'No eligible managers available'} required />
      </form>)}
      {actionError && <p className="myhr-feature__error">{actionError}</p>}
    </Modal>

    </DetailPanel>
    <Modal className="employee-management__account-modal" isOpen={dialog === 'add'} title={createdAccount ? 'Account Created Successfully' : step === 1 ? 'Create Account' : 'Account Setup / Review'} onClose={closeDialog} footer={createdAccount ? <><Button type="button" variant="secondary" onClick={closeDialog}>Done</Button><Button type="button" variant="primary" onClick={resetAccountFlow}>Create Another Account</Button></> : <><Button type="button" variant="secondary" onClick={closeDialog}>Cancel</Button>{step === 1 ? <Button type="button" variant="primary" onClick={continueToReview}>Continue</Button> : <><Button type="button" variant="secondary" onClick={() => setStep(1)}>Back</Button><Button type="button" variant="primary" loading={submitting} onClick={createAccount}>Create Account</Button></>}</>}>
      {formError && <ValidationMessage message={formError} variant="error" />}
      {createdAccount ? <div className="employee-management__account-success"><dl><div><dt>Name</dt><dd>{createdName}</dd></div><div><dt>Role</dt><dd>{createdAccount.role === 'HR' ? 'HR' : 'Employee'}</dd></div><div><dt>User ID</dt><dd>{createdAccount.loginId}</dd></div><div><dt>Employee ID</dt><dd>{createdAccount.employeeId}</dd></div></dl>{createdAccount.emailDelivered ? <p role="status">Account created. Sign-in details were emailed to {createdAccount.email}. They will be asked to change the temporary password at first sign-in.</p> : <p role="alert" className="myhr-feature__error">Account created, but the welcome email could not be delivered. Sign-in details were not sent. Ask {createdName} to use "Forgot password" with Login ID {createdAccount.loginId} once email is working, or contact support.</p>}</div> : <><div className="employee-management__steps"><span className={step === 1 ? 'active' : ''}>1 Profile Information</span><span className={step === 2 ? 'active' : ''}>2 Account Setup / Review</span></div>{step === 1 ? <form className="myhr-feature__form" onSubmit={(event) => event.preventDefault()} noValidate>
        {role === 'COMPANY_ADMIN' ? <Dropdown name="accountRole" label="Role" value={form.accountRole} onChange={setField('accountRole')} options={ROLE_OPTIONS} placeholder="Select Role" error={errors.accountRole} required /> : <TextInput name="accountRole" label="Role" value="Employee" disabled />}
        <div className="myhr-feature__form-grid"><TextInput name="firstName" label="First Name" value={form.firstName} onChange={setField('firstName')} error={errors.firstName} maxLength={100} required /><TextInput name="lastName" label="Last Name" value={form.lastName} onChange={setField('lastName')} error={errors.lastName} maxLength={100} required /></div>
        <EmailInput name="personalEmail" label="Personal Email" value={form.personalEmail} onChange={setField('personalEmail')} error={errors.personalEmail} required />
        <div className="myhr-feature__form-grid"><PhoneInput name="phoneNumber" label="Phone Number" value={form.phoneNumber} onChange={setField('phoneNumber')} error={errors.phoneNumber} required /><TextInput name="designation" label="Designation" value={form.designation} onChange={setField('designation')} error={errors.designation} placeholder="e.g. Software Engineer" maxLength={120} required /></div>
        <div className="myhr-feature__form-grid"><Dropdown name="employmentType" label="Employment Type" value={form.employmentType} onChange={setField('employmentType')} options={EMPLOYMENT_TYPES} error={errors.employmentType} required /><TextInput name="startDate" label="Start Date" type="date" value={form.startDate} onChange={setField('startDate')} error={errors.startDate} required /></div>
        <div className="employee-management__address-autocomplete">
          <TextInput name="streetAddress" label="Street Address" value={form.streetAddress} onChange={setField('streetAddress')} error={errors.streetAddress} required />
          {addressSearching && <small className="employee-management__address-message">Searching addresses...</small>}
          {!addressSearching && addressMessage && <small className="employee-management__address-message">{addressMessage}</small>}
          {addressSuggestions.length > 0 && <ul className="employee-management__address-suggestions" aria-label="Address suggestions">
            {addressSuggestions.map((suggestion) => <li key={suggestion.id}><button type="button" onClick={() => selectAddressSuggestion(suggestion)}>{suggestion.displayAddress}</button></li>)}
          </ul>}
        </div>
        <div className="myhr-feature__form-grid"><TextInput name="city" label="City" value={form.city} onChange={setField('city')} error={errors.city} maxLength={100} required /><TextInput name="state" label="State" value={form.state} onChange={setField('state')} error={errors.state} maxLength={100} required /></div>
        <TextInput name="zipCode" label="ZIP Code" value={form.zipCode} onChange={setField('zipCode')} error={errors.zipCode} required />
        {targetRole === 'EMPLOYEE' && <Dropdown name="reportingManagerUserId" label="Reporting Manager" value={form.reportingManagerUserId} onChange={setField('reportingManagerUserId')} options={managers.map((manager) => ({ value: manager.userId, label: managerLabel(manager) }))} placeholder={loadingManagers ? 'Loading active HR users…' : 'Select Reporting Manager'} error={errors.reportingManagerUserId} disabled={loadingManagers} required />}
      </form> : <div className="employee-management__account-review"><dl><div><dt>Name</dt><dd>{form.firstName.trim()} {form.lastName.trim()}</dd></div><div><dt>Role</dt><dd>{targetRole === 'HR' ? 'HR' : 'Employee'}</dd></div><div><dt>Personal Email</dt><dd>{form.personalEmail.trim()}</dd></div><div><dt>Phone Number</dt><dd>{form.phoneNumber.trim()}</dd></div><div><dt>Designation</dt><dd>{form.designation.trim()}</dd></div><div><dt>Employment Type</dt><dd>{EMPLOYMENT_TYPES.find((item) => item.value === form.employmentType)?.label}</dd></div><div><dt>Start Date</dt><dd>{formatDate(form.startDate)}</dd></div><div><dt>Address</dt><dd>{form.streetAddress.trim()}, {form.city.trim()}, {form.state.trim()} {form.zipCode.trim()}</dd></div>{targetRole === 'EMPLOYEE' && <div><dt>Reporting Manager</dt><dd>{managerLabel(managers.find((manager) => manager.userId === form.reportingManagerUserId) || { userId: form.reportingManagerUserId })}</dd></div>}<div><dt>User/Employee ID</dt><dd>Auto-generated when account is created</dd></div></dl></div>}</>}
    </Modal>
  </MyHrFeaturePage>;
}
