import DocumentRoute from '../../components/DocumentRoute';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import jsPDF from 'jspdf';
import { Button, Checkbox, Dropdown, EmailInput, PhoneInput, TextArea, TextInput, Toast } from '../../../../components';
import myHrDocumentRequestApi from '../../../../api/myHrDocumentRequestApi';
import myHrEmployeeApi from '../../../../api/myHrEmployeeApi';
import resolveFileUrl from '../../../../api/resolveFileUrl';
import { formatDateLong, formatDateOnly } from '../../../../utils/dateFormat';
import endWithPeriod from '../../../../utils/endWithPeriod';
import MyHrFeaturePage from '../MyHrFeaturePage';
import { measureSignatureNaturalSize } from '../signatureImageProcessing';
import {
  COMPANY_INFO,
  EMPLOYMENT_CATEGORIES,
  OFFER_TYPES,
  OTHER_DESIGNATION,
  PAY_TYPES,
  getDesignationLabel,
  getDesignationOptions,
  getOfferLetterTemplate,
  getStandardOfferLetterClauses,
} from './offerLetterTemplates';
import { approverLabel } from './offerLetterState';
import './documentRequests.css';
import './offerLetterApproval.css';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const ZIP_PATTERN = /^\d{5}$/;
const PERSON_NAME_PATTERN = /^[\p{L}\p{M}]+(?: [\p{L}\p{M}]+)*$/u;
const LETTERS_WORDS_PATTERN = /^[\p{L}\p{M}]+(?:[ -][\p{L}\p{M}]+)*$/u;
const PHONE_PATTERN = /^\+1\d{10}$/;
const PAY_RATE_PATTERN = /^\d+(\.\d+)?$/;
const LETTERS_AND_SPACES = /[^\p{L}\p{M} ]/gu;
const LETTERS_SPACES_HYPHENS = /[^\p{L}\p{M} -]/gu;
const today = () => new Date().toISOString().slice(0, 10);

/** Keep digits and at most one decimal point (e.g. 45 or 15.50). */
function sanitizePayRate(value) {
  const cleaned = String(value).replace(/[^\d.]/g, '');
  const decimal = cleaned.indexOf('.');
  if (decimal === -1) return cleaned;
  return `${cleaned.slice(0, decimal + 1)}${cleaned.slice(decimal + 1).replace(/\./g, '')}`;
}

/** Fields that accept letters (and spaces/hyphens) only - digits and other symbols are stripped as the user types. */
const LETTERS_ONLY_FIELDS = {
  employeeName: LETTERS_AND_SPACES,
  otherDesignation: LETTERS_AND_SPACES,
  payRateWords: LETTERS_SPACES_HYPHENS,
  payFrequencyNote: LETTERS_AND_SPACES,
};

/** jsPDF's addImage needs an explicit format matching the image's own encoding. */
function logoFormat(dataUrl) {
  const subtype = /^data:image\/([a-zA-Z0-9+.-]+);/.exec(dataUrl)?.[1]?.toLowerCase();
  if (subtype === 'jpeg' || subtype === 'jpg') return 'JPEG';
  if (subtype === 'webp') return 'WEBP';
  return 'PNG';
}

/**
 * Fetches an uploaded image (logo or organization signature) and converts it
 * to a data URL so jsPDF - which cannot load a bare HTTP URL - can stamp it
 * into the generated PDF, not just the on-screen preview.
 */
function fetchImageAsset(url) {
  return fetch(resolveFileUrl(url))
    .then((response) => response.blob())
    .then((blob) => new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    }))
    .then((dataUrl) => new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve({ dataUrl, width: image.naturalWidth, height: image.naturalHeight, format: logoFormat(dataUrl) });
      image.onerror = reject;
      image.src = dataUrl;
    }));
}

// PENDING = being prepared; SUBMITTED / APPROVED / RETURNED_FOR_CORRECTION = in the Admin approval workflow; FULFILLED = already sent.
const OPENABLE_STATUSES = ['PENDING', 'SUBMITTED', 'APPROVED', 'RETURNED_FOR_CORRECTION', 'FULFILLED'];

const titleCase = (value) => String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());

const initialForm = {
  offerType: '', employmentCategory: '', designation: '', otherDesignation: '', rolesAndResponsibilities: '',
  employeeName: '', employeeEmail: '', employeeMobile: '', joiningDate: '', issueDate: today(), workLocation: '',
  streetAddress: '', city: '', state: '', zipCode: '',
  payType: 'hourly', payRate: '', payRateWords: '', payFrequencyNote: 'payable monthly',
  benefitsEligible: false, benefitsText: '',
  endDate: '', internshipSchedule: '',
};

/**
 * First implementation of the "prepare a document from an employee request"
 * workflow (see the document-generation design doc). Offer-Letter-specific
 * fields/templates live here; a future document type adds its own page(s)
 * next to this one rather than generalizing this component prematurely.
 */
