import DocumentRoute from '../../../components/DocumentRoute';
import ApprovalBanner from '../ApprovalBanner';
import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import jsPDF from 'jspdf';
import { Button, Checkbox, Dropdown, EmailInput, TextArea, TextInput, Toast } from '../../../../../components';
import myHrDocumentRequestApi from '../../../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../../../api/myHrEmployeeApi';
import resolveFileUrl from '../../../../../api/resolveFileUrl';
import { formatDateOnly } from '../../../../../utils/dateFormat';
import endWithPeriod from '../../../../../utils/endWithPeriod';
import { formatDisplayLabel } from '../../../../../utils/myHrDisplayLabel';
import { getMyHrSession } from '../../../../../auth/myHrSession';
import MyHrFeaturePage from '../../MyHrFeaturePage';
import SignatureField from '../SignatureField';
import { COMPANY_INFO } from '../offerLetterTemplates';
import { DOCUMENT_WORKFLOW_TYPES, matchesRequestTitle } from '../documentWorkflowTypes';
import { DOCUMENT_TYPE_CONFIGS } from './documentTypeConfigs';
import { setWorkflowState } from './documentWorkflowState';
import { fetchLetterheadLogo } from './letterheadLogo';
import { approverLabel } from '../offerLetterState';
import DocumentFulfillmentEmailComposer from '../DocumentFulfillmentEmailComposer';
import '../documentRequests.css';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PERSON_NAME_PATTERN = /^[\p{L}\p{M}]+(?: [\p{L}\p{M}]+)*$/u;
const LETTERS_AND_SPACES = /[^\p{L}\p{M} ]/gu;
const today = () => new Date().toISOString().slice(0, 10);

/**
 * Generic "request -> prepare -> template -> preview -> send" workflow used
 * by every document type except Offer Letter (which keeps its own dedicated
 * page - see OfferLetterPreparePage). Document-specific content lives in
 * documentTypeConfigs.js as data (fields + a preview builder); this shell
 * owns everything that's the same across types: fetching the real request,
 * showing its context, step navigation, the template/preview card, the
 * signature prototype, and sending (a generated PDF, via the real fulfill()
 * call so the request is marked completed for the employee to see/download).
 */
