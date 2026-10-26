import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ActionCard, Button, Card, Dropdown, EmailInput, EmptyState, Loader, Modal, PhoneInput, SearchInput, StatusTag, TextArea, TextInput } from '../../../../components';
import { searchAddressSuggestions } from '../../../../api/addressAutocompleteApi';
import myHrProfileChangeApi from '../../../../api/myHrProfileChangeApi';
import { formatDateTime } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import './profileChanges.css';
import InteractiveProfileBadge from './components/InteractiveProfileBadge';

const FIELD_LABELS = { NAME: 'Name', PERSONAL_EMAIL: 'Personal Email', PHONE_NUMBER: 'Phone Number', ADDRESS: 'Address', DESIGNATION: 'Designation' };

function formatRequestedAt(value) {
  return value ? formatDateTime(value) : '—';
}

// ADDRESS objects must always display streetAddress -> city -> state ->
// zipCode regardless of the key order the current/requested value actually
// arrives in (e.g. Object.entries insertion order can differ between the
// two), so current and requested addresses are directly comparable.
const ADDRESS_FIELD_ORDER = ['streetAddress', 'city', 'state', 'zipCode'];

// Renders a current/requested value as text without inventing data - ADDRESS
// values come back as an object (e.g. { streetAddress, city }), so this shows
// exactly the keys/values present rather than "[object Object]".
function formatFieldValue(value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'object') {
    const orderedKeys = [...ADDRESS_FIELD_ORDER, ...Object.keys(value).filter((key) => !ADDRESS_FIELD_ORDER.includes(key))];
    const parts = orderedKeys
      .filter((key) => value[key] !== null && value[key] !== undefined && value[key] !== '')
      .map((key) => [key, value[key]]);
    return parts.length ? parts.map(([key, v]) => `${key}: ${v}`).join(', ') : '—';
  }
  return String(value);
}

// A single display label for a real reportingManager (from the list/detail
// API), preferring whatever real fields are actually present - never a
// fabricated name. COMPANY_ADMIN reviewers have no EmployeeProfile, so
// employeeId/name are commonly absent and loginId/email are shown instead.
function managerDisplayLabel(reportingManager) {
  if (!reportingManager) return 'Unassigned';
  const identifier = reportingManager.employeeId || reportingManager.loginId;
  if (reportingManager.name && identifier) return `${identifier} · ${reportingManager.name}`;
  if (reportingManager.name) return reportingManager.name;
  if (identifier) return identifier;
  return reportingManager.email || 'Unassigned';
}

// Maps a ProfileChangeRequestResponse (from GET /api/myhr/profile-change-requests)
// onto the shape this page renders. employeeId/employeeName/reportingManager/
// requesterRole are real EmployeeProfile/User information the backend already
// resolved - nothing here is invented, and no follow-up request is made per row.
function mapAdminRequest(request) {
  return {
    id: request.id,
    employeeId: request.employeeId || request.requesterUserId,
    employee: request.employeeName || '',
    fieldName: request.fieldName,
    existingValue: request.existingValue,
    requestedValue: request.requestedValue,
    requestReason: request.requestReason,
    manager: managerDisplayLabel(request.reportingManager),
    requesterRole: request.requesterRole || null,
    requestedAt: formatRequestedAt(request.requestedAt),
    status: request.status,
    processedBy: request.processedByUserId,
    processingComment: request.processingComment,
    processedAt: formatRequestedAt(request.processedAt),
  };
}

// Pending first, then Rejected, then Completed (Approved) - newest first
// within each status group. Sorts the raw API response (status/requestedAt
// as an ISO string) before mapAdminRequest formats requestedAt into a
// display string that's no longer sortable as a date.
const STATUS_ORDER = { PENDING: 0, REJECTED: 1, APPROVED: 2 };
function sortByStatusThenDate(requests) {
  return [...requests].sort((a, b) => {
    const statusDiff = (STATUS_ORDER[a.status] ?? 3) - (STATUS_ORDER[b.status] ?? 3);
    if (statusDiff !== 0) return statusDiff;
    return new Date(b.requestedAt) - new Date(a.requestedAt);
  });
}

// DESIGNATION is never self-service (locked V1 scope) - excluded from the
// request field dropdown for every role, not just EMPLOYEE.
const REQUESTABLE_FIELDS = Object.entries(FIELD_LABELS)
  .filter(([field]) => field !== 'DESIGNATION')
  .map(([value, label]) => ({ value, label }));