export default function OfferLetterPreparePage({ role }) {
  const navigate = useNavigate();
  const { requestId } = useParams();
  const base = role === 'COMPANY_ADMIN' ? '/myhr/admin' : '/myhr/hr';
  const detailPath = `${base}/document-requests/${requestId}`;

  const [request, setRequest] = useState(null);
  const [organization, setOrganization] = useState(null);
  const [logoAsset, setLogoAsset] = useState(null);
  const [orgSignatureAsset, setOrgSignatureAsset] = useState(null);
  const [assignedHr, setAssignedHr] = useState(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [step, setStep] = useState(1);
  const [form, setForm] = useState(initialForm);
  const [errors, setErrors] = useState({});
  const [sent, setSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');
  // The email that accompanies the letter. Recipient/subject/message are
  // editable for this send only - nothing here is saved to the employee's
  // profile or changes the generated PDF.
  const [email, setEmail] = useState({ recipientEmail: '', subject: '', message: '', attachmentFileName: '' });
  const [emailErrors, setEmailErrors] = useState({});
  const emailDraftKeyRef = useRef(null);
  const [emailDraftLoading, setEmailDraftLoading] = useState(false);
  const [emailDraftNote, setEmailDraftNote] = useState('');
  // Set once fulfill() has stored the PDF, so a failed email can be retried
  // without delivering (and storing) the letter a second time.
  const [delivered, setDelivered] = useState(false);
  const [emailedTo, setEmailedTo] = useState('');
  // Set when the server accepted the send but could not confirm delivery (HTTP 202): not a failure, and not safe to resend blindly.
  const [emailNotice, setEmailNotice] = useState('');
  const [previewFile, setPreviewFile] = useState(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const [preparingPreview, setPreparingPreview] = useState(false);
  const [previewError, setPreviewError] = useState('');
  // Company Admin approval of this letter (null until loaded). The letter can only be sent to the employee once it is APPROVED.
  const [approval, setApproval] = useState(null);
  const [approvedPreviewUrl, setApprovedPreviewUrl] = useState('');
  const approvalStatus = approval?.approvalStatus || 'NONE';

  useEffect(() => {
    setLoading(true); setLoadError('');
    myHrDocumentRequestApi.get(requestId).then((response) => {
      const data = response.data.data;
      if (data.documentType !== 'OFFER_LETTER' || data.requestTitle !== 'Offer Letter') {
        setRequest(null);
        setLoadError('This request is not an offer letter request.');
        return;
      }
      if (!OPENABLE_STATUSES.includes(data.status)) {
        setRequest(null);
        setLoadError('Only pending offer letter requests can be prepared.');
        return;
      }
      setRequest(data);
      setForm((current) => ({
        ...current,
        employeeName: data.requestedByName || '',
        employeeEmail: data.requestedByEmail || '',
        streetAddress: data.requestedByStreetAddress || '',
        city: data.requestedByCity || '',
        state: data.requestedByState || '',
        zipCode: data.requestedByZipCode || '',
      }));
    }).catch(() => setLoadError('Offer letter request could not be loaded.'))
      .finally(() => setLoading(false));
  }, [requestId]);

  // The Admin approval state of this letter. A letter that was already submitted keeps the form HR submitted, so a
  // rejected letter can be corrected without re-entering it.
  useEffect(() => {
    if (!request) return undefined;
    let active = true;
    Promise.resolve(myHrDocumentRequestApi.getOfferLetterApproval(requestId)).then((response) => {
      const data = response?.data?.data;
      if (!active || !data) return;
      setApproval(data);
      if (data.formJson) {
        try { setForm((current) => ({ ...current, ...JSON.parse(data.formJson) })); } catch { /* keep the prefilled form */ }
      }
    }).catch(() => { /* no approval yet, or it could not be read: treat as not submitted */ });
    return () => { active = false; };
  }, [request, requestId]);

  // The exact PDF that was submitted (and later approved), shown read-only. It is never regenerated.
  useEffect(() => {
    if (!['PENDING_APPROVAL', 'APPROVED'].includes(approvalStatus) || !approval?.documentId) { setApprovedPreviewUrl(''); return undefined; }
    let active = true;
    let objectUrl = '';
    Promise.resolve(myHrDocumentRequestApi.getDocument(requestId, approval.documentId)).then((response) => {
      if (!active || !response?.data) return;
      objectUrl = URL.createObjectURL(new Blob([response.data], { type: 'application/pdf' }));
      setApprovedPreviewUrl(objectUrl);
    }).catch(() => { if (active) setApprovedPreviewUrl(''); });
    return () => { active = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [approvalStatus, approval?.documentId, requestId]);

  // Organization Settings' name, address, phone, website, EIN and E-Verify
  // number (real values, not the COMPANY_INFO prototype defaults) - loaded
  // once and falls back to COMPANY_INFO only while this request is in flight
  // or if it fails.
  useEffect(() => {
    myHrEmployeeApi.getMine()
      .then((response) => setOrganization(response.data.data))
      .catch(() => setOrganization(null));
  }, []);

  // Organization Settings' logo, fetched once and converted to a data URL so
  // jsPDF (which cannot load a bare HTTP URL) can stamp it on every page of
  // the generated PDF, not just the on-screen preview.
  useEffect(() => {
    const logoUrl = organization?.organizationLogoUrl;
    if (!logoUrl) { setLogoAsset(null); return undefined; }
    let active = true;
    fetchImageAsset(logoUrl)
      .then((asset) => { if (active) setLogoAsset(asset); })
      .catch(() => { if (active) setLogoAsset(null); });
    return () => { active = false; };
  }, [organization?.organizationLogoUrl]);

  // The signature applied to the offer letter's issuer block: the preparing
  // HR user's own signature from their My HR Profile (the signed-in user's
  // GET /employees/me). Fetched as a data URL, like the logo above, so jsPDF
  // can embed it. (orgSignatureAsset keeps its name so the PDF layout code
  // that draws it is untouched.)
  useEffect(() => {
    const signatureUrl = organization?.signatureUrl;
    if (!signatureUrl) { setOrgSignatureAsset(null); return undefined; }
    let active = true;
    // Measured once here so the on-screen preview and the PDF both draw the
    // same extracted signature at the same, natural size (see
    // measureSignatureNaturalSize) - never the PNG's raw pixel size.
    fetchImageAsset(signatureUrl)
      .then(async (asset) => ({
        ...asset,
        natural: await measureSignatureNaturalSize(asset.dataUrl).catch(() => ({ width: asset.width, height: asset.height })),
      }))
      .then((asset) => { if (active) setOrgSignatureAsset(asset); })
      .catch(() => { if (active) setOrgSignatureAsset(null); });
    return () => { active = false; };
  }, [organization?.signatureUrl]);

  // The employee's own assigned HR (their active primary reporting manager -
  // the same person OfferLetterService.authorizeProcessor requires to be the
  // one preparing this letter, unless a Company Admin is doing it instead).
  // A Company Admin has no EmployeeProfile of their own (see
  // EmployeeManagementService.getOwnDetail), so `organization?.name` above is
  // null for them - this is what lets the signature block still show the
  // real assigned HR's name in that case, instead of falling all the way
  // back to the COMPANY_INFO placeholder.
  useEffect(() => {
    if (!request?.requestedByUserId) { setAssignedHr(null); return; }
    myHrEmployeeApi.get(request.requestedByUserId)
      .then((response) => {
        const employee = response.data.data;
        setAssignedHr(employee.reportingManager || null);
        // The request carries the employee's identity and address, while the
        // Employee Management profile is the source of truth for their phone.
        // Do not replace a value HR has already typed while this lookup loads.
        if (employee.phoneNumber) {
          setForm((current) => (current.employeeMobile ? current : {
            ...current,
            employeeMobile: employee.phoneNumber,
          }));
        }
      })
      .catch(() => setAssignedHr(null));
  }, [request?.requestedByUserId]);

  const orgName = organization?.organizationName || COMPANY_INFO.name;
  const approver = approverLabel(organization?.organizationName);
  const orgAddress = organization?.organizationAddress || COMPANY_INFO.address;
  const orgPhone = organization?.organizationPhoneNumber || COMPANY_INFO.phone;
  const orgWebsite = organization?.organizationWebsite || COMPANY_INFO.website;
  const orgEin = organization?.organizationEinNumber || COMPANY_INFO.ein;
  const orgEVerifyNumber = organization?.organizationEVerifyNumber || COMPANY_INFO.eVerifyId;
  // HR letters are issued by the employee's assigned HR. When a Company
  // Admin prepares a letter, Organization Settings is the source of the
  // administrator's name; do not show the assigned HR as the issuer.
  const issuerName = role === 'COMPANY_ADMIN'
    ? (organization?.organizationAdminName || organization?.name || COMPANY_INFO.hrName)
    : (assignedHr?.name || organization?.name || COMPANY_INFO.hrName);
  const issuerTitle = role === 'COMPANY_ADMIN'
    ? ''
    : (organization?.designation || COMPANY_INFO.hrTitle);

  const isOther = form.designation === OTHER_DESIGNATION;
  const designationOptions = useMemo(() => getDesignationOptions(form.offerType), [form.offerType]);
  const template = useMemo(
    () => getOfferLetterTemplate(form.offerType, form.designation), [form.offerType, form.designation]);
  const designationLabel = isOther ? form.otherDesignation.trim() : getDesignationLabel(form.offerType, form.designation);
  const rolesAndResponsibilities = useMemo(
    () => form.rolesAndResponsibilities.split('\n').map((line) => line.trim()).filter(Boolean),
    [form.rolesAndResponsibilities],
  );
  const standardClauses = useMemo(
    () => getStandardOfferLetterClauses({ name: orgName, ein: orgEin, eVerifyId: orgEVerifyNumber }),
    [orgName, orgEin, orgEVerifyNumber]);

  // Pre-populates the email when the Template & Send step opens. Re-loaded
  // only when the designation or employee name changes, so HR's edits survive
  // a round trip to Back to Details.
  const draftEmployeeName = form.employeeName.trim();
  const draftKey = `${designationLabel || ''}|${draftEmployeeName}`;
  useEffect(() => {
    if (approvalStatus !== 'APPROVED' || delivered || draftKey === emailDraftKeyRef.current) return undefined;
    let active = true;
    let completed = false;
    emailDraftKeyRef.current = draftKey;
    setEmailDraftLoading(true); setEmailDraftNote('');
    myHrDocumentRequestApi.getOfferLetterEmailDraft(requestId, { designation: designationLabel, employeeName: draftEmployeeName })
      .then((response) => {
        if (!active) return;
        const draft = response.data.data;
        setEmail({
          // The employee's current email of record, resolved by the backend
          // from their profile (the request's requestedByEmail is the login
          // account email, which approved profile changes do not update).
          // Falls back to the address on the letter if none is saved.
          recipientEmail: draft.recipientEmail || form.employeeEmail.trim() || '',
          subject: draft.subject || '',
          message: draft.message || '',
          attachmentFileName: draft.attachmentFileName || '',
        });
        setEmailErrors({});
      })
      .catch(() => {
        if (!active) return;
        setEmail((current) => ({ ...current, recipientEmail: current.recipientEmail || form.employeeEmail.trim() }));
        setEmailDraftNote('The email template could not be loaded. Enter the subject and message before sending.');
      })
      .finally(() => {
        completed = true;
        if (active) setEmailDraftLoading(false);
      });
    return () => {
      active = false;
      // Abandoned before it finished (e.g. HR went back to details) - load again next time.
      if (!completed) { emailDraftKeyRef.current = null; setEmailDraftLoading(false); }
    };
    // form.employeeEmail is read once per draft on purpose - the recipient is
    // independently editable afterwards.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [approvalStatus, delivered, draftKey, requestId]);

  const setEmailField = (field) => (event) => {
    const { value } = event.target;
    setEmail((current) => ({ ...current, [field]: value }));
    setEmailErrors((current) => ({ ...current, [field]: undefined }));
  };

  const validateEmail = () => {
    const next = {};
    const recipient = email.recipientEmail.trim();
    if (!recipient) next.recipientEmail = 'Enter the recipient email address.';
    else if (!EMAIL_PATTERN.test(recipient)) next.recipientEmail = 'Enter a valid email address.';
    if (!email.subject.trim()) next.subject = 'Enter an email subject.';
    else if (email.subject.trim().length > 200) next.subject = 'Subject must not exceed 200 characters.';
    if (!email.message.trim()) next.message = 'Enter an email message.';
    setEmailErrors(next);
    return Object.keys(next).length === 0;
  };

  const setField = (field) => (event) => {
    const { type, checked, value: rawValue } = event.target;
    let value = type === 'checkbox' ? checked : rawValue;
    if (type !== 'checkbox' && field === 'payRate') {
      value = sanitizePayRate(rawValue);
    } else if (type !== 'checkbox' && LETTERS_ONLY_FIELDS[field]) {
      value = String(rawValue).replace(LETTERS_ONLY_FIELDS[field], '');
    }
    setForm((current) => {
      const next = { ...current, [field]: value };
      if (field === 'offerType') {
        Object.assign(next, {
          // The backend only accepts the "UNPAID:INTERN" combination today, so
          // Unpaid always implies Intern - there is no employment-category
          // choice to make on that path (see OfferLetterService.validate()).
          employmentCategory: value === 'UNPAID' ? 'INTERN' : '',
          designation: '', otherDesignation: '', rolesAndResponsibilities: '',
          payType: 'hourly', payRate: '', payRateWords: '', payFrequencyNote: 'payable monthly',
          benefitsEligible: false, benefitsText: '',
          endDate: '', internshipSchedule: '',
        });
      }
      if (field === 'designation') {
        next.otherDesignation = '';
        // Standard designations pre-fill the saved template's duties (still
        // editable); "Other" starts blank for HR to write its own duties.
        next.rolesAndResponsibilities = value === OTHER_DESIGNATION
          ? ''
          : (getOfferLetterTemplate(current.offerType, value)?.rolesAndResponsibilities || []).join('\n');
      }
      return next;
    });
    setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const validateStep1 = () => {
    const next = {};
    if (!form.offerType) next.offerType = 'Select an offer type.';
    if (form.offerType === 'PAID') {
      if (!form.employmentCategory) next.employmentCategory = 'Select an employment category.';
      if (!form.payType) next.payType = 'Select a pay type.';
      if (!form.payRate.trim()) next.payRate = 'Enter a pay rate.';
      else if (!PAY_RATE_PATTERN.test(form.payRate.trim())) next.payRate = 'Pay rate may only contain numbers.';
      if (form.payRateWords.trim() && !LETTERS_WORDS_PATTERN.test(form.payRateWords.trim())) {
        next.payRateWords = 'Pay rate in words may only contain letters, spaces, and hyphens.';
      }
      if (form.payFrequencyNote.trim() && !LETTERS_WORDS_PATTERN.test(form.payFrequencyNote.trim())) {
        next.payFrequencyNote = 'Payment schedule may only contain letters, spaces, and hyphens.';
      }
    }
    if (form.offerType === 'UNPAID' && form.endDate && form.joiningDate && form.endDate < form.joiningDate) {
      next.endDate = 'Internship end date cannot be before the joining date.';
    }
    if (!form.designation) next.designation = 'Select a designation.';
    if (isOther && !form.otherDesignation.trim()) next.otherDesignation = 'Enter a designation.';
    else if (isOther && form.otherDesignation.trim() && !PERSON_NAME_PATTERN.test(form.otherDesignation.trim())) {
      next.otherDesignation = 'Designation may only contain letters and spaces.';
    }
    if (form.designation && !form.rolesAndResponsibilities.trim()) next.rolesAndResponsibilities = 'Enter roles & responsibilities.';
    if (!form.employeeName.trim()) next.employeeName = 'Enter the employee name.';
    else if (!PERSON_NAME_PATTERN.test(form.employeeName.trim())) next.employeeName = 'Name may only contain letters and spaces.';
    if (!form.employeeEmail.trim()) next.employeeEmail = 'Enter the employee email.';
    else if (!EMAIL_PATTERN.test(form.employeeEmail.trim())) next.employeeEmail = 'Enter a valid email address.';
    if (!form.employeeMobile.trim()) next.employeeMobile = 'Enter the employee mobile number.';
    else if (!PHONE_PATTERN.test(form.employeeMobile.trim())) next.employeeMobile = 'Enter a valid 10-digit phone number.';
    if (!form.joiningDate) next.joiningDate = 'Select a joining date.';
    if (!form.issueDate) next.issueDate = 'Select an issue date.';
    if (form.joiningDate && form.issueDate && form.joiningDate < form.issueDate) {
      next.joiningDate = 'Joining date cannot be before the issue date.';
    }
    if (!form.workLocation.trim()) next.workLocation = 'Enter a work location.';
    if (!form.streetAddress.trim()) next.streetAddress = 'Enter the street address.';
    if (!form.city.trim()) next.city = 'Enter the city.';
    else if (!PERSON_NAME_PATTERN.test(form.city.trim())) next.city = 'City may only contain letters and spaces.';
    if (!form.state.trim()) next.state = 'Enter the state.';
    else if (!PERSON_NAME_PATTERN.test(form.state.trim())) next.state = 'State may only contain letters and spaces.';
    if (!form.zipCode.trim()) next.zipCode = 'Enter the ZIP code.';
    else if (!ZIP_PATTERN.test(form.zipCode.trim())) next.zipCode = 'Enter a valid 5-digit ZIP code.';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const continueToTemplate = (event) => {
    event.preventDefault();
    if (validateStep1()) setStep(2);
  };

  const offerLetterPayload = useCallback(() => ({
    offerType: form.offerType,
    employmentCategory: form.offerType === 'PAID' ? form.employmentCategory : 'INTERN',
    designation: designationLabel,
    offerDate: form.issueDate,
    startDate: form.joiningDate,
    endDate: form.offerType === 'UNPAID' ? (form.endDate || null) : null,
    workLocation: form.workLocation.trim(),
    reportingManager: null,
    // Duties are kept in previewText above, not here - this field is only
    // ever the short (240-char) internship schedule text, e.g. "20
    // hours/week, Monday-Thursday", never the full selected-template duties.
    internshipSchedule: form.offerType === 'UNPAID' ? (form.internshipSchedule.trim() || null) : null,
    compensationType: form.offerType === 'PAID' ? form.payType.toUpperCase() : null,
    // The server stores a numeric amount. Keep the richer formatted value in
    // the preview, while extracting the amount used in the generated PDF.
    compensationAmount: form.offerType === 'PAID' ? form.payRate.replace(/[^0-9.]/g, '') : null,
    payFrequency: form.offerType === 'PAID' ? 'MONTHLY' : null,
    timesheetRequirement: form.offerType === 'PAID' && form.payType === 'hourly' ? 'REQUIRED' : 'NOT_REQUIRED',
    benefitsEligibility: form.offerType === 'PAID' ? (form.benefitsEligible ? 'ELIGIBLE' : 'NOT_ELIGIBLE') : null,
    issuerName,
    issuerDesignation: issuerTitle,
    previewText: [
      orgName.toUpperCase(),
      `To\n${form.issueDate ? formatDateOnly(form.issueDate) : ''}`,
      `${form.employeeName}\n${form.streetAddress}\n${[form.city, form.state, form.zipCode].filter(Boolean).join(', ')}\nEmail id: ${form.employeeEmail}\nMob: ${form.employeeMobile}`,
      `Dear ${form.employeeName},`,
      `We at ${orgName} are pleased to extend this offer of employment. We are excited that you are joining us, and look forward to a mutually beneficial relationship.`,
      `In accordance with our discussion, following are the terms of your employment with ${endWithPeriod(orgName)} Please review the complete offer and sign the employee acceptance page in Waypoint.`,
      `1. Duties: You will be employed with the title of ${designationLabel} and perform the below duties:\n${rolesAndResponsibilities.map((item) => `• ${item}`).join('\n')}`,
      `2. Compensation: ${form.offerType === 'PAID'
        ? `Your compensation is ${form.payType === 'salary' ? 'an annual salary of' : 'paid at the rate of'} ${form.payRate}${form.payRateWords ? ` (${form.payRateWords})` : ''}${form.payType === 'hourly' ? ' per hour' : ''}, ${form.payFrequencyNote || 'payable per the standard payroll schedule'}. The employment start date is ${form.joiningDate ? formatDateOnly(form.joiningDate) : ''}.${form.payType === 'hourly' ? ' Approved timesheets are required at end of each month for payroll process.' : ''}`
        : `This is an unpaid position. The employment start date is ${form.joiningDate ? formatDateOnly(form.joiningDate) : ''}.`
          + `${form.endDate ? ` The internship is expected to conclude on ${formatDateOnly(form.endDate)}.` : ''}`
          + `${form.internshipSchedule ? ` Schedule: ${form.internshipSchedule}.` : ''}`}`,
      `3. Benefits: ${form.offerType === 'PAID' && form.benefitsEligible
        ? (form.benefitsText.trim() || "You are eligible for the company's standard employee benefits, subject to plan terms and eligibility rules in effect at the time.")
        : 'This role is not eligible for benefits.'}`,
      ...standardClauses.map((clause) => `${clause.number}. ${clause.title}: ${clause.body}${clause.highlight ? ` ${clause.highlight}` : ''}`),
      `Sincerely,\n${orgName}\n${issuerName}${issuerTitle ? `\n${issuerTitle}` : ''}\nDate: ${form.issueDate ? formatDateOnly(form.issueDate) : ''}`,
      `Employee Details and acceptance:\nI agree with the terms stated in this offer letter.\n\nName: ${form.employeeName}\nDate: ${form.issueDate ? formatDateOnly(form.issueDate) : ''}`,
      `${orgName.toUpperCase()} | ${orgAddress} | ${orgPhone} | ${orgWebsite}`,
    ].join('\n\n'),
  }), [form, designationLabel, issuerName, issuerTitle, orgName, orgAddress, orgPhone, orgWebsite, rolesAndResponsibilities, standardClauses]);

  const createCompletedPdf = useCallback(() => {
    const snapshot = offerLetterPayload();
    const pdf = new jsPDF({ unit: 'pt', format: 'letter' });
    pdf.setProperties({ title: 'Offer Letter', subject: snapshot.designation, author: orgName });
    const pageWidth = pdf.internal.pageSize.getWidth();
    const pageHeight = pdf.internal.pageSize.getHeight();
    const margin = 54;
    const width = pageWidth - (margin * 2);
    const contentTop = logoAsset ? 94 : 62;
    const contentBottom = pageHeight - 66;
    const brandColor = [23, 63, 51];
    const mutedColor = [91, 106, 100];
    const logoHeight = 34;
    const logoWidth = logoAsset ? logoHeight * (logoAsset.width / logoAsset.height) : 0;
    const date = formatDateLong(form.issueDate);
    let y = contentTop;

    const drawHeader = () => {
      if (logoAsset) pdf.addImage(logoAsset.dataUrl, logoAsset.format, margin, 34, logoWidth, logoHeight);
      else { pdf.setTextColor(...brandColor); pdf.setFont('helvetica', 'bold'); pdf.setFontSize(13); pdf.text(orgName.toUpperCase(), margin, 50); }
      pdf.setTextColor(0, 0, 0);
      // Leave the font the way every caller expects to find it (normal) -
      // callers that need bold always set it themselves before drawing.
      pdf.setFont('helvetica', 'normal');
    };
    const addPage = () => { pdf.addPage(); drawHeader(); y = contentTop; };
    const ensureSpace = (height) => { if (y + height > contentBottom) addPage(); };
    // Re-sets font/size on every line, not just once before the loop - a
    // page break mid-paragraph runs addPage() -> drawHeader(), which would
    // otherwise leave the wrong font active for the remaining lines.
    const writeLines = (lines, { size = 10.5, lineHeight = 15, x = margin, style = 'normal' } = {}) => {
      for (const line of lines) {
        ensureSpace(lineHeight);
        pdf.setFont('helvetica', style); pdf.setFontSize(size);
        pdf.text(line, x, y);
        y += lineHeight;
      }
    };
    const paragraph = (text, { size = 10.5, style = 'normal', indent = 0, after = 12, lineHeight = 15 } = {}) => {
      pdf.setFont('helvetica', style); pdf.setFontSize(size);
      const lines = pdf.splitTextToSize(text, width - indent);
      writeLines(lines, { size, lineHeight, x: margin + indent, style });
      y += after;
    };
    const bullet = (text) => {
      pdf.setFont('helvetica', 'normal'); pdf.setFontSize(10.5);
      const lines = pdf.splitTextToSize(text, width - 30);
      ensureSpace(30);
      lines.forEach((line, index) => {
        ensureSpace(15);
        if (index === 0) pdf.text('\u2022', margin + 4, y);
        pdf.text(line, margin + 18, y);
        y += 15;
      });
      y += 4;
    };
    // Lays out a sequence of {text, bold} runs as a fully justified
    // paragraph: words are measured and wrapped into lines first, then every
    // line except the paragraph's last has its inter-word gap stretched so
    // both the left AND right edges land flush on the margins - the last
    // line stays left-aligned with a normal space, per standard
    // justified-text convention. Font still switches per word, which is
    // what lets a clause bold a single value (a rate, an ID number) in the
    // middle of a sentence.
    const writeRuns = (runs, { size = 10.5, lineHeight = 15, after = 12 } = {}) => {
      const widthOf = (text, bold) => { pdf.setFont('helvetica', bold ? 'bold' : 'normal'); pdf.setFontSize(size); return pdf.getTextWidth(text); };
      // Word-split the runs' concatenated text, not each run in isolation -
      // a run boundary with no whitespace around it (the trailing "." run
      // after a bolded EIN/ID value) must stay glued to its neighbor word,
      // not become its own word with a space inserted on either side.
      const fullText = runs.map((run) => run.text).join('');
      let offset = 0;
      const boldRanges = runs.map((run) => { const range = { start: offset, end: offset + run.text.length, bold: run.bold }; offset = range.end; return range; });
      const boldAt = (index) => boldRanges.find((range) => index >= range.start && index < range.end)?.bold ?? false;
      const words = [...fullText.matchAll(/\S+/g)].map((match) => ({ text: match[0], bold: boldAt(match.index) }));
      words.forEach((word) => { word.width = widthOf(word.text, word.bold); });
      const spaceWidth = widthOf(' ', false);
      const lines = [];
      let current = [];
      let currentWidth = 0;
      words.forEach((word) => {
        const widthIfAdded = currentWidth + (current.length ? spaceWidth : 0) + word.width;
        if (current.length && widthIfAdded > width) { lines.push(current); current = []; currentWidth = 0; }
        currentWidth += (current.length ? spaceWidth : 0) + word.width;
        current.push(word);
      });
      if (current.length) lines.push(current);
      lines.forEach((line, lineIndex) => {
        ensureSpace(lineHeight);
        const isLastLine = lineIndex === lines.length - 1;
        const wordsWidth = line.reduce((sum, word) => sum + word.width, 0);
        const gapCount = line.length - 1;
        const gap = !isLastLine && gapCount > 0 ? (width - wordsWidth) / gapCount : spaceWidth;
        let x = margin;
        line.forEach((word, wordIndex) => {
          // ensureSpace() above may have paged and redrawn the header, which
          // leaves its own font/size active - reassert this word's before
          // drawing it, or a page break before this line bolds/misizes it.
          pdf.setFont('helvetica', word.bold ? 'bold' : 'normal');
          pdf.setFontSize(size);
          pdf.text(word.text, x, y);
          x += word.width;
          if (wordIndex < line.length - 1) {
            // Draw a real space character (not just skip ahead) so the PDF's
            // text still has one - a viewer's copy/paste would otherwise glue
            // adjacent words together even though they look fine on screen.
            // Its advance is the justified gap, not its own glyph width.
            pdf.text(' ', x, y);
            x += gap;
          }
        });
        y += lineHeight;
      });
      y += after;
    };
    // body is either a plain string (every clause except Compensation and
    // Employment Eligibility) or an array of {text, bold} runs for the two
    // clauses that need part of their sentence bolded (the rate, the ID numbers).
    const clauseParagraph = (number, title, body, { after = 13 } = {}) => {
      const runs = Array.isArray(body) ? body : [{ text: body, bold: false }];
      writeRuns([{ text: `${number}. ${title}: `, bold: true }, ...runs], { after });
    };
    drawHeader();
    pdf.setFont('helvetica', 'normal'); pdf.setFontSize(10); pdf.text('To,', margin, y); pdf.text(date, pageWidth - margin, y, { align: 'right' }); y += 18;
    paragraph(`${form.employeeName}\n${form.streetAddress}\n${[form.city, form.state, form.zipCode].filter(Boolean).join(', ')}\nEmail: ${form.employeeEmail}\nMob: ${form.employeeMobile}`, { after: 18, lineHeight: 14 });
    paragraph(`Dear ${form.employeeName},`, { after: 10 });
    // These two opening paragraphs are prose like the numbered clauses below,
    // so they're justified the same way via writeRuns - everything else
    // above and below them (address block, salutation, signature lines) is
    // short, label-like text where justification doesn't apply.
    writeRuns([{ text: `We at ${orgName} are pleased to extend this offer of employment. We are excited that you are joining us, and look forward to a mutually beneficial relationship.`, bold: false }]);
    writeRuns([{ text: `In accordance with our discussion, following are the terms of your employment with ${endWithPeriod(orgName)} If you agree with them, please initial and sign in the appropriate spaces on each page.`, bold: false }]);
    clauseParagraph(1, 'Duties', [
      { text: 'You will be employed with the title of ', bold: false },
      { text: designationLabel, bold: true },
      { text: ' and perform the below duties:', bold: false },
    ], { after: 7 });
    rolesAndResponsibilities.forEach((item) => bullet(item));
    // The rate, its words spelled out (PAID only), and the employment start
    // date (both cases, every place it's mentioned) are bold; the rest of
    // the sentence stays normal.
    const compensation = form.offerType === 'PAID' ? [
      { text: `Your compensation is ${form.payType === 'salary' ? 'an annual salary of' : 'paid at the rate of'} `, bold: false },
      { text: `$${form.payRate}`, bold: true },
      ...(form.payRateWords ? [{ text: ` (${form.payRateWords})`, bold: true }] : []),
      { text: `${form.payType === 'hourly' ? ' per hour' : ''}, ${form.payFrequencyNote || 'payable monthly'}. The employment start date is `, bold: false },
      { text: formatDateLong(form.joiningDate), bold: true },
      { text: `.${form.payType === 'hourly' ? ' Approved timesheets are required at end of each month for payroll process.' : ''}`, bold: false },
    ] : [
      { text: 'Your compensation is unpaid, as this is an intern role. The employment start date is ', bold: false },
      { text: formatDateLong(form.joiningDate), bold: true },
      { text: `.${form.endDate ? ` The internship is expected to conclude on ${formatDateLong(form.endDate)}.` : ''}${form.internshipSchedule ? ` Schedule: ${form.internshipSchedule}.` : ''}`, bold: false },
    ];
    clauseParagraph(2, 'Compensation', compensation);
    clauseParagraph(3, 'Benefits', form.offerType === 'PAID' && form.benefitsEligible ? (form.benefitsText.trim() || "You are eligible for the company's standard employee benefits, subject to plan terms and eligibility rules in effect at the time.") : 'This role is not eligible for benefits.');
    standardClauses.forEach((clause) => {
      // Clause 12 (Employment Eligibility) is the one clause whose highlight
      // sentence names two labeled values - the Company ID and the EIN -
      // that should stand out bold, label and value together; every other
      // clause's body stays a plain string.
      if (clause.number === 12 && clause.highlight) {
        clauseParagraph(clause.number, clause.title, [
          { text: `${clause.body} Our `, bold: false },
          { text: `E-Verify Company ID is ${orgEVerifyNumber}`, bold: true },
          { text: ' and our ', bold: false },
          { text: `Employment Identification Number (EIN) is ${orgEin}`, bold: true },
          { text: '.', bold: false },
        ]);
      } else {
        clauseParagraph(clause.number, clause.title, `${clause.body}${clause.highlight ? ` ${clause.highlight}` : ''}`);
      }
    });
    // This block's total height (through its closing underline) is reserved
    // below in one go, so it never gets split across a page break. Its
    // internal spacing is kept tight specifically so that reservation is
    // small enough to usually still fit right after the last clause, rather
    // than pushing the whole block onto an otherwise near-empty new page.
    ensureSpace(175);
    paragraph('Sincerely,', { after: 4 });
    paragraph(orgName, { style: 'bold', after: 12 });
    const signatureY = y;
    const rightX = margin + (width / 2) + 12;
    pdf.setDrawColor(194, 207, 201); pdf.setLineWidth(0.6);
    pdf.line(margin, signatureY + 128, margin + 216, signatureY + 128);
    pdf.line(rightX, signatureY + 128, pageWidth - margin, signatureY + 128);
    pdf.setFont('helvetica', 'bold'); pdf.setFontSize(10); pdf.text('Employee Details and acceptance:', rightX, signatureY);
    pdf.setFont('helvetica', 'normal'); pdf.setFontSize(10); pdf.text('I agree with the terms stated in this offer letter.', rightX, signatureY + 18);
    if (orgSignatureAsset) {
      const { natural } = orgSignatureAsset;
      const scale = Math.min(1, 160 / (natural.width * 0.75), 48 / (natural.height * 0.75));
      pdf.addImage(orgSignatureAsset.dataUrl, orgSignatureAsset.format, margin, signatureY + 4, natural.width * 0.75 * scale, natural.height * 0.75 * scale);
    }
    // Keep the printed labels outside their signature images, as in the
    // approved two-column layout. Each image is centered inside its own slot.
    pdf.text('Signature:', margin, signatureY + 66);
    pdf.text('Signature:', rightX, signatureY + 38);
    // This empty field is a placement anchor, not an interactive signing control.
    // The backend reads its rectangle, stamps the employee image there, and removes it from the completed PDF.
    const employeeSignatureField = new pdf.AcroForm.TextField();
    employeeSignatureField.fieldName = 'waypoint_employee_signature';
    employeeSignatureField.x = rightX;
    employeeSignatureField.y = signatureY + 44;
    employeeSignatureField.width = 204;
    employeeSignatureField.height = 42;
    employeeSignatureField.readOnly = true;
    employeeSignatureField.showWhenPrinted = false;
    pdf.addField(employeeSignatureField);
    pdf.text(`${issuerName}`, margin, signatureY + 86);
    if (issuerTitle) {
      pdf.text(`${issuerTitle}`, margin, signatureY + 100);
    }
    pdf.text(`Date: ${date}`, margin, signatureY + (issuerTitle ? 114 : 98));
    pdf.text(`Name: ${form.employeeName}`, rightX, signatureY + 96);
    pdf.text(`Date: ${form.issueDate ? formatDateLong(form.issueDate) : '[Issue Date]'}`, rightX, signatureY + 112);

    const footerY = pageHeight - 38;
    const footerText = `${orgName.toUpperCase()} | ${orgAddress} | ${orgPhone} | ${orgWebsite}`;
    const totalPages = pdf.internal.getNumberOfPages();
    for (let pageNumber = 1; pageNumber <= totalPages; pageNumber += 1) {
      pdf.setPage(pageNumber);
      pdf.setDrawColor(207, 222, 216); pdf.setLineWidth(0.5); pdf.line(margin, footerY - 14, pageWidth - margin, footerY - 14);
      pdf.setFont('helvetica', 'normal'); pdf.setFontSize(7.5); pdf.setTextColor(...mutedColor);
      pdf.text(footerText, margin, footerY);
    }

    return new File([pdf.output('blob')], 'offer-letter.pdf', { type: 'application/pdf' });
  }, [offerLetterPayload, orgName, logoAsset, form, orgAddress, orgPhone, orgWebsite, orgEin, orgEVerifyNumber, issuerName, issuerTitle, designationLabel, rolesAndResponsibilities, standardClauses, orgSignatureAsset]);

  // The PDF shown here is also the one uploaded by sendOfferLetter. Keeping a
  // single file removes the previous split between the HTML review layout and
  // a separately generated document the employee received.
  useEffect(() => {
    if (step !== 2 || sent || ['PENDING_APPROVAL', 'APPROVED', 'SENT'].includes(approvalStatus)) {
      setPreviewFile(null);
      setPreviewUrl('');
      setPreparingPreview(false);
      return undefined;
    }

    let objectUrl = '';
    setPreparingPreview(true);
    setPreviewError('');
    try {
      const file = createCompletedPdf();
      objectUrl = URL.createObjectURL(file);
      setPreviewFile(file);
      setPreviewUrl(objectUrl);
    } catch {
      setPreviewFile(null);
      setPreviewUrl('');
      setPreviewError('The offer letter preview could not be generated. Please return to the details and try again.');
    } finally {
      setPreparingPreview(false);
    }

    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [step, sent, approvalStatus, createCompletedPdf]);

  const apiErrorMessage = (error, fallback) => {
    const fieldErrors = error?.response?.data?.data;
    const detail = fieldErrors && typeof fieldErrors === 'object'
      ? Object.entries(fieldErrors).map(([field, message]) => `${field}: ${message}`).join('; ')
      : '';
    return detail || error?.response?.data?.message || fallback;
  };

  // Step 1 of the approval workflow: HR submits the generated PDF and the letter facts for Company Admin approval.
  // Nothing is sent to the employee at this point.
  const submitForApproval = async () => {
    if (!organization?.signatureUrl) {
      setSendError('Your signature is not configured. Add your signature on your Profile page before submitting this offer letter.');
      setStep(1);
      return;
    }
    if (!previewFile || preparingPreview) {
      setSendError(previewError || 'The offer letter preview is still being prepared. Please wait a moment and try again.');
      return;
    }
    setSending(true); setSendError('');
    try {
      const response = await myHrDocumentRequestApi.submitOfferLetterForApproval(requestId, {
        employeeName: form.employeeName.trim(),
        designation: designationLabel,
        offerType: titleCase(form.offerType),
        employmentCategory: form.employmentCategory ? titleCase(form.employmentCategory) : '',
        joiningDate: form.joiningDate,
        issueDate: form.issueDate,
        payRate: form.offerType === 'PAID' && form.payRate ? `${form.payRate} ${form.payType === 'salary' ? '(annual salary)' : 'per hour'}` : '',
        workLocation: form.workLocation.trim(),
        rolesAndResponsibilities: form.rolesAndResponsibilities,
        formJson: JSON.stringify(form),
      }, previewFile);
      setApproval(response.data.data);
    } catch (error) {
      setSendError(apiErrorMessage(error, 'The offer letter could not be submitted for approval. Please try again.'));
    } finally {
      setSending(false);
    }
  };

  // Step 2, only once the Admin approved it: deliver the approved letter to the employee in WayPoint (once, the server
  // refuses anything that is not approved), then send the existing email with the PDF attached. A failed email can be
  // retried without delivering the letter a second time.
  const sendOfferLetter = async () => {
    if (!validateEmail()) return;
    setSending(true); setSendError('');
    try {
      if (!delivered) {
        try {
          await myHrDocumentRequestApi.deliverOfferLetter(requestId);
        } catch (error) {
          setSendError(apiErrorMessage(error, 'Offer letter could not be sent. Please try again.'));
          return;
        }
        setDelivered(true);
      }
      const recipientEmail = email.recipientEmail.trim();
      try {
        const emailResponse = await myHrDocumentRequestApi.emailOfferLetter(requestId, {
          recipientEmail, subject: email.subject.trim(), message: email.message,
          // Same value the PDF was generated from, so the attachment name matches the letter.
          employeeName: form.employeeName.trim(),
        });
        if (emailResponse?.status === 202) setEmailNotice(emailResponse?.data?.message || '');
      } catch (error) {
        const reason = apiErrorMessage(error, 'The email could not be sent. Please try again.');
        setSendError(`The offer letter was stored and shared with ${form.employeeName} in WayPoint, but email delivery failed. ${reason}`);
        return;
      }
      setEmailedTo(recipientEmail);
      setSent(true);
    } finally {
      setSending(false);
    }
  };

  if (loading) return <MyHrFeaturePage className="phase-six" title="Prepare Offer Letter" backTo={detailPath}><p role="status">Loading offer letter request…</p></MyHrFeaturePage>;
  if (loadError || !request) return <MyHrFeaturePage className="phase-six" title="Prepare Offer Letter" backTo={detailPath}><p role="alert" className="document-requests__error">{loadError || 'Offer letter request could not be loaded.'}</p></MyHrFeaturePage>;

  const banner = (variant, label, message, extra = null) => (
    <div className={`offer-approval__banner offer-approval__banner--${variant}`} role="status">
      <span className="offer-approval__badge">{label}</span>
      <p>{message}</p>
      {extra}
    </div>
  );
  const approvedPdf = approvedPreviewUrl ? (
    <div className="offer-letter__pdf-preview" aria-label="Approved offer letter">
      <iframe title="Approved offer letter PDF" src={approvedPreviewUrl} />
      <a href={approvedPreviewUrl} target="_blank" rel="noreferrer">Open the offer letter in a new tab</a>
    </div>
  ) : null;
  const backToRequest = <footer><Button type="button" onClick={() => navigate(detailPath)}>Back to Request</Button></footer>;
  let statusPanel = null;
  if (approvalStatus === 'PENDING_APPROVAL') {
    statusPanel = <div className="request-documents__review offer-letter__template">
      {banner('pending', 'PENDING ADMIN APPROVAL', `This Offer Letter has been submitted to the ${approver} for approval. You can send it to the employee only after approval.`)}
      {approvedPdf}
      {backToRequest}
    </div>;
  } else if (approvalStatus === 'SENT') {
    statusPanel = <div className="request-documents__review offer-letter__template">
      {banner('sent', 'SENT', 'This Offer Letter has been sent to the employee.')}
      {backToRequest}
    </div>;
  } else if (approvalStatus === 'APPROVED' && sent) {
    statusPanel = <div className="request-documents__review offer-letter__template"><div className="offer-letter__sent">
      <Toast variant="success"
        message={emailNotice
          ? `Offer letter sent to ${form.employeeName} in WayPoint. ${emailNotice}`
          : `Offer letter sent to ${form.employeeName} and emailed to ${emailedTo} with the PDF attached. It now awaits their signature and signed return before this request can be completed.`} />
      {backToRequest}
    </div></div>;
  } else if (approvalStatus === 'APPROVED') {
    statusPanel = <div className="request-documents__review offer-letter__template">
      {/* A Company Admin's own letter needs no approval, so there is nothing to report to them. */}
      {role !== 'COMPANY_ADMIN' && banner('approved', 'APPROVED', `This Offer Letter has been approved by the ${approver} and is ready to be sent to the employee.`)}
      {approvedPdf}
      <section className="offer-letter__send" aria-label="Offer letter email">
                <h3>Send Offer Letter</h3>
                <p className="offer-letter__send-intro">
                  The offer letter is shared with the employee in WayPoint and emailed with the PDF attached.
                  Changes here apply to this email only.
                </p>
                {emailDraftLoading && <p role="status" className="offer-letter__send-note">Loading email template…</p>}
                {emailDraftNote && <p className="offer-letter__send-note">{emailDraftNote}</p>}
                <EmailInput name="recipientEmail" label="Recipient Email" required value={email.recipientEmail}
                  onChange={setEmailField('recipientEmail')} error={emailErrors.recipientEmail}
                  hint="Pre-filled from the employee's saved email. Editing it does not change their profile." />
                <TextInput name="emailSubject" label="Subject" required value={email.subject}
                  onChange={setEmailField('subject')} error={emailErrors.subject} maxLength={200} />
                <TextArea name="emailMessage" label="Message" required value={email.message}
                  onChange={setEmailField('message')} error={emailErrors.message} rows={16} maxLength={20000}
                  hint="Plain text. Wrap words in **double asterisks** for bold; email addresses and links become clickable." />
                <div className="offer-letter__attachment">
                  <span className="offer-letter__attachment-label">Attachment</span>
                  <span className="offer-letter__attachment-file">
                    <strong>Offer Letter PDF</strong>
                    <span>{email.attachmentFileName || 'offer-letter.pdf'}</span>
                  </span>
                </div>
                <footer>
                  <Button type="button" loading={sending} disabled={emailDraftLoading} onClick={sendOfferLetter}>
                    {delivered && !sending ? 'Retry Sending Email' : 'Send Offer Letter'}
                  </Button>
                </footer>
              </section>
    </div>;
  }

  return <MyHrFeaturePage className="phase-six" title="Prepare Offer Letter" description="Build and send the offer letter for this request." fullWidth backTo={detailPath}>
    <div className="document-workflow-layout">
      <DocumentRoute request={request} needsApproval={role === 'HR'} preparationStep={step} sent={sent} approvalStatus={approvalStatus} nextAction={sent ? undefined : approvalStatus === 'APPROVED' ? 'Send approved document' : undefined} />
    <section className="request-documents__card">
      <header><div><span>HUMAN RESOURCES</span><h2>PREPARE OFFER LETTER</h2></div></header>
      {sendError && <p role="alert" className="document-requests__error">{sendError}</p>}
      {approvalStatus === 'REJECTED' && banner('rejected', 'ADMIN APPROVAL REJECTED',
        `The ${approver} rejected this offer letter. Correct it and submit it for approval again.`,
        approval?.rejectionReason ? <p className="offer-approval__reason"><strong>Reason:</strong> {approval.rejectionReason}</p> : null)}

      {statusPanel || (step === 1 ? (
        <form className="request-documents__form" onSubmit={continueToTemplate} noValidate>
          <div className="request-documents__grid">
            <Dropdown name="offerType" label="Offer Type" required value={form.offerType} onChange={setField('offerType')}
              placeholder="Select offer type" error={errors.offerType} options={OFFER_TYPES} />
            <Dropdown name="designation" label="Designation" required value={form.designation} onChange={setField('designation')}
              disabled={!form.offerType} placeholder={form.offerType ? 'Select designation' : 'Select offer type first'}
              error={errors.designation} options={designationOptions} />
            {isOther && <TextInput name="otherDesignation" label="Other Designation" required value={form.otherDesignation}
              onChange={setField('otherDesignation')} error={errors.otherDesignation} maxLength={160} />}
          </div>

          {form.designation && <div className="request-documents__grid">
            <div className="request-documents__wide">
              <TextArea name="rolesAndResponsibilities" label="Roles &amp; Responsibilities" required
                value={form.rolesAndResponsibilities} onChange={setField('rolesAndResponsibilities')}
                hint="One responsibility per line. Standard designations are pre-filled from the saved template - edit as needed."
                rows={6} error={errors.rolesAndResponsibilities} />
            </div>
          </div>}

          <h3 className="offer-letter__section-title">Employee Information</h3>
          <div className="request-documents__grid">
            <TextInput name="employeeName" label="Employee Name" required value={form.employeeName}
              onChange={setField('employeeName')} error={errors.employeeName} maxLength={160} />
            <EmailInput name="employeeEmail" label="Employee Email" required value={form.employeeEmail}
              onChange={setField('employeeEmail')} error={errors.employeeEmail} />
            <PhoneInput name="employeeMobile" label="Employee Mobile" required value={form.employeeMobile}
              onChange={setField('employeeMobile')} error={errors.employeeMobile} />
            <TextInput name="joiningDate" label="Joining Date" type="date" required value={form.joiningDate}
              onChange={setField('joiningDate')} error={errors.joiningDate} min={form.issueDate || undefined} />
            <TextInput name="issueDate" label="Issue Date" type="date" required value={form.issueDate}
              onChange={setField('issueDate')} error={errors.issueDate} />
            <TextInput name="workLocation" label="Work Location" required value={form.workLocation}
              onChange={setField('workLocation')} error={errors.workLocation} maxLength={240} />
          </div>

          <h3 className="offer-letter__section-title">Employee Address</h3>
          <div className="request-documents__grid">
            <div className="request-documents__wide">
              <TextInput name="streetAddress" label="Street Address" required value={form.streetAddress}
                onChange={setField('streetAddress')} error={errors.streetAddress} maxLength={240} />
            </div>
            <TextInput name="city" label="City" required value={form.city} onChange={setField('city')}
              error={errors.city} maxLength={100} />
            <TextInput name="state" label="State" required value={form.state} onChange={setField('state')}
              error={errors.state} maxLength={100} />
            <TextInput name="zipCode" label="ZIP Code" required value={form.zipCode} onChange={setField('zipCode')}
              error={errors.zipCode} />
          </div>

          {form.offerType === 'PAID' && (
            <>
              <h3 className="offer-letter__section-title">Compensation &amp; Benefits</h3>
              <div className="request-documents__grid">
                <Dropdown name="employmentCategory" label="Employment Category" required
                  value={form.employmentCategory} onChange={setField('employmentCategory')} placeholder="Select employment category"
                  error={errors.employmentCategory} options={EMPLOYMENT_CATEGORIES} />
                <Dropdown name="payType" label="Pay Type" required value={form.payType} onChange={setField('payType')}
                  error={errors.payType} options={PAY_TYPES} />
                <TextInput name="payRate" label="Pay Rate" required value={form.payRate} onChange={setField('payRate')}
                  placeholder={form.payType === 'salary' ? 'e.g. 95000' : 'e.g. 45'} error={errors.payRate}
                  maxLength={20} />
                <TextInput name="payRateWords" label="Pay Rate in Words" value={form.payRateWords}
                  onChange={setField('payRateWords')} placeholder="e.g. Ninety-Five Thousand Dollars"
                  error={errors.payRateWords} maxLength={200} />
                <TextInput name="payFrequencyNote" label="Payment Schedule" value={form.payFrequencyNote}
                  onChange={setField('payFrequencyNote')} placeholder="e.g. payable monthly"
                  error={errors.payFrequencyNote} maxLength={120} />
              </div>
              <div className="request-documents__grid">
                <div>
                  <Checkbox name="benefitsEligible" label="Eligible for benefits" checked={form.benefitsEligible}
                    onChange={setField('benefitsEligible')} />
                </div>
                <div className="request-documents__wide">
                  <TextArea name="benefitsText" label="Benefits Summary" value={form.benefitsText}
                    onChange={setField('benefitsText')} rows={3}
                    hint="Shown in the letter when the employee is eligible for benefits; leave blank to use the standard wording."
                    placeholder="Eligible for medical, dental and vision coverage, and the company's 401(k) plan, per current policy." />
                </div>
              </div>
            </>
          )}

          {form.offerType === 'UNPAID' && (
            <>
              <h3 className="offer-letter__section-title">Internship Details</h3>
              <div className="request-documents__grid">
                <TextInput name="endDate" label="Internship End Date" type="date" value={form.endDate}
                  onChange={setField('endDate')} error={errors.endDate}
                  hint="Optional - leave blank for an open-ended internship." />
                <TextInput name="internshipSchedule" label="Schedule" value={form.internshipSchedule}
                  onChange={setField('internshipSchedule')} placeholder="e.g. 20 hours/week, Monday-Thursday" />
              </div>
            </>
          )}

          {organization === null ? null : organization.signatureUrl ? (
            <p className="offer-letter__org-signature-status offer-letter__org-signature-status--ready">✓ Your signature is configured</p>
          ) : (
            <p className="offer-letter__org-signature-status offer-letter__org-signature-status--missing">Your signature is not configured. Add your signature on your Profile page before sending this offer letter.</p>
          )}

          <footer>
            <Button type="button" variant="secondary" onClick={() => navigate(detailPath)}>Cancel</Button>
            <Button type="submit">Continue</Button>
          </footer>
        </form>
      ) : (
        <div className="request-documents__review offer-letter__template">
          <>
              <h3>SELECTED TEMPLATE</h3>
              <div className="offer-letter__template-badge">{template ? template.name : `Custom ${designationLabel || 'Offer'} Letter`}</div>

              <div className="offer-letter__pdf-preview" aria-label="Offer letter preview">
                {preparingPreview ? <p role="status">Preparing the PDF preview…</p> : previewUrl ? <>
                  <iframe title="Offer letter PDF preview" src={previewUrl} />
                  <a href={previewUrl} target="_blank" rel="noreferrer">Open preview in a new tab</a>
                </> : <p role="alert">{previewError || 'The offer letter preview is unavailable.'}</p>}
              </div>

              {role === 'COMPANY_ADMIN' ? (
                // The Admin is the approver: submitting approves it on the server and moves straight to sending.
                <section className="offer-letter__send" aria-label="Finalize offer letter">
                  <h3>Finalize Offer Letter</h3>
                  <p className="offer-letter__send-intro">Review the offer letter above, then continue to email it to the employee.</p>
                  <footer>
                    <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                    <Button type="button" loading={sending} disabled={preparingPreview || !previewFile} onClick={submitForApproval}>
                      Continue to Send
                    </Button>
                  </footer>
                </section>
              ) : (
                <section className="offer-letter__send" aria-label="Submit for Admin approval">
                  <h3>Submit for Admin Approval</h3>
                  <p className="offer-letter__send-intro">
                    This offer letter must be approved by the {approver} before it can be sent to the employee.
                    Submit it for approval - you will be notified when the Admin approves or rejects it.
                  </p>
                  <footer>
                    <Button type="button" variant="secondary" onClick={() => setStep(1)}>Back to Details</Button>
                    <Button type="button" loading={sending} disabled={preparingPreview || !previewFile} onClick={submitForApproval}>
                      {approvalStatus === 'REJECTED' ? 'Resubmit for Approval' : 'Submit for Admin Approval'}
                    </Button>
                  </footer>
                </section>
              )}
          </>
        </div>
      ))}
    </section>
    </div>
  </MyHrFeaturePage>;
}
