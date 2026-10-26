import { formatDateOnly } from '../../../../../utils/dateFormat';
import { formatUsPhone } from '../../../../../utils/phoneFormat';
import endWithPeriod from '../../../../../utils/endWithPeriod';

/**
 * Prototype-level field/template/preview config for every generic document
 * type (everything except Offer Letter, which has its own dedicated page).
 * This is intentionally data, not components: each type is a flat field
 * list plus a preview-paragraph builder, rendered by the one generic
 * DocumentPreparationPage. These fields are a prototype starting point only
 * - not a legal/immigration-complete requirement list.
 *
 * signedByAuthorizedSignatory marks a letter whose signature block names the
 * company admin (the Organization Settings admin account): HR never signs it. The PDF is submitted
 * unsigned and the server applies the signatory's stored signature when the
 * signatory (and only the signatory) approves it.
 */

const EMPLOYMENT_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ON_LEAVE', label: 'On Leave' },
  { value: 'TERMINATED', label: 'Terminated' },
];

const statusLabel = (value) => EMPLOYMENT_STATUS_OPTIONS.find((option) => option.value === value)?.label || '';
const dateOrPlaceholder = (value, placeholder) => (value ? formatDateOnly(value) : placeholder);
const articleFor = (word) => (/^[aeiou]/i.test(word) ? 'an' : 'a');

// Chosen by HR per letter (never inferred from the name); drives both the
// "Mr./Ms." prefix and the pronouns in the Experience and Relieving Letter.
const NEUTRAL_PRONOUNS = { subject: 'they', possessive: 'their', object: 'them' };
const SALUTATION_OPTIONS = [
  { value: 'MR', label: 'Mr.', prefix: 'Mr. ', pronouns: { subject: 'he', possessive: 'his', object: 'him' } },
  { value: 'MS', label: 'Ms.', prefix: 'Ms. ', pronouns: { subject: 'she', possessive: 'her', object: 'her' } },
  { value: 'NONE', label: 'No title (they/their)', prefix: '', pronouns: NEUTRAL_PRONOUNS },
];

const SIGNATORY_TITLE = 'President';

// Organization Settings' Payroll Period (PayrollPeriod enum) -> pay frequency label.
const PAY_FREQUENCY_LABELS = { WEEKLY: 'Weekly', BIWEEKLY: 'Biweekly', MONTHLY: 'Monthly' };

const capitalize = (word) => word.charAt(0).toUpperCase() + word.slice(1);
const possessiveOf = (name) => (/s$/i.test(name) ? `${name}'` : `${name}'s`);
/** '95,000' or '$95,000' -> '$95,000', so HR can type the amount either way. */
const withDollar = (amount) => (String(amount).trim().startsWith('$') ? String(amount).trim() : `$${String(amount).trim()}`);

// Signatory name, email and mobile (admin contact number) come from
// Organization Settings (a line is omitted when that setting is empty); the
// title is fixed for now.
const authorisedSignatoryLines = ({ adminName, adminEmail, adminPhone }) => [
  'Authorised Signatory',
  ...(adminName ? [`Name: ${adminName}`] : []),
  `Title: ${SIGNATORY_TITLE}`,
  ...(adminEmail ? [`Email: ${adminEmail}`] : []),
  ...(adminPhone ? [`Mobile: ${formatUsPhone(adminPhone)}`] : []),
];