export default function DocumentPreparationPage({ role, documentTypeId }) {
  const navigate = useNavigate();
  const { requestId } = useParams();
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : '/myhr/hr';
  const detailPath = `${base}/document-requests/${requestId}`;

  const workflowType = DOCUMENT_WORKFLOW_TYPES.find((type) => type.id === documentTypeId);
  const config = DOCUMENT_TYPE_CONFIGS[documentTypeId];

  // Only used as the useState initializer (documentTypeId is fixed for the
  // life of this route instance), so no memoization is needed here.
  const initialForm = {
    employeeName: '', employeeEmail: '', employeeId: '', issueDate: today(),
    signaturePreviewUrl: '',
    // Only meaningful for OTHER, where HR decides per request whether the
    // employee must sign and return a signed copy (documentWorkflowTypes.js
    // marks OTHER as signatureConfigurable instead of a fixed default).
    otherRequiresSignature: false, otherRequiresSignedReturn: false,
    ...Object.fromEntries(config.fields.map((field) => [field.name, ''])),
  };

  const [request, setRequest] = useState(null);
  const [organization, setOrganization] = useState(null);
  const [logoAsset, setLogoAsset] = useState(null);
  const [assignedHr, setAssignedHr] = useState(null);
  const [employeeProfile, setEmployeeProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [step, setStep] = useState(1);
  const [form, setForm] = useState(initialForm);
  const [errors, setErrors] = useState({});
  const [sent, setSent] = useState(false);
  const [emailDelivery, setEmailDelivery] = useState(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');
  // HR cannot send a document to the employee directly: it goes to the Company Admin for approval first. A Company Admin is
  // the approver, so an Admin keeps sending directly. approval is the server's state for this document (null until loaded).
  // HR signs every generated employee-delivery document before it enters the separate Company Admin review workflow -
  // except a letter signed by the company admin (the Organization Settings admin account): it is never signed here. It goes
  // through approval, where the server applies the signatory's stored signature (a signatory preparing it themselves signs it directly).
  const signedBySignatory = Boolean(config.signedByAuthorizedSignatory);
  const approvalMode = role === 'HR' || signedBySignatory;
  const [approval, setApproval] = useState(null);
  const [approvedPreviewUrl, setApprovedPreviewUrl] = useState('');
  const approvalStatus = approvalMode ? (approval?.approvalStatus || 'NONE') : 'NONE';

  useEffect(() => {
    setLoading(true); setLoadError('');
    myHrDocumentRequestApi.get(requestId).then((response) => {
      const data = response.data.data;
      if (!matchesRequestTitle(workflowType, data.requestTitle)) {
        setRequest(null);
        setLoadError(`This request is not a ${workflowType.label.toLowerCase()} request.`);
        return;
      }
      const inApproval = approvalMode && ['SUBMITTED', 'APPROVED', 'RETURNED_FOR_CORRECTION', 'FULFILLED'].includes(data.status);
      if (data.status !== 'PENDING' && !inApproval) {
        setRequest(null);
        setLoadError('Only pending document requests can be prepared.');
        return;
      }
      setRequest(data);
      setForm((current) => ({
        ...current,
        employeeName: data.requestedByName || '',
        employeeId: data.requestedByLoginId || '',
      }));
    }).catch(() => setLoadError('Document request could not be loaded.'))
      .finally(() => setLoading(false));
  }, [requestId, workflowType, approvalMode]);

  // The Admin approval state of this document. A document that was already submitted keeps the details HR submitted, so a
  // rejected one can be corrected without re-entering them (the signature image is never stored: HR re-adds it).
  useEffect(() => {
    if (!approvalMode || !request) return undefined;
    let active = true;
    Promise.resolve(myHrDocumentRequestApi.getDocumentApproval(requestId)).then((response) => {
      const data = response?.data?.data;
      if (!active || !data) return;
      setApproval(data);
      if (data.formJson) {
        try { setForm((current) => ({ ...current, ...JSON.parse(data.formJson), signaturePreviewUrl: current.signaturePreviewUrl })); } catch { /* keep the prefilled form */ }
      }
    }).catch(() => { /* not submitted yet, or it could not be read: treat as not submitted */ });
    return () => { active = false; };
  }, [approvalMode, request, requestId]);

  // The exact file that was submitted (and later approved), shown read-only.
  useEffect(() => {
    if (!['PENDING_APPROVAL', 'APPROVED'].includes(approvalStatus) || !approval?.documentId) { setApprovedPreviewUrl(''); return undefined; }
    let active = true;
    let objectUrl = '';
    Promise.resolve(myHrDocumentRequestApi.getDocument(requestId, approval.documentId)).then((response) => {
      if (!active || !response?.data) return;
      objectUrl = URL.createObjectURL(new Blob([response.data], { type: approval.contentType || 'application/pdf' }));
      setApprovedPreviewUrl(objectUrl);
    }).catch(() => { if (active) setApprovedPreviewUrl(''); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [approvalStatus, approval?.documentId, approval?.contentType, requestId]);

  // Real Organization Settings name (not the COMPANY_INFO prototype default)
  // for the letterhead/closing - loaded once, falls back to COMPANY_INFO
  // while this request is in flight or if it fails (see OfferLetterPreparePage).
  useEffect(() => {
    myHrEmployeeApi.getMine()
      .then((response) => setOrganization(response.data.data))
      .catch(() => setOrganization(null));
  }, []);

  // Organization Settings logo for the letterhead (preview and PDF); the
  // organization name is only the fallback when no logo is configured.
  useEffect(() => {
    const logoUrl = organization?.organizationLogoUrl;
    if (!logoUrl) { setLogoAsset(null); return undefined; }
    let active = true;
    fetchLetterheadLogo(logoUrl)
      .then((asset) => { if (active) setLogoAsset(asset); })
      .catch(() => { if (active) setLogoAsset(null); });
    return () => { active = false; };
  }, [organization?.organizationLogoUrl]);

  // The employee's own assigned HR (their active primary reporting manager -
  // see OfferLetterPreparePage). A Company Admin preparing the document has
  // no EmployeeProfile of their own, so this is what keeps the real assigned
  // HR's name showing instead of falling back to the COMPANY_INFO placeholder.
  // The same profile also pre-fills the employee email and any config field
  // that names a profileField - only where HR hasn't already typed a value.
  useEffect(() => {
    if (!request?.requestedByUserId) { setAssignedHr(null); setEmployeeProfile(null); return; }
    myHrEmployeeApi.get(request.requestedByUserId)
      .then((response) => {
        const profile = response.data.data;
        setAssignedHr(profile.reportingManager || null);
        setEmployeeProfile(profile);
        const prefill = {
          employeeEmail: profile.workEmail || profile.personalEmail || '',
          ...Object.fromEntries(config.fields
            .filter((field) => field.profileField)
            .map((field) => [field.name, profile[field.profileField] || ''])),
        };
        setForm((current) => ({
          ...current,
          ...Object.fromEntries(Object.entries(prefill).filter(([name, value]) => value && !current[name])),
        }));
      })
      .catch(() => { setAssignedHr(null); setEmployeeProfile(null); });
  }, [request?.requestedByUserId, config.fields]);

  const orgName = organization?.organizationName || COMPANY_INFO.name;
  // The company admin account (Organization Settings Admin Email) preparing its own letter signs it directly: the
  // server approves and signs it in the same submit, so there is nothing to wait for and it is sent straight away.
  // Another Admin (or HR) still submits it for the company admin to sign.
  const sessionEmail = String(getMyHrSession()?.email || '').trim().toLowerCase();
  const selfSigner = role === 'COMPANY_ADMIN' && signedBySignatory && Boolean(sessionEmail)
    && sessionEmail === String(organization?.organizationAdminEmail || '').trim().toLowerCase();
  const ownSignatureUrl = selfSigner && organization?.signatureUrl ? resolveFileUrl(organization.signatureUrl) : '';
  const approver = signedBySignatory
    ? (approval?.signatoryName ? `company admin (${approval.signatoryName})` : 'company admin')
    : approverLabel(organization?.organizationName);
  // Organization Settings and employee profile values a preview() builder may
  // need beyond the form (e.g. the salary verification letter's pay frequency,
  // the employment verification letter's start date and employment type).
  const letterContext = {
    payrollPeriod: organization?.organizationPayrollPeriod || '',
    orgAddress: organization?.organizationAddress || '',
    employeeStartDate: employeeProfile?.startDate || '',
    employmentType: employeeProfile?.employmentType || '',
  };
  // The signer is the HR assigned to this employee - see OfferLetterPreparePage.
  const issuerName = assignedHr?.name || organization?.name || COMPANY_INFO.hrName;
  const issuerTitle = organization?.designation || COMPANY_INFO.hrTitle;
  // Sign-off lines around the signature image - a type's own closing()
  // (e.g. Experience and Relieving Letter's fixed signatory) or the default.
  const closing = config.closing
    ? config.closing({
      orgName,
      orgPhone: organization?.organizationPhoneNumber || '',
      orgAddress: organization?.organizationAddress || '',
      orgWebsite: organization?.organizationWebsite || '',
      adminName: organization?.organizationAdminName || '',
      adminEmail: organization?.organizationAdminEmail || '',
      adminPhone: organization?.organizationAdminPhoneNumber || '',
      issuerName,
      issuerTitle,
    })
    : { beforeSignature: [`For ${endWithPeriod(orgName)}`], afterSignature: [issuerName, issuerTitle, orgName] };

  const setField = (field) => (event) => {
    let value = event.target.value;
    if (field === 'employeeName') value = String(value).replace(LETTERS_AND_SPACES, '');
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const validateStep1 = () => {
    const next = {};
    if (!form.employeeName.trim()) next.employeeName = 'Enter the employee name.';
    else if (!PERSON_NAME_PATTERN.test(form.employeeName.trim())) next.employeeName = 'Name may only contain letters and spaces.';
    if (!form.employeeEmail.trim()) next.employeeEmail = 'Enter the employee email.';
    else if (!EMAIL_PATTERN.test(form.employeeEmail.trim())) next.employeeEmail = 'Enter a valid email address.';
    if (!form.issueDate) next.issueDate = 'Select an issue date.';
    config.fields.forEach((field) => {
      if (field.required && !String(form[field.name] || '').trim()) next[field.name] = `Enter ${field.label.toLowerCase()}.`;
    });
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const continueToTemplate = (event) => {
    event.preventDefault();
    if (validateStep1()) setStep(2);
  };

  const requiresEmployeeSignature = workflowType.signatureConfigurable ? form.otherRequiresSignature : workflowType.requiresEmployeeSignature;
  const requiresSignedReturn = workflowType.signatureConfigurable ? (form.otherRequiresSignature && form.otherRequiresSignedReturn) : workflowType.requiresSignedReturn;

  // Renders the same blocks/letterhead/closing as the on-screen preview
  // below into a PDF, so what HR reviews before sending is what the employee
  // ends up with. For a letter signed by the company admin the signature
  // space is left blank and its position (fractions of the page, from the top
  // left) is returned, so the server can sign exactly there on approval.
  const createCompletedPdf = () => {
    let signaturePlacement = null;
    const pdf = new jsPDF({ unit: 'pt', format: 'letter' });
    pdf.setProperties({ title: workflowType.label });
    const margin = 54; const width = 504; const pageHeight = 792; let y = 64;
    const nextPage = (height = 0) => { if (y + height > pageHeight - 54) { pdf.addPage(); y = 64; } };
    const paragraph = (text, { bold = false, size = 10, indent = 0, after = 11 } = {}) => {
      pdf.setFont('helvetica', bold ? 'bold' : 'normal'); pdf.setFontSize(size);
      const lines = pdf.splitTextToSize(text, width - indent); nextPage(lines.length * (size + 3) + after);
      pdf.text(lines, margin + indent, y, { lineHeightFactor: 1.3 }); y += lines.length * (size + 3) + after;
    };

    if (logoAsset) {
      const logoHeight = 32;
      pdf.addImage(logoAsset.dataUrl, logoAsset.format, margin, 36, logoHeight * (logoAsset.width / logoAsset.height), logoHeight);
      y = 36 + logoHeight + 24;
    } else {
      pdf.setTextColor(23, 63, 51); paragraph(orgName.toUpperCase(), { bold: true, size: 13, after: 20 }); pdf.setTextColor(0, 0, 0);
    }
    config.preview(form, orgName, letterContext).forEach((block) => {
      if (block.type === 'list') { block.items.forEach((item) => paragraph(`• ${item}`, { indent: 18, after: 6 })); return; }
      if (block.type === 'lines') { paragraph(block.lines.join('\n'), { after: 11 }); return; }
      if (block.type === 'heading') {
        nextPage(24); pdf.setFont('helvetica', 'bold'); pdf.setFontSize(11);
        pdf.text(block.text, margin + (width / 2), y, { align: 'center' }); y += 22;
        return;
      }
      paragraph(block.text);
    });
    paragraph(closing.beforeSignature.join('\n'), { after: 8 });
    nextPage(46);
    if (signedBySignatory) {
      signaturePlacement = { page: pdf.getNumberOfPages(), x: margin / 612, y: y / pageHeight, width: 84 / 612, height: 38 / pageHeight };
      y += 46;
    } else {
      if (form.signaturePreviewUrl) pdf.addImage(form.signaturePreviewUrl, 'PNG', margin, y, 84, 38);
      y += form.signaturePreviewUrl ? 46 : 30;
    }
    paragraph(closing.afterSignature.join('\n'), { after: 0 });

    // A type's optional letterhead footer (e.g. Salary Verification) is
    // stamped at the bottom of every page, like printed letterhead.
    if (closing.footer) {
      pdf.setFont('helvetica', 'normal'); pdf.setFontSize(7.5); pdf.setTextColor(90, 90, 90);
      const footerLines = pdf.splitTextToSize(closing.footer, width);
      for (let page = 1; page <= pdf.getNumberOfPages(); page += 1) {
        pdf.setPage(page);
        pdf.text(footerLines, margin + (width / 2), pageHeight - 30, { align: 'center' });
      }
      pdf.setTextColor(0, 0, 0);
    }

    return { file: new File([pdf.output('blob')], `${workflowType.routeSegment}.pdf`, { type: 'application/pdf' }), signaturePlacement };
  };

  const sendDocument = async (email) => {
    setSending(true); setSendError('');
    try {
      // Real backend integration: generates the actual PDF and uploads it
      // via the generic fulfill() endpoint, which stores it as the
      // request's fulfilledDocumentId and marks the request FULFILLED.
      const result = (await myHrDocumentRequestApi.fulfill(requestId, createCompletedPdf().file, email)).data.data;
      setEmailDelivery(result.emailDelivery);
      // The backend has no concept of the employee sign/return lifecycle
      // yet (fulfill() only knows "HR delivered a document"), so that part
      // is layered on top via the same localStorage workflow-state overlay
      // used by Offer Letter - for sign-required types the request is
      // genuinely FULFILLED in the backend's eyes, but from the employee/HR
      // lifecycle this is only "sent - awaiting the employee's signature."
      setWorkflowState(requestId, {
        stage: 'SENT_TO_EMPLOYEE',
        requiresEmployeeSignature,
        requiresSignedReturn,
        sentAt: new Date().toISOString(),
        sentByUserId: getMyHrSession()?.userId || null,
      });
      setSent(true);
    } catch (error) {
      const fieldErrors = error?.response?.data?.data;
      const detail = fieldErrors && typeof fieldErrors === 'object'
        ? Object.entries(fieldErrors).map(([field, message]) => `${field}: ${message}`).join('; ')
        : '';
      setSendError(detail || error?.response?.data?.message || `${workflowType.label} could not be completed. Please try again.`);
      throw error;
    } finally {
      setSending(false);
    }
  };

  // HR: submit the generated document for Company Admin approval. Nothing is sent to the employee at this point.
  const submitForApproval = async () => {
    if (!form.employeeEmail.trim() || !EMAIL_PATTERN.test(form.employeeEmail.trim())) {
      setErrors((current) => ({ ...current, employeeEmail: 'Enter a valid employee email.' }));
      return;
    }
    if (!signedBySignatory && !form.signaturePreviewUrl) {
      setSendError('Add your HR signature before submitting this document for Admin approval.');
      return;
    }
    setSending(true); setSendError('');
    try {
      const { file, signaturePlacement } = createCompletedPdf();
      const response = await myHrDocumentRequestApi.submitDocumentForApproval(requestId, {
        employeeName: form.employeeName.trim(),
        issueDate: form.issueDate,
        formJson: JSON.stringify({ ...form, signaturePreviewUrl: '' }),
        ...(signaturePlacement ? { authorizedSignature: signaturePlacement } : {}),
      }, file);
      setApproval(response.data.data);
    } catch (error) {
      setSendError(error?.response?.data?.message || `${workflowType.label} could not be submitted for approval. Please try again.`);
    } finally {
      setSending(false);
    }
  };

  // HR: once the Admin approved it, deliver exactly the approved document to the employee (the server refuses anything else).
  const sendApprovedDocument = async (email) => {
    setSending(true); setSendError('');
    try {
      const result = (await myHrDocumentRequestApi.deliverApprovedDocument(requestId, email)).data.data;
      setEmailDelivery(result.emailDelivery);
      setWorkflowState(requestId, {
        stage: 'SENT_TO_EMPLOYEE',
        requiresEmployeeSignature,
        requiresSignedReturn,
        sentAt: new Date().toISOString(),
        sentByUserId: getMyHrSession()?.userId || null,
      });
      setSent(true);
    } catch (error) {
      setSendError(error?.response?.data?.message || `${workflowType.label} could not be sent. Please try again.`);
      throw error;
    } finally {
      setSending(false);
    }
  };

  // Company admin signing their own letter: one step. The submit is approved and signed on the server, and the signed
  // copy is then delivered. If the server still holds it for the signatory, the pending panel shows that instead.
  // email is the reviewed recipient/subject/message from DocumentFulfillmentEmailComposer.
  const signAndSend = async (email) => {
    setSending(true); setSendError('');
    let signed = approval;
    try {
      if (signed?.approvalStatus !== 'APPROVED') {
        const { file, signaturePlacement } = createCompletedPdf();
        signed = (await myHrDocumentRequestApi.submitDocumentForApproval(requestId, {
          employeeName: form.employeeName.trim(),
          issueDate: form.issueDate,
          formJson: JSON.stringify({ ...form, signaturePreviewUrl: '' }),
          authorizedSignature: signaturePlacement,
        }, file)).data.data;
        if (signed?.approvalStatus !== 'APPROVED') { setApproval(signed); return; }
      }
      const result = (await myHrDocumentRequestApi.deliverApprovedDocument(requestId, email)).data.data;
      setEmailDelivery(result.emailDelivery);
      setWorkflowState(requestId, {
        stage: 'SENT_TO_EMPLOYEE',
        requiresEmployeeSignature,
        requiresSignedReturn,
        sentAt: new Date().toISOString(),
        sentByUserId: getMyHrSession()?.userId || null,
      });
      setApproval(signed);
      setSent(true);
    } catch (error) {
      // Signed but not delivered: keep the signed state so a retry only sends it.
      if (signed?.approvalStatus === 'APPROVED') setApproval(signed);
      setSendError(error?.response?.data?.message || `${workflowType.label} could not be sent. Please try again.`);
      throw error;
    } finally {
      setSending(false);
    }
  };

  const approvedFile = approvedPreviewUrl ? (
    /pdf/i.test(approval?.contentType || 'application/pdf')
      ? <div className="offer-letter__pdf-preview" aria-label={`Submitted ${workflowType.label}`}>
        <iframe title={`Submitted ${workflowType.label}`} src={approvedPreviewUrl} />
      </div>
      : <p><a href={approvedPreviewUrl} download={approval?.fileName || 'document'}>Download {approval?.fileName || 'the document'}</a></p>
  ) : null;
  const backToRequest = <footer><Button type="button" onClick={() => navigate(detailPath)}>Back to Request</Button></footer>;
  let statusPanel = null;
  if (approvalStatus === 'PENDING_APPROVAL') {
    statusPanel = <div className="request-documents__review offer-letter__template">
      <ApprovalBanner variant="pending" label="PENDING ADMIN APPROVAL"
        message={`This ${workflowType.label} has been submitted to the ${approver} for approval. You can send it to the employee only after approval.`} />
      {approvedFile}
      {backToRequest}
    </div>;
  } else if (approvalStatus === 'SENT') {
    statusPanel = <div className="request-documents__review offer-letter__template">
      <ApprovalBanner variant="sent" label="SENT" message={`This ${workflowType.label} has been sent to the employee.`} />
      {backToRequest}
    </div>;
  } else if (approvalStatus === 'APPROVED' && sent) {
    statusPanel = <div className="request-documents__review offer-letter__template"><div className="offer-letter__sent">
      <Toast variant={emailDelivery?.status === 'DELIVERED' ? 'success' : 'warning'}
        message={emailDelivery?.status === 'DELIVERED'
          ? `${workflowType.label} emailed to ${emailDelivery.recipientEmail}.`
          : `${workflowType.label} was completed, but email was not delivered. ${emailDelivery?.message || ''}`} />
      {backToRequest}
    </div></div>;
  } else if (approvalStatus === 'APPROVED') {
    // The company admin signed it themselves: no approval to report, just the signed letter to send.
    statusPanel = <div className="request-documents__review offer-letter__template">
      {!selfSigner && <ApprovalBanner variant="approved" label="APPROVED"
        message={`This ${workflowType.label} has been approved by the ${approver} and is ready to be sent to the employee.`} />}
      {selfSigner && <h3>SIGNED {workflowType.label.toUpperCase()}</h3>}
      {approvedFile}
      <div className="offer-letter__send">
        <h3>Send {workflowType.label}</h3>
        <DocumentFulfillmentEmailComposer requestId={requestId} attachmentFileName={approval?.fileName || `${workflowType.routeSegment}.pdf`}
          sendLabel={`Send ${workflowType.label}`} onSend={sendApprovedDocument} />
      </div>
    </div>;
  }

  if (loading) return <MyHrFeaturePage className="phase-six" title={workflowType.prepareLabel} backTo={detailPath}><p role="status">Loading document request…</p></MyHrFeaturePage>;
  if (loadError || !request) return <MyHrFeaturePage className="phase-six" title={workflowType.prepareLabel} backTo={detailPath}><p role="alert" className="document-requests__error">{loadError || 'Document request could not be loaded.'}</p></MyHrFeaturePage>;

  return <MyHrFeaturePage className="phase-six" title={workflowType.prepareLabel} description={`Build and send the ${workflowType.label.toLowerCase()} for this request.`} fullWidth backTo={detailPath}>
    <section className="document-requests__detail offer-letter__context">
      <header><h2>{request.requestedByName}</h2></header>
      <dl>
        <div><dt>Employee</dt><dd>{request.requestedByLoginId ? `${request.requestedByLoginId} - ${request.requestedByName}` : request.requestedByName}</dd></div>
        <div><dt>Request</dt><dd>{request.requestTitle}</dd></div>
        <div><dt>Requested on</dt><dd>{request.requestedAt ? formatDateOnly(request.requestedAt) : '—'}</dd></div>
        <div><dt>Status</dt><dd>{formatDisplayLabel(request.status)}</dd></div>
      </dl>
    </section>

    <div className="document-workflow-layout">
      <DocumentRoute request={request} needsApproval={role === 'HR'} preparationStep={step} sent={sent} approvalStatus={approvalStatus} nextAction={sent ? undefined : approvalStatus === 'APPROVED' ? 'Send approved document' : undefined} />
    <section className="request-documents__card">
      <header><div><span>HUMAN RESOURCES</span><h2>{workflowType.prepareLabel.toUpperCase()}</h2></div></header>
      {sendError && <p role="alert" className="document-requests__error">{sendError}</p>}
      {approvalStatus === 'REJECTED' && <ApprovalBanner variant="rejected" label="ADMIN APPROVAL REJECTED"
        message={`The ${approver} rejected this ${workflowType.label}. Correct it and submit it for approval again.`}>
        {approval?.rejectionReason ? <p className="offer-approval__reason"><strong>Reason:</strong> {approval.rejectionReason}</p> : null}
      </ApprovalBanner>}

      {statusPanel || (step === 1 ? (
        <form className="request-documents__form" onSubmit={continueToTemplate} noValidate>
          <div className="request-documents__grid">
            <TextInput name="employeeId" label="Employee ID" value={request.requestedByLoginId || '—'} disabled />
            <TextInput name="employeeName" label="Employee Name" required value={form.employeeName}
              onChange={setField('employeeName')} error={errors.employeeName} />
            <EmailInput name="employeeEmail" label="Employee Email" required value={form.employeeEmail}
              onChange={setField('employeeEmail')} error={errors.employeeEmail}
              hint={!errors.employeeEmail ? 'Auto-filled from the employee profile when available - confirm it.' : undefined} />
            {config.fields.map((field) => {
              if (field.type === 'textarea') return null;
              const commonProps = {
                name: field.name, label: field.label, required: field.required,
                value: form[field.name] || '', onChange: setField(field.name), error: errors[field.name],
              };
              if (field.type === 'select') return <Dropdown key={field.name} {...commonProps} placeholder={`Select ${field.label.toLowerCase()}`} options={field.options} />;
              return <TextInput key={field.name} {...commonProps} type={field.type === 'date' ? 'date' : 'text'} placeholder={field.placeholder} />;
            })}
            <TextInput name="issueDate" label="Issue Date" type="date" required value={form.issueDate}
              onChange={setField('issueDate')} error={errors.issueDate} />
          </div>

          {config.fields.filter((field) => field.type === 'textarea').map((field) => (
            <div className="request-documents__wide" key={field.name}>
              <TextArea name={field.name} label={field.label} required={field.required} value={form[field.name] || ''}
                onChange={setField(field.name)} placeholder={field.placeholder} hint={field.hint} error={errors[field.name]} />
            </div>
          ))}

          {workflowType.signatureConfigurable && <>
            <h3 className="offer-letter__section-title">Employee Signature Requirements</h3>
            <div className="request-documents__grid">
              <Checkbox name="otherRequiresSignature" label="Employee must sign this document"
                checked={form.otherRequiresSignature}
                onChange={(e) => setForm((current) => ({ ...current, otherRequiresSignature: e.target.checked, otherRequiresSignedReturn: e.target.checked ? current.otherRequiresSignedReturn : false }))} />
              <Checkbox name="otherRequiresSignedReturn" label="Employee must return a signed copy"
                checked={form.otherRequiresSignedReturn} disabled={!form.otherRequiresSignature}
                onChange={(e) => setForm((current) => ({ ...current, otherRequiresSignedReturn: e.target.checked }))} />
            </div>
          </>}

          <h3 className="offer-letter__section-title">Signature</h3>
          {selfSigner
            ? <p className="offer-letter__send-intro">
              You sign this letter. Your signature from{' '}
              <Link to="/myhr/admin/profile">My ADMIN Profile</Link> is added to the letter automatically.
              {organization && !organization.signatureUrl && ' You have not added a signature yet - add it there first.'}
            </p>
            : signedBySignatory
            ? <p className="offer-letter__send-intro">
              This letter is signed by the company admin named in its signature block. Their signature from My HR Profile is
              applied automatically when they approve it - it cannot be signed here.
            </p>
            : <SignatureField onChange={(url) => setForm((current) => ({ ...current, signaturePreviewUrl: url }))} />}

          <footer>
            <Button type="button" variant="secondary" onClick={() => navigate(detailPath)}>Cancel</Button>
            <Button type="submit">Continue</Button>
          </footer>
        </form>
      ) : (
        <div className="request-documents__review offer-letter__template">
          {!sent ? (
            <>
              <h3>SELECTED TEMPLATE</h3>
              <div className="offer-letter__template-badge">{config.templateName}</div>

              <div className="offer-letter__preview" aria-label={`${workflowType.label} preview`}>
                {organization?.organizationLogoUrl
                  ? <img className="offer-letter__logo" src={resolveFileUrl(organization.organizationLogoUrl)} alt={`${orgName} logo`} />
                  : <p className="offer-letter__letterhead">{orgName.toUpperCase()}</p>}
                {config.preview(form, orgName, letterContext).map((block, index) => {
                  if (block.type === 'list') return <ul key={index}>{block.items.map((item, itemIndex) => <li key={itemIndex}>{item}</li>)}</ul>;
                  if (block.type === 'lines') return <p key={index}>{block.lines.map((line, lineIndex) => (
                    <span key={lineIndex}>{line}{lineIndex < block.lines.length - 1 && <br />}</span>
                  ))}</p>;
                  if (block.type === 'heading') return <p key={index} className="document-letter__heading"><strong>{block.text}</strong></p>;
                  return <p key={index}>{block.text}</p>;
                })}
                <p>{closing.beforeSignature.map((line, index) => <span key={index}>{index > 0 && <br />}{line}</span>)}</p>
                {ownSignatureUrl
                  ? <div className="offer-letter__preview-signature"><img src={ownSignatureUrl} alt="Your signature" /></div>
                  : selfSigner
                  ? <p className="offer-letter__preview-signature-placeholder">[Your signature from My ADMIN Profile]</p>
                  : signedBySignatory
                  ? <p className="offer-letter__preview-signature-placeholder">[Company admin&apos;s signature - applied on approval]</p>
                  : form.signaturePreviewUrl
                  ? <div className="offer-letter__preview-signature"><img src={form.signaturePreviewUrl} alt="Authorized signature" /></div>
                  : <p className="offer-letter__preview-signature-placeholder">[Signature pending]</p>}
                <p>{closing.afterSignature.map((line, index) => <span key={index}>{index > 0 && <br />}{line}</span>)}</p>
                {closing.footer && <p className="document-letter__footer">{closing.footer}</p>}
              </div>

              {selfSigner ? (
                <div className="offer-letter__send">
                  <h3>Send {workflowType.label}</h3>
                  <p className="offer-letter__send-intro">
                    Check the {workflowType.label.toLowerCase()} above - it carries your signature from My ADMIN Profile. Once verified, send it to the employee.
                  </p>
                  <DocumentFulfillmentEmailComposer requestId={requestId} attachmentFileName={`${workflowType.routeSegment}.pdf`}
                    sendLabel="Verify & Send" onSend={signAndSend} />
                  <footer>
                    <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                  </footer>
                </div>
              ) : approvalMode ? (
                <div className="offer-letter__send">
                  <h3>{signedBySignatory ? 'Submit for Signature' : 'Submit for Admin Approval'}</h3>
                  <p className="offer-letter__send-intro">
                    {signedBySignatory
                      ? `This ${workflowType.label.toLowerCase()} must be approved and signed by the ${approver} before it can be sent to the employee. `
                        + 'Their signature is applied when they approve it - you will be notified when they approve or reject it.'
                      : `Your HR signature is included in this ${workflowType.label.toLowerCase()}. The ${approver} must approve it before it can be sent to the employee. `
                        + 'You will be notified when the Admin approves or requests changes.'}
                  </p>
                  <EmailInput name="sendEmployeeEmail" label="Employee Email" required value={form.employeeEmail}
                    onChange={setField('employeeEmail')} error={errors.employeeEmail} />
                  <footer>
                    <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                    <Button type="button" loading={sending} onClick={submitForApproval}>
                      {approvalStatus === 'REJECTED' ? 'Resubmit for Approval' : (signedBySignatory ? 'Submit for Signature' : 'Submit for Admin Approval')}
                    </Button>
                  </footer>
                </div>
              ) : (
                <div className="offer-letter__send">
                  <h3>Send {workflowType.label}</h3>
                  <DocumentFulfillmentEmailComposer requestId={requestId} attachmentFileName={`${workflowType.routeSegment}.pdf`}
                    sendLabel={`Send ${workflowType.label}`} onSend={sendDocument} />
                  <footer>
                    <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                  </footer>
                </div>
              )}
            </>
          ) : (
            <div className="offer-letter__sent">
              <Toast variant={emailDelivery?.status === 'DELIVERED' ? 'success' : 'warning'}
                message={emailDelivery?.status === 'DELIVERED'
                  ? `${workflowType.label} emailed to ${emailDelivery.recipientEmail}.`
                  : `${workflowType.label} was completed, but email was not delivered. ${emailDelivery?.message || ''}`} />
              <footer>
                <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                <Button type="button" onClick={() => navigate(detailPath)}>Back to Request</Button>
              </footer>
            </div>
          )}
        </div>
      ))}
    </section>
    </div>
  </MyHrFeaturePage>;
}