function normalizeUsPhone(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  let digits = raw.replace(/\D/g, '');
  if (!digits) return '';
  if (raw.includes('+')) {
    if (digits.length !== 11 || !digits.startsWith('1')) return '';
    digits = digits.slice(1);
  } else if (digits.length === 11 && digits.startsWith('1')) {
    digits = digits.slice(1);
  }
  return digits.length === 10 ? `+1${digits}` : '';
}

// Field-specific structured inputs, never a generic JSON/object input:
// NAME collects firstName/lastName, ADDRESS collects its four allowed keys,
// PERSONAL_EMAIL/PHONE_NUMBER collect a single typed scalar value.
function RequestForm({ onSubmit, showCancel = false }) {
  const [fieldName, setFieldName] = useState('');
  const [scalarValue, setScalarValue] = useState('');
  const [firstName, setFirstName] = useState(''); const [lastName, setLastName] = useState('');
  const [streetAddress, setStreetAddress] = useState(''); const [city, setCity] = useState('');
  const [state, setState] = useState(''); const [zipCode, setZipCode] = useState('');
  const [requestReason, setRequestReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});
  const [addressSuggestions, setAddressSuggestions] = useState([]);
  const [addressSearching, setAddressSearching] = useState(false);
  const addressRequest = useRef(0);
  const suppressAddressSearch = useRef(false);

  useEffect(() => {
    if (suppressAddressSearch.current) { suppressAddressSearch.current = false; return undefined; }
    if (fieldName !== 'ADDRESS' || streetAddress.trim().length < 3) { setAddressSuggestions([]); return undefined; }
    const sequence = ++addressRequest.current;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setAddressSearching(true);
      searchAddressSuggestions(streetAddress, { signal: controller.signal })
        .then((items) => { if (sequence === addressRequest.current) setAddressSuggestions(items); })
        .catch((requestError) => { if (requestError?.name !== 'AbortError' && sequence === addressRequest.current) setAddressSuggestions([]); })
        .finally(() => { if (sequence === addressRequest.current) setAddressSearching(false); });
    }, 400);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [fieldName, streetAddress]);

  // Only relevant when the form is an always-visible inline panel (HR's own
  // Request a Change view) rather than a dismissable modal (EMPLOYEE) -
  // there's nothing to close, so Cancel just clears the fields back out.
  const resetForm = () => {
    setFieldName(''); setScalarValue('');
    setFirstName(''); setLastName('');
    setStreetAddress(''); setCity(''); setState(''); setZipCode('');
    setRequestReason(''); setError(''); setFieldErrors({}); setAddressSuggestions([]);
  };

  const updateScalarValue = (value) => {
    setScalarValue(value);
    setFieldErrors((current) => ({ ...current, scalarValue: undefined }));
    setError('');
  };

  const buildRequestedValue = () => {
    if (fieldName === 'NAME') return { firstName: firstName.trim(), lastName: lastName.trim() };
    if (fieldName === 'ADDRESS') {
      const address = {};
      if (streetAddress.trim()) address.streetAddress = streetAddress.trim();
      if (city.trim()) address.city = city.trim();
      if (state.trim()) address.state = state.trim();
      if (zipCode.trim()) address.zipCode = zipCode.trim();
      return address;
    }
    return fieldName === 'PHONE_NUMBER' ? normalizeUsPhone(scalarValue) : scalarValue.trim();
  };

  const validate = () => {
    const next = {};
    const personName = /^[\p{L}\p{M}]+(?:[ '’][\p{L}\p{M}]+)*$/u;
    if (!fieldName) next.fieldName = 'Select a field.';
    if (fieldName === 'NAME') {
      if (!firstName.trim()) next.firstName = 'First Name is required.';
      else if (!personName.test(firstName.trim())) next.firstName = 'Use letters, spaces, hyphens, or apostrophes only.';
      if (!lastName.trim()) next.lastName = 'Last Name is required.';
      else if (!personName.test(lastName.trim())) next.lastName = 'Use letters, spaces, hyphens, or apostrophes only.';
    } else if (fieldName === 'PERSONAL_EMAIL' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(scalarValue.trim())) next.scalarValue = 'Enter a valid email address.';
    else if (fieldName === 'PHONE_NUMBER' && !normalizeUsPhone(scalarValue)) next.scalarValue = 'Enter a valid 10-digit phone number.';
    else if (fieldName === 'ADDRESS') {
      if (!streetAddress.trim() && !city.trim() && !state.trim() && !zipCode.trim()) next.streetAddress = 'Enter at least one address field.';
    }
    if (requestReason.trim().length > 1000) next.requestReason = 'Reason must be 1,000 characters or fewer.';
    return next;
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    const nextErrors = validate();
    setFieldErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    setSubmitting(true);
    setError('');
    onSubmit({ fieldName, requestedValue: buildRequestedValue(), requestReason: requestReason.trim() || undefined })
      .catch((submitError) => {
        setError(submitError?.response?.data?.message || 'Something went wrong submitting this request. Please try again.');
      })
      .finally(() => setSubmitting(false));
  };

  return <form className="myhr-feature__form" onSubmit={handleSubmit}>
    <Dropdown name="fieldName" label="Field Name" options={REQUESTABLE_FIELDS} value={fieldName} onChange={(event) => { setFieldName(event.target.value); setFieldErrors({}); }} error={fieldErrors.fieldName} />
    {fieldName === 'NAME' && <>
      <TextInput name="firstName" label="First Name" value={firstName} onChange={(event) => setFirstName(event.target.value)} error={fieldErrors.firstName} maxLength={100} />
      <TextInput name="lastName" label="Last Name" value={lastName} onChange={(event) => setLastName(event.target.value)} error={fieldErrors.lastName} maxLength={100} />
    </>}
    {fieldName === 'ADDRESS' && <>
      <div className="profile-changes__address-autocomplete"><TextInput name="streetAddress" label="Street Address" value={streetAddress} onChange={(event) => setStreetAddress(event.target.value)} error={fieldErrors.streetAddress} />
        {addressSearching && <small>Searching addresses…</small>}
        {addressSuggestions.length > 0 && <ul className="profile-changes__address-suggestions">{addressSuggestions.map((suggestion) => <li key={suggestion.id}><button type="button" onClick={() => { addressRequest.current += 1; suppressAddressSearch.current = true; setStreetAddress(suggestion.streetAddress || streetAddress); setCity(suggestion.city || city); setState(suggestion.state || state); setZipCode(suggestion.zipCode || zipCode); setAddressSuggestions([]); }}>{suggestion.displayAddress}</button></li>)}</ul>}
      </div>
      <TextInput name="city" label="City" value={city} onChange={(event) => setCity(event.target.value)} />
      <TextInput name="state" label="State" value={state} onChange={(event) => setState(event.target.value)} />
      <TextInput name="zipCode" label="ZIP Code" value={zipCode} onChange={(event) => setZipCode(event.target.value)} />
    </>}
    {fieldName === 'PERSONAL_EMAIL' && <EmailInput name="requestedValue" label="Requested Value" value={scalarValue} onChange={(event) => updateScalarValue(event.target.value)} error={fieldErrors.scalarValue} />}
    {fieldName === 'PHONE_NUMBER' && <PhoneInput name="requestedValue" label="Requested Value" value={scalarValue} onChange={(event) => updateScalarValue(event.target.value)} error={fieldErrors.scalarValue} showCountryCode={false} />}
    <TextArea name="requestReason" label="Reason for Request (Optional)" value={requestReason} onChange={(event) => setRequestReason(event.target.value)} error={fieldErrors.requestReason} maxLength={1000} />
    {error && <p className="profile-changes__feedback" role="alert">{error}</p>}
    <div className="profile-changes__form-actions">
      {showCancel && <Button type="button" variant="secondary" onClick={resetForm} disabled={submitting}>Cancel</Button>}
      <Button type="submit" variant="primary" disabled={submitting}>{submitting ? 'Submitting…' : 'Submit Request'}</Button>
    </div>
  </form>;
}

// Same circular line-icon language as the role landing page's dashboard
// action cards (MyHrRoleLandingPage) - reused here rather than imported
// since these two icons are specific to this page's own two cards.
const ACTION_CARD_ICON_PATHS = {
  request: ['M4 3h9l3 3v11H4Z', 'M13 3v4h4M7 11h6M7 14h4'],
  assigned: ['M10 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z', 'M4 17a6 6 0 0 1 12 0'],
};

// HR lands on two action cards (Request a Change / Assigned Requests) instead
// of both sections at once - COMPANY_ADMIN and EMPLOYEE have only one relevant
// section each, so they skip straight to it. Compact-to-medium tiles, not
// full-width feature panels: icon + title share a header row, description
// and the Open action sit below at the same left edge.
function ProfileChangeActionIcon({ type }) {
  return <svg viewBox="0 0 20 20">{ACTION_CARD_ICON_PATHS[type].map((d) => <path key={d} d={d} />)}</svg>;
}

// COMPANY_ADMIN/HR reviewer's view of a request, sourced entirely from
// ProfileChangeRequestDetailResponse. The backend deliberately minimizes
// `requester` to {userId, employeeId, name, role} - no address/personal
// email/phone/designation - so a reviewer never sees more of someone else's
// profile than the field actually under review already shows below.
function AdminRequestReview({ detail }) {
  const requester = detail.requester;
  const managerLabel = managerDisplayLabel(detail.reportingManager);
  return (
    <div className="profile-changes__review">
      <div className="profile-changes__modal-badge-wrap">
        <InteractiveProfileBadge
          employeeId={requester?.employeeId || detail.employeeId || '—'}
          employeeName={requester?.name || requester?.employeeId || 'Employee'}
          fieldName={FIELD_LABELS[detail.fieldName] || detail.fieldName}
          existingValue={formatFieldValue(detail.existingValue)}
          requestedValue={formatFieldValue(detail.requestedValue)}
          status={detail.status}
          manager={managerLabel}
          requestedAt={formatRequestedAt(detail.requestedAt)}
          reason={detail.requestReason}
          processingComment={detail.processingComment}
          processedAt={formatRequestedAt(detail.processedAt)}
          role={requester?.role || 'Employee'}
        />
      </div>
    </div>
  );
}

function OwnRequestDetail({ request }) {
  return (
    <div className="profile-changes__review">
      <div className="profile-changes__modal-badge-wrap">
        <InteractiveProfileBadge
          employeeId={request.employeeId}
          employeeName={request.employee || request.employeeId}
          fieldName={FIELD_LABELS[request.fieldName] || request.fieldName}
          existingValue={formatFieldValue(request.existingValue)}
          requestedValue={formatFieldValue(request.requestedValue)}
          status={request.status}
          manager={request.manager}
          requestedAt={request.requestedAt}
          reason={request.requestReason}
          processingComment={request.processingComment}
          processedAt={request.processedAt}
          role={request.requesterRole || 'Employee'}
        />
      </div>
    </div>
  );
}

export default function ProfileChangeRequestsPage({ role, defaultHrView = null }) {
  const isAdmin = role === 'COMPANY_ADMIN';
  // COMPANY_ADMIN reviews every request in the organization; HR reviews only
  // requests assigned to them (their direct reports' active reporting
  // manager) - the backend enforces the same scoping independently.
  const isReviewer = isAdmin || role === 'HR';
  const isHr = role === 'HR';
  const [searchParams] = useSearchParams();
  // A "Profile change request submitted" notification's actionUrl carries
  // ?view=assigned so HR lands straight past the card menu into the review
  // queue that notification is actually about, instead of the generic
  // two-card landing screen (see ProfileChangeRequestService.assignedReviewTarget
  // on the backend). Company Admin has no card menu, so this is a no-op there.
  const requestedView = searchParams.get('view') === 'assigned' ? 'assigned' : null;
  const requestedId = searchParams.get('requestId');
  // HR is the only role with both a request-of-their-own and a review queue,
  // so HR alone gets a card menu to choose between them; COMPANY_ADMIN and
  // EMPLOYEE each have exactly one relevant section and skip straight to it.
  const [hrView, setHrView] = useState(defaultHrView || requestedView); // null | 'request' | 'assigned'
  const [query, setQuery] = useState(''); const [status, setStatus] = useState('ALL'); const [requestType, setRequestType] = useState('ALL');
  const [reviewPage, setReviewPage] = useState(1); const [historyPage, setHistoryPage] = useState(1);
  const [showRequest, setShowRequest] = useState(false); const [submitted, setSubmitted] = useState(null); const [selected, setSelected] = useState(null); const [selectedIsOwn, setSelectedIsOwn] = useState(false); const [decision, setDecision] = useState(null); const [processingComment, setProcessingComment] = useState('');
  // True only when isReviewer (COMPANY_ADMIN, or HR) is actually reviewing
  // someone else's request - HR is also a requester, so isReviewer alone
  // can't tell "assigned to me to review" apart from "my own submission".
  const isReviewingOther = isReviewer && !selectedIsOwn;

  // Reviewer queue, loaded from the real API - organization-wide for
  // COMPANY_ADMIN, assigned-to-them only for HR. Never populated for
  // EMPLOYEE, which uses myRequests/loadMyRequests below instead, scoped to
  // their own submissions only.
  const [reviewRequests, setReviewRequests] = useState([]);
  const [reviewLoadState, setReviewLoadState] = useState(isReviewer ? 'loading' : 'idle');

  const loadReviewRequests = () => {
    setReviewLoadState('loading');
    myHrProfileChangeApi.listForAdmin()
      .then((response) => {
        setReviewRequests(sortByStatusThenDate(response.data?.data || []).map(mapAdminRequest));
        setReviewLoadState('success');
      })
      .catch(() => {
        setReviewLoadState('error');
      });
  };

  useEffect(() => {
    if (!isReviewer) return;
    loadReviewRequests();
  }, [isReviewer]);

  const visible = useMemo(() => {
    const sourceRequests = isReviewer ? reviewRequests : [];
    return sourceRequests.filter((request) => `${request.employeeId} ${request.employee} ${request.id}`.toLowerCase().includes(query.toLowerCase()) && (status === 'ALL' || request.status === status) && (requestType === 'ALL' || request.requesterRole === requestType));
  }, [isReviewer, reviewRequests, query, status, requestType]);
  const visiblePage = visible.slice((reviewPage - 1) * 10, reviewPage * 10);
  const reviewPages = Math.ceil(visible.length / 10);
  useEffect(() => { setReviewPage(1); }, [query, status, requestType]);
  const isEmployee = role === 'EMPLOYEE';
  const canRequestOwn = role === 'EMPLOYEE' || role === 'HR';
  const showCards = isHr && !hrView;
  const showRequestSection = canRequestOwn && (!isHr || hrView === 'request');
  const showAssignedSection = !isEmployee && (!isHr || hrView === 'assigned');

  // EMPLOYEE/HR's own submission history, loaded from the real
  // GET /api/myhr/profile-change-requests/mine API - never populated for
  // COMPANY_ADMIN, which has no EmployeeProfile of its own to submit for.
  const [myRequests, setMyRequests] = useState([]);
  const [myLoadState, setMyLoadState] = useState(canRequestOwn ? 'loading' : 'idle');
  const historyPageItems = myRequests.slice((historyPage - 1) * 10, historyPage * 10);
  const historyPages = Math.ceil(myRequests.length / 10);

  const loadMyRequests = () => {
    setMyLoadState('loading');
    myHrProfileChangeApi.listMine()
      .then((response) => {
        setMyRequests(sortByStatusThenDate(response.data?.data || []).map(mapAdminRequest));
        setMyLoadState('success');
      })
      .catch(() => {
        setMyLoadState('error');
      });
  };

  useEffect(() => {
    if (!canRequestOwn) return;
    loadMyRequests();
  }, [canRequestOwn]);

  // Deep-links a notification straight to the specific request it's about
  // (?requestId=...) once its authorized, already-scoped list has loaded -
  // never by fetching the id directly, so a request outside this user's
  // reviewer assignment/organization/own history simply never matches and
  // the page is left showing its normal (already-correct) section instead
  // of a blank or broken state. Checks the reviewer queue first (a
  // "submitted" notification's id lives there), then own history (an
  // approval/rejection notification's id - relevant for HR, who is both a
  // reviewer and a requester). Runs once per mount; never re-opens a request
  // the user has already closed after a list refresh (e.g. after deciding it).
  const autoOpenedRequestRef = useRef(false);
  useEffect(() => {
    if (!requestedId || autoOpenedRequestRef.current) return;
    const reviewerReady = !isReviewer || reviewLoadState === 'success';
    const ownReady = !canRequestOwn || myLoadState === 'success';
    if (!reviewerReady || !ownReady) return;

    const reviewMatch = isReviewer ? reviewRequests.find((request) => request.id === requestedId) : null;
    if (reviewMatch) {
      setSelectedIsOwn(false);
      setSelected(reviewMatch);
      setHrView((current) => current || 'assigned');
    } else {
      const ownMatch = canRequestOwn ? myRequests.find((request) => request.id === requestedId) : null;
      if (ownMatch) {
        setSelectedIsOwn(true);
        setSelected(ownMatch);
      }
    }
    autoOpenedRequestRef.current = true;
  }, [requestedId, isReviewer, reviewLoadState, reviewRequests, canRequestOwn, myLoadState, myRequests]);

  const submitOwnRequest = ({ fieldName, requestedValue, requestReason }) =>
    myHrProfileChangeApi.create(fieldName, requestedValue, requestReason).then((response) => {
      setSubmitted({ fieldName });
      setShowRequest(false);
      loadMyRequests();
      return response;
    });

  // Real request detail for COMPANY_ADMIN's review modal (id/status/employee/
  // manager/current+requested value/processing info) - never the PROFILE fixture.
  const [detail, setDetail] = useState(null);
  const [detailLoadState, setDetailLoadState] = useState('idle');
  const [decisionState, setDecisionState] = useState('idle'); // 'idle' | 'submitting' | 'error'
  const [decisionError, setDecisionError] = useState('');
  const [decisionSuccess, setDecisionSuccess] = useState('');

  const loadDetail = (requestId) => {
    setDetailLoadState('loading');
    myHrProfileChangeApi.getDetail(requestId)
      .then((response) => {
        setDetail(response.data?.data || null);
        setDetailLoadState('success');
      })
      .catch(() => {
        setDetailLoadState('error');
      });
  };

  useEffect(() => {
    if (!isReviewingOther || !selected) {
      setDetail(null);
      setDetailLoadState('idle');
      setDecisionSuccess('');
      return;
    }
    loadDetail(selected.id);
  }, [isReviewingOther, selected]);

  const closeReviewModal = () => {
    setSelected(null);
    setSelectedIsOwn(false);
    setDetail(null);
    setDetailLoadState('idle');
    setDecisionState('idle');
    setDecisionError('');
    setDecisionSuccess('');
  };

  const openDecision = (nextDecision) => {
    setDecision(nextDecision);
    setDecisionError('');
  };

  const closeDecisionModal = () => {
    if (decisionState === 'submitting') return;
    setDecision(null);
    setProcessingComment('');
    setDecisionState('idle');
    setDecisionError('');
  };

  const confirmDecision = () => {
    if (!isReviewingOther || !selected || decisionState === 'submitting') return;
    setDecisionState('submitting');
    setDecisionError('');
    const call = decision === 'APPROVED'
      ? myHrProfileChangeApi.approve(selected.id, processingComment)
      : myHrProfileChangeApi.reject(selected.id, processingComment);
    call
      .then((response) => {
        const updated = response.data?.data;
        // Reflect the decision on the already-loaded detail immediately,
        // without a second GET - the response already carries every
        // decision field the review modal needs.
        setDetail((current) => (current && updated ? {
          ...current,
          status: updated.status,
          processedByUserId: updated.processedByUserId,
          processingComment: updated.processingComment,
          processedAt: updated.processedAt,
        } : current));
        setDecisionSuccess(decision === 'APPROVED' ? 'Change applied.' : 'Request rejected.');
        loadReviewRequests();
        setDecision(null);
        setProcessingComment('');
        setDecisionState('idle');
      })
      .catch(() => {
        setDecisionState('error');
        setDecisionError(`Something went wrong ${decision === 'APPROVED' ? 'applying' : 'rejecting'} this change. Please try again.`);
      });
  };

  const reviewFooter = () => {
    if (!selected) return null;
    if (isReviewingOther && detailLoadState !== 'success') {
      return <Button type="button" variant="secondary" onClick={closeReviewModal}>Close</Button>;
    }
    const currentStatus = isReviewingOther ? detail?.status : selected.status;
    return <>
      <Button type="button" variant="secondary" onClick={closeReviewModal}>Back</Button>
      {/* Approve/reject is a reviewer-only action - gated on isReviewingOther,
          not just a PENDING status check, so HR viewing their own pending
          request (selectedIsOwn) never sees these, however this footer is
          reached. */}
      {isReviewingOther && currentStatus === 'PENDING' && <>
        <Button type="button" variant="danger" onClick={() => openDecision('REJECTED')}>Reject Request</Button>
        <Button type="button" variant="primary" onClick={() => openDecision('APPROVED')}>Apply Change</Button>
      </>}
    </>;
  };

  const reviewBody = () => {
    if (!selected) return null;
    if (isReviewingOther) {
      if (detailLoadState === 'loading') return <Loader label="Loading request details…" />;
      if (detailLoadState === 'error') return <EmptyState title="Couldn't load request details" description="Something went wrong loading this request." action={<Button type="button" variant="secondary" onClick={() => loadDetail(selected.id)}>Try Again</Button>} />;
      if (!detail) return null;
      return <>
        {decisionSuccess && <p className="profile-changes__feedback" role="status">{decisionSuccess}</p>}
        <AdminRequestReview detail={detail} />
      </>;
    }
    // Viewing your own submitted request (EMPLOYEE always, or HR when it's
    // their own) - every field is already present on the list row, so no
    // extra request-detail API call is made here at all.
    return <OwnRequestDetail request={selected} />;
  };

  const dashboardBackRoute = isEmployee ? '/myhr/employee/dashboard' : isAdmin ? '/myhr/admin/dashboard' : '/myhr/hr/dashboard';
  const employeeRequestAction = isEmployee ? (
    <Button type="button" variant="primary" className="profile-changes__request-button" onClick={() => setShowRequest(true)}>
      Request a Change
    </Button>
  ) : undefined;

  const pageTitle = role === 'HR' || role === 'COMPANY_ADMIN' ? undefined : 'Profile Change Requests';
  const pageDescription = isEmployee ? null : role === 'HR' || role === 'COMPANY_ADMIN' ? undefined : 'Review profile changes across the organization.';

  return <MyHrFeaturePage className="profile-changes__feature" title={pageTitle} description={pageDescription} backTo={dashboardBackRoute} headerAction={employeeRequestAction}>
    {showCards && <div className="profile-changes__cards">
      <ActionCard icon={<ProfileChangeActionIcon type="request" />} eyebrow="My profile" title="Request a Change" description="Submit and track your own profile change requests." onClick={() => setHrView('request')} />
      <ActionCard icon={<ProfileChangeActionIcon type="assigned" />} eyebrow="Review queue" title="Assigned Requests" description="Review and act on requests assigned to you." onClick={() => setHrView('assigned')} />
    </div>}
    {/* HR's own request flow is an always-visible inline form (no trigger
        button/modal) - EMPLOYEE keeps the existing button + modal below. */}
    {showRequestSection && isHr && <>
      <div className="profile-changes__section-head"><div><h2>Request a Profile Change</h2><p>Submit a change to your own profile for your reporting manager to review.</p></div></div>
      <Card title="Request Form" className="profile-changes__form-card"><RequestForm onSubmit={submitOwnRequest} showCancel /></Card>
    </>}
    {showRequestSection && !isHr && !isEmployee && (
      <div className="profile-changes__section-head"><div><h2>My Requests</h2><p>Controlled changes remain pending until processed.</p></div><Button type="button" variant="primary" className="profile-changes__button--sm" onClick={() => setShowRequest(true)}>Request a Change</Button></div>
    )}
    {submitted && <p className="profile-changes__feedback"><StatusTag status="Pending" tone="warning" /> Request for {FIELD_LABELS[submitted.fieldName]} submitted.</p>}
    {showRequestSection && <section className="profile-changes__history">
      <h2>{isHr ? 'My Previous Requests' : 'Request History'}</h2>
      {isHr && <p className="profile-changes__history-subtitle">Your submitted profile change requests.</p>}
      {myLoadState === 'loading' && <Loader label="Loading your requests…" />}
      {myLoadState === 'error' && <EmptyState title="Couldn't load your requests" description="Something went wrong loading your profile change requests." action={<Button type="button" variant="secondary" onClick={loadMyRequests}>Try Again</Button>} />}
      {myLoadState === 'success' && myRequests.length === 0 && <EmptyState title={isHr ? "You haven't submitted any profile change requests yet." : 'No profile change requests available.'} />}
      {myLoadState === 'success' && historyPageItems.map((request) => <RequestCard key={request.id} request={request} onReview={(clicked) => { setSelectedIsOwn(true); setSelected(clicked); }} employeeView />)}
      {myLoadState === 'success' && historyPages > 1 && <MyHrPagination currentPage={historyPage} totalPages={historyPages} onPageChange={setHistoryPage} />}
    </section>}
    {showAssignedSection && <section className="profile-changes__assigned"><div className="profile-changes__section-head"><div><h2>{role === 'HR' ? 'Assigned Requests' : 'Organization Requests'}</h2><p>Search and filter requests that require review.</p></div></div>
      <div className="profile-changes__filters">
        <div className="wp-field profile-changes__search-field"><span className="wp-form-label" aria-hidden="true">Search</span><SearchInput value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Employee ID, name, or request ID" /></div>
        <Dropdown name="requestStatus" label="Status" value={status} onChange={(event) => setStatus(event.target.value)} options={[{ value: 'ALL', label: 'All statuses' }, { value: 'PENDING', label: 'Pending' }, { value: 'APPROVED', label: 'Completed' }, { value: 'REJECTED', label: 'Rejected' }]} />
        {isAdmin && <Dropdown name="requestType" label="Request Type" value={requestType} onChange={(event) => setRequestType(event.target.value)} options={[{ value: 'ALL', label: 'All requests' }, { value: 'EMPLOYEE', label: 'Employee requests' }, { value: 'HR', label: 'HR requests' }]} />}
        <Button type="button" variant="secondary" className="profile-changes__clear-btn" onClick={() => { setQuery(''); setStatus('ALL'); setRequestType('ALL'); }} disabled={!query && status === 'ALL' && requestType === 'ALL'}>Clear filters</Button>
      </div>
      <div className="profile-changes__list">
        {isReviewer && reviewLoadState === 'loading' && <Loader label="Loading requests…" />}
        {isReviewer && reviewLoadState === 'error' && <EmptyState title="Couldn't load requests" description="Something went wrong loading profile change requests." action={<Button type="button" variant="secondary" onClick={loadReviewRequests}>Try Again</Button>} />}
        {(!isReviewer || reviewLoadState === 'success') && visible.length === 0 && <EmptyState title="No requests found" description="No profile change requests match your filters." />}
        {(!isReviewer || reviewLoadState === 'success') && visiblePage.map((request) => <RequestCard key={request.id} request={request} onReview={(clicked) => { setSelectedIsOwn(false); setSelected(clicked); }} />)}
        {(!isReviewer || reviewLoadState === 'success') && reviewPages > 1 && <MyHrPagination currentPage={reviewPage} totalPages={reviewPages} onPageChange={setReviewPage} />}
      </div>
    </section>}
    <Modal isOpen={showRequest} title="Request a Profile Change" onClose={() => setShowRequest(false)}><RequestForm onSubmit={submitOwnRequest} /></Modal>
    <Modal className="profile-changes__modal" isOpen={Boolean(selected)} title={isReviewingOther ? 'Review Profile Change Request' : 'Profile Change Request Details'} onClose={closeReviewModal} footer={reviewFooter()}>
      {reviewBody()}
    </Modal>
    <Modal isOpen={Boolean(decision)} title={decision === 'APPROVED' ? 'Apply Profile Change?' : 'Reject Profile Change?'} onClose={closeDecisionModal} footer={<><Button type="button" variant="secondary" onClick={closeDecisionModal} disabled={decisionState === 'submitting'}>Cancel</Button><Button type="button" variant="primary" onClick={confirmDecision} disabled={!isReviewingOther || decisionState === 'submitting'}>{decisionState === 'submitting' ? 'Processing…' : `Confirm ${decision === 'APPROVED' ? 'Change' : 'Rejection'}`}</Button></>}>
      <p>{decision === 'APPROVED' ? 'The profile will be updated and the backend status set to APPROVED.' : 'The profile will remain unchanged and the backend status set to REJECTED.'}</p>
      <TextArea name="processingComment" label="Processing Comment (optional)" value={processingComment} onChange={(event) => setProcessingComment(event.target.value)} disabled={decisionState === 'submitting'} />
      {decisionError && <p className="profile-changes__feedback" role="alert">{decisionError}</p>}
    </Modal>
  </MyHrFeaturePage>;
}

// One shared card for both "My Previous Requests" (own history, employeeView)
// and "Assigned Requests" (reviewer queue) - same border/spacing/typography/
// status badge/field grid/action placement either way, so the two sections
// read as one component rather than two different designs. employeeView
// forces the action to always read "View Details" (a requester never gets
// Approve/Reject, regardless of status); the reviewer queue shows "Review
// Request" only while a request is still PENDING.
function RequestCard({ request, onReview, employeeView = false }) {
  const statusLabel = request.status === 'APPROVED' ? 'Completed' : request.status;
  const statusTone = request.status === 'APPROVED' ? 'success' : request.status === 'REJECTED' ? 'error' : 'warning';
  const employeeLabel = request.employee ? `${request.employeeId} · ${request.employee}` : request.employeeId;
  return (
    <article className="profile-changes__request">
      <div className="profile-changes__request-top">
        <div className="profile-changes__employee-head">
          <strong className="profile-changes__employee-title">{employeeLabel}</strong>
        </div>
        <StatusTag status={statusLabel} tone={statusTone} />
      </div>

      <div className="profile-changes__request-grid">
        <div>
          <span className="profile-changes__request-label">Field</span>
          <strong>{FIELD_LABELS[request.fieldName] || request.fieldName}</strong>
        </div>
        <div>
          <span className="profile-changes__request-label">Submitted</span>
          <span>{request.requestedAt}</span>
        </div>
        <Button
          type="button"
          variant="secondary"
          className="profile-changes__button--sm"
          onClick={() => onReview(request)}
        >
          {employeeView || request.status !== 'PENDING' ? 'View Details' : 'Review Request'}
        </Button>
      </div>
      <div className="profile-changes__request-meta">
        <span>Reporting Manager: <b>{request.manager}</b></span>
      </div>
    </article>
  );
}