// 'ORG INC. | <ADDRESS> | <PHONE> | WWW.<SITE>' from Organization Settings -
// blank parts are skipped rather than left as empty separators.
const letterheadFooter = ({ orgName, orgAddress, orgPhone, orgWebsite }) => [
  endWithPeriod(orgName), orgAddress, orgPhone && formatUsPhone(orgPhone), orgWebsite && orgWebsite.replace(/^https?:\/\//i, ''),
].filter(Boolean).join(' | ').toUpperCase();

// 'Sincerely, <Org>.' + Authorised Signatory block + letterhead footer, shared
// by the letters that follow the organization's signed-letter format.
const signatoryClosingWithFooter = (context) => ({
  beforeSignature: ['Sincerely,', endWithPeriod(context.orgName)],
  afterSignature: authorisedSignatoryLines(context),
  footer: letterheadFooter(context),
});

// Employee profile employmentType (EmploymentType enum) -> 'on a ... basis' wording.
const EMPLOYMENT_BASIS_LABELS = { FULL_TIME: 'full-time', PART_TIME: 'part-time', CONTRACT: 'contract', INTERNSHIP: 'internship' };

export const DOCUMENT_TYPE_CONFIGS = {
  // Start date and employment type come straight from the employee profile
  // and the company address from Organization Settings, so HR only confirms
  // the salutation and designation and writes the role description.
  EMPLOYMENT_VERIFICATION: {
    templateName: 'Employment Verification Letter Template',
    fields: [
      { name: 'salutation', label: 'Salutation', type: 'select', required: true, options: SALUTATION_OPTIONS },
      { name: 'designation', label: 'Designation', type: 'text', required: true, profileField: 'designation' },
      {
        name: 'roleDescription',
        label: 'Role Description',
        type: 'textarea',
        required: true,
        placeholder: 'e.g. Software Development, Application Development, Software Engineering, Systems Analysis, '
          + 'Database and Storage Administration, and related computer consulting services to corporate clients, '
          + 'primarily Fortune 500 companies.',
        hint: "Completes '<Organization> ... is engaged in providing ...' in the letter.",
      },
    ],
    preview: (form, orgName, { employeeStartDate, employmentType, orgAddress } = {}) => {
      const salutation = SALUTATION_OPTIONS.find((option) => option.value === form.salutation);
      const { subject, possessive } = salutation?.pronouns || NEUTRAL_PRONOUNS;
      const name = `${salutation?.prefix || ''}${form.employeeName || '[Employee Name]'}`;
      const basis = EMPLOYMENT_BASIS_LABELS[employmentType] || '[Employment Type]';
      const roleDescription = (form.roleDescription || '').replace(/\s+/g, ' ').trim();
      return [
        { type: 'p', text: `Date: ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}` },
        { type: 'heading', text: 'TO WHOM IT MAY CONCERN' },
        { type: 'heading', text: 'Employment Verification Letter' },
        {
          type: 'p',
          text: `This letter is to confirm that ${name} has been employed with us at ${orgName} since `
            + `${dateOrPlaceholder(employeeStartDate, '[Employment Start Date]')}, on ${articleFor(basis)} ${basis} basis `
            + `in the position of ${form.designation || '[Designation]'}.`,
        },
        {
          type: 'p',
          text: `${orgName} anticipates continuing ${possessiveOf(name)} employment for the duration of ${possessive} `
            + 'authorized employment status and possibly beyond, subject to business needs and mutual agreement.',
        },
        {
          type: 'p',
          // One paragraph, as in the template; line breaks pasted into the
          // role description are collapsed so the PDF doesn't break mid-sentence.
          text: `${capitalize(subject)} ${subject === 'they' ? 'are' : 'is'} currently an employee in good standing with the company `
            + `${orgName}, located at ${orgAddress || '[Organization Address]'}, is engaged in providing `
            + `${roleDescription ? endWithPeriod(roleDescription) : '[Role Description].'}`,
        },
        { type: 'p', text: 'Please feel free to contact us for any further information you need.' },
      ];
    },
    closing: signatoryClosingWithFooter,
    signedByAuthorizedSignatory: true,
  },

  EMPLOYMENT_LETTER: {
    templateName: 'Standard Employment Letter Template',
    fields: [
      { name: 'designation', label: 'Designation', type: 'text', required: true },
      { name: 'employmentStartDate', label: 'Employment Start Date', type: 'date', required: true },
      { name: 'employmentStatus', label: 'Employment Status', type: 'select', required: true, options: EMPLOYMENT_STATUS_OPTIONS },
      { name: 'workLocation', label: 'Work Location', type: 'text', required: true },
      { name: 'purpose', label: 'Purpose', type: 'text', required: false, placeholder: 'e.g. Visa application, loan application' },
    ],
    preview: (form) => [
      { type: 'p', text: 'To Whom It May Concern,' },
      {
        type: 'p',
        text: `This letter confirms that ${form.employeeName || '[Employee Name]'} is employed with our organization as `
          + `${form.designation || '[Designation]'}, starting from ${dateOrPlaceholder(form.employmentStartDate, '[Employment Start Date]')}. `
          + `The employee's current status is ${statusLabel(form.employmentStatus) || '[Employment Status]'}, based at ${form.workLocation || '[Work Location]'}.`
          + `${form.purpose ? ` This letter is issued for the purpose of ${form.purpose}.` : ''}`,
      },
      { type: 'p', text: `This letter is issued on ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}.` },
    ],
  },

  // One merged template for both Experience and Relieving Letter requests
  // (see documentWorkflowTypes.js). profileField names the
  // EmployeeDetailResponse property DocumentPreparationPage pre-fills the
  // field from, so HR doesn't retype what the employee profile already has.
  EXPERIENCE_LETTER: {
    templateName: 'Experience and Relieving Letter Template',
    fields: [
      { name: 'salutation', label: 'Salutation', type: 'select', required: true, options: SALUTATION_OPTIONS },
      { name: 'designation', label: 'Designation', type: 'text', required: true, profileField: 'designation' },
      { name: 'employmentStartDate', label: 'Employment Start Date', type: 'date', required: true, profileField: 'startDate' },
      { name: 'lastWorkingDate', label: 'Last Working Date', type: 'date', required: true },
    ],
    preview: (form, orgName) => {
      const salutation = SALUTATION_OPTIONS.find((option) => option.value === form.salutation);
      const { subject, possessive, object } = salutation?.pronouns || NEUTRAL_PRONOUNS;
      const name = `${salutation?.prefix || ''}${form.employeeName || '[Employee Name]'}`;
      const designation = form.designation || '[Designation]';
      const lastWorkingDate = dateOrPlaceholder(form.lastWorkingDate, '[Last Working Date]');
      return [
        { type: 'p', text: `Date: ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}` },
        { type: 'heading', text: 'Experience and Relieving Letter' },
        { type: 'heading', text: 'TO WHOM IT MAY CONCERN' },
        {
          type: 'p',
          text: `This letter is to certify that ${name} was employed with ${orgName} as ${articleFor(designation)} ${designation} `
            + `from ${dateOrPlaceholder(form.employmentStartDate, '[Employment Start Date]')} to ${lastWorkingDate}. `
            + `During ${possessive} employment, ${subject} performed ${possessive} duties sincerely and responsibly, `
            + 'and contributed positively to the organization.',
        },
        {
          type: 'p',
          text: `This is also to confirm that ${name} has been relieved from the services of the company with effect from ${lastWorkingDate}.`,
        },
        { type: 'p', text: `We wish ${object} all the very best in ${possessive} future endeavors.` },
      ];
    },
    closing: (context) => ({
      beforeSignature: ['Sincerely,', endWithPeriod(context.orgName)],
      afterSignature: authorisedSignatoryLines(context),
    }),
    signedByAuthorizedSignatory: true,
  },

  SALARY_VERIFICATION: {
    templateName: 'Salary Verification Letter Template',
    fields: [
      { name: 'salutation', label: 'Salutation', type: 'select', required: true, options: SALUTATION_OPTIONS },
      { name: 'designation', label: 'Designation', type: 'text', required: true, profileField: 'designation' },
      { name: 'employmentStartDate', label: 'Employment Start Date', type: 'date', required: true, profileField: 'startDate' },
      { name: 'annualSalary', label: 'Annual Salary', type: 'text', required: true, placeholder: 'e.g. 95,000' },
    ],
    // Pay frequency is not typed per letter - it is the organization's
    // Payroll Period from Organization Settings.
    preview: (form, orgName, { payrollPeriod } = {}) => {
      const salutation = SALUTATION_OPTIONS.find((option) => option.value === form.salutation);
      const { subject } = salutation?.pronouns || NEUTRAL_PRONOUNS;
      const name = `${salutation?.prefix || ''}${form.employeeName || '[Employee Name]'}`;
      const designation = form.designation || '[Designation]';
      const payFrequency = PAY_FREQUENCY_LABELS[payrollPeriod] || '[Payroll Period - set in Organization Settings]';
      return [
        { type: 'p', text: `Date: ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}` },
        { type: 'heading', text: 'Salary Verification Letter' },
        { type: 'heading', text: 'TO WHOM IT MAY CONCERN' },
        {
          type: 'p',
          text: `This letter is to verify that ${name} is employed with ${orgName} as ${articleFor(designation)} ${designation}. `
            + `${capitalize(subject)} ${subject === 'they' ? 'have' : 'has'} been employed with our company since `
            + `${dateOrPlaceholder(form.employmentStartDate, '[Employment Start Date]')}.`,
        },
        { type: 'p', text: `As of the date of this letter, ${name} receives the following compensation:` },
        {
          type: 'list',
          items: [
            `Annual Salary: ${form.annualSalary ? withDollar(form.annualSalary) : '[Annual Salary]'}`,
            `Pay Frequency: ${payFrequency}`,
          ],
        },
        {
          type: 'p',
          text: `Should you require any additional information regarding ${possessiveOf(name)} employment or compensation, `
            + 'please contact us using the information below.',
        },
      ];
    },
    closing: signatoryClosingWithFooter,
    signedByAuthorizedSignatory: true,
  },

  I983: {
    templateName: 'Standard Signed I-983 Template',
    fields: [
      { name: 'designation', label: 'Job Title / Designation', type: 'text', required: true },
      { name: 'workLocation', label: 'Work Location', type: 'text', required: true },
      { name: 'employmentStartDate', label: 'Training Start Date', type: 'date', required: true },
      { name: 'trainingDetails', label: 'Training Plan Details', type: 'textarea', required: false, hint: 'Prototype only - a summary of the STEM OPT training plan.' },
    ],
    preview: (form) => [
      {
        type: 'p',
        text: `Form I-983 (Training Plan for STEM OPT Students) summary for ${form.employeeName || '[Employee Name]'}, job title `
          + `${form.designation || '[Job Title / Designation]'}, work location ${form.workLocation || '[Work Location]'}, `
          + `training start date ${dateOrPlaceholder(form.employmentStartDate, '[Training Start Date]')}.`,
      },
      ...(form.trainingDetails ? [{ type: 'p', text: form.trainingDetails }] : []),
      { type: 'p', text: `Prepared on ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}. This is a prototype summary, not the legal I-983 filing.` },
    ],
  },

  OTHER: {
    templateName: 'Custom Employment Document Template',
    fields: [
      { name: 'documentTitle', label: 'Document Title', type: 'text', required: true, placeholder: 'e.g. Bonus Letter' },
      { name: 'details', label: 'Document Details', type: 'textarea', required: true, hint: 'What this document needs to say.' },
    ],
    preview: (form) => [
      { type: 'p', text: 'To Whom It May Concern,' },
      {
        type: 'p',
        text: `This is a ${form.documentTitle || '[Document Title]'} issued to ${form.employeeName || '[Employee Name]'} `
          + `on ${dateOrPlaceholder(form.issueDate, '[Issue Date]')}.`,
      },
      ...(form.details ? [{ type: 'p', text: form.details }] : []),
    ],
  },
};
