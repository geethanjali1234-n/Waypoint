/**
 * Prototype-only Offer Letter template data. No backend template storage
 * exists yet - this mirrors the "saved company documents" concept from the
 * design doc as a static lookup, keyed by offer type + designation, until a
 * real template service is built.
 */

export const OFFER_TYPES = [
  { value: 'PAID', label: 'Paid' },
  { value: 'UNPAID', label: 'Unpaid' },
];

export const EMPLOYMENT_CATEGORIES = [
  { value: 'INTERN', label: 'Intern' },
  { value: 'FULL_TIME', label: 'Full-time' },
  { value: 'PART_TIME', label: 'Part-time' },
  { value: 'CONTRACT', label: 'Contract' },
];

export const PAY_TYPES = [
  { value: 'hourly', label: 'Hourly' },
  { value: 'salary', label: 'Annual Salary' },
];

export const OTHER_DESIGNATION = 'OTHER';

/**
 * Prototype-only company profile for the fixed portions of the letter
 * (letterhead, signer, standard clause terms). No per-organization
 * company-profile settings exist yet (see OrganizationSettingsPage) -
 * replace this with real org settings once that exists.
 */
export const COMPANY_INFO = {
  name: 'Tardus Inc.',
  address: '4425 W Airport Freeway, Suite # 530, Irving, TX 75062',
  phone: '972-666-8217',
  website: 'WWW.TARDUSINC.COM',
  eVerifyId: '1533740',
  ein: '84-3596475',
  hrName: 'Madhu Latha',
  hrTitle: 'HR Manager',
  noticePeriod: 'Four (4) weeks',
  confidentialityPeriod: 'one (1) year',
  developmentsDisclosurePeriod: 'six (6) months',
  nonCompetePeriod: 'one (1) year',
  nonCompeteRadius: 'one-hundred-mile',
  nonSolicitPeriod: 'one (1) year',
  disabilityConsecutiveDays: '120',
  disabilityWindowDays: '183',
  disabilityNoticeDays: 'thirty (30)',
  governingLawState: 'Texas',
};

/**
 * Standard legal clauses (4 through 18) that appear on every offer letter
 * regardless of designation or compensation - only Duties (1), Compensation
 * (2) and Benefits (3) vary per offer and are built in
 * OfferLetterPreparePage.
 *
 * `overrides.name`, `overrides.ein` and `overrides.eVerifyId` let the caller
 * substitute the organization's real name, EIN and E-Verify Company ID (from
 * Organization Settings) throughout these clauses instead of the COMPANY_INFO
 * placeholders - every other clause term (periods, governing law state, etc.)
 * still comes from COMPANY_INFO.
 */
export function getStandardOfferLetterClauses(overrides = {}) {
  const C = {
    ...COMPANY_INFO,
    ...(overrides.name ? { name: overrides.name } : null),
    ...(overrides.ein ? { ein: overrides.ein } : null),
    ...(overrides.eVerifyId ? { eVerifyId: overrides.eVerifyId } : null),
  };
  return [
    { number: 4, title: 'Confidentiality', body: `You shall treat as confidential all Trade Secrets and Confidential Information belonging to ${C.name} (or information belonging to third parties to which ${C.name} owes an obligation of secrecy) which is disclosed to you, which you may acquire or develop or which you may observe in the course of your employment by ${C.name} and which at the time of disclosure is not previously known by you and not known or used by others in the trade generally, and you shall not disclose, publish or otherwise use, either during or after termination of your employment by ${C.name}, any such Trade Secrets or Confidential Information without the prior written consent of ${C.name}. As used in this Agreement, "Confidential Information" means the whole or any portion or phase of any data or information relating to ${C.name}' services, products, processes or techniques relating to its business or that of any of ${C.name}' clients, whether or not copyrighted, patented or patentable. As used in this Agreement, "Trade Secret" means any useful process, machine or other device or composition of matter which is new and which is being used or studied by ${C.name} and is not described in a patent or described in any literature already published and distributed externally by ${C.name}; the source code or algorithms of any software developed or owned by ${C.name}; any formula, plan, tool, machine, process or method employed by ${C.name}, whether patentable or not, which is not generally known to others; business plans and marketing concepts of ${C.name}; marketing or sales information of ${C.name}; financial information or projections regarding ${C.name}; financial, pricing and/or credit information regarding clients or vendors of ${C.name}; a listing of names, addresses or telephone numbers of customers or clients of ${C.name}; internal corporate policies and procedures of ${C.name}; and any other information falling under the definition of a "Trade Secret" pursuant to the Uniform Trade Secrets Act (or, if applicable, the version thereof adopted by ${C.governingLawState}). Furthermore, you agree to maintain as confidential any and all confidential information of any client of ${C.name} that you may receive or with which you may become in contact.` },
    { number: 5, title: 'Confidentiality after Termination of Employment', body: `The confidentiality provisions of this Agreement with respect to ${C.name}' information shall remain in full force and effect for a ${C.confidentialityPeriod} period after the termination of your employment. The confidentiality provisions of the Agreement with respect to Client information shall remain in full force and effect indefinitely during, and after termination of, your employment.` },
    { number: 6, title: 'Assignment of Developments', body: `You will promptly disclose in writing to ${C.name} complete information concerning each and every discovery, improvement, device, design, apparatus, practice, process, method or product, whether or not patentable or copyrightable, made, developed, perfected, devised, conceived, produced, created or first reduced to practice by you, during the term of your employment with ${C.name}, or within ${C.developmentsDisclosurePeriod} thereafter, relating either directly or indirectly to the services, programs, products or other applications of ${C.name} developed or being developed by ${C.name} ("Developments"). You hereby acknowledge that any and all of said Developments are the property of ${C.name} and hereby assign and agree to assign to ${C.name} any and all of your right, title and interest in and to any and all of such Developments. You will execute any documents requested by ${C.name} (which shall be prepared at ${C.name}' expense), including but not limited to patent and copyright applications and registrations, and will perform any and all further acts deemed necessary or desirable by ${C.name} in order to confirm, exploit, or enforce the rights herein granted and assigned by you to ${C.name}. The provisions of this paragraph shall not apply to any Development meeting each of the following conditions: (i) such Development was developed entirely on your own time, (ii) such Development was made without the use of any ${C.name} equipment, supplies, facility or Confidential Information or Trade Secrets, (iii) such Development does not relate directly to any service, program, software, product or application of ${C.name} in place during the term of your employment or to any research or development efforts of ${C.name} during such term, and (iv) such Development does not result from any work or service performed by you on behalf of, or for, ${C.name}.` },
    { number: 7, title: 'Maintenance of Records', body: `You shall keep and maintain adequate and current written records of all Developments and Confidential Information made by you (solely or jointly with others) during the term of employment ("Records"). The Records may be in the form of notes, sketches, drawings, flow charts, electronic data or recordings, laboratory notebooks and any other format. The Records will be available to and remain the sole property of ${C.name} at all times. You shall not remove such Records from ${C.name}' place of business except as expressly permitted by ${C.name} policy which may, from time to time, be revised at the sole election of ${C.name} for the purpose of furthering ${C.name}' business.` },
    { number: 8, title: 'Non-Compete Agreement', body: `It is understood that you will be an employee of ${C.name} and will not perform services (either on a temporary or permanent basis) for other persons or firms or on your own account. Recognizing that the various items of information are special and unique assets of ${C.name} and its Clients, you agree that for a period of ${C.nonCompetePeriod} following the termination of this Agreement, you will not, directly or indirectly, engage in, be employed by or possess an equity interest in any business which is directly or indirectly competitive with ${C.name} which operates, is located, and/or conducts business activities within a ${C.nonCompeteRadius} radius of ${C.name}' facility. You agree that this non-compete provision will not adversely affect your livelihood.` },
    { number: 9, title: 'Non-Solicitation', body: `You agree that, while employed with ${C.name} and for ${C.nonSolicitPeriod} after such employment ends, you will not solicit directly or indirectly any of ${C.name}' clients, where ${C.name} placed or involved an employee/consultant during the last ${C.nonSolicitPeriod} of your employment with ${C.name}. Also, while employed with ${C.name} and for ${C.nonSolicitPeriod} after your employment with ${C.name} ceases you will not market, solicit, recruit or hire any of ${C.name}' employees and sub-contractors to work for anyone other than ${C.name}. Nor will you disclose any employee or contact/customer lists, trade secrets, proprietary software programs or any other proprietary information obtained as a result of being employed by ${C.name}, to anyone except in the ordinary course of conducting business for ${C.name}' benefit, subject to Article 5 above.` },
    { number: 10, title: 'Termination', body: `Employment under this Agreement shall be for an unspecified term on an at-will basis. This Agreement may be terminated by you with ${C.noticePeriod} written notice. ${C.name} has all the rights to terminate anytime because of performance issue.` },
    { number: 11, title: 'Termination for Disability', body: `${C.name} shall have the option to terminate this Agreement, if you become permanently disabled (defined as unable to work for a period of ${C.disabilityConsecutiveDays} consecutive days or at least ${C.disabilityWindowDays} days in any 12-month period) and are no longer able to perform the essential functions of the position with reasonable accommodation. ${C.name} shall exercise this option by giving you ${C.disabilityNoticeDays} calendar day's written notice.` },
    { number: 12, title: 'Employment Eligibility', body: `Employment with ${C.name} is contingent upon providing satisfactory documentation of your identity and legal authorization to work in the United States, as required by law. ${C.name} participates in the federal E-Verify program.`, highlight: `Our E-Verify Company ID is ${C.eVerifyId} and our Employment Identification Number (EIN) is ${C.ein}.` },
    { number: 13, title: 'Breach', body: `Any breach of any provisions of this Agreement by you will result in immediate and irreparable harm to ${C.name}, which will be extremely difficult to quantify and could not be reasonably or adequately compensated by law. For these reasons, ${C.name} shall have the right, without objection from you, to obtain such preliminary, temporary or permanent injunctions or restraining orders or decrees as may be necessary to protect ${C.name} against, or on account of any breach of this Agreement. If ${C.name} is successful in recovering damages or obtaining injunctive relief, you agree to be responsible for paying all of ${C.name}' expenses in seeking such relief, including all costs of bringing such suit and all reasonable attorney's fees.` },
    { number: 14, title: 'Return of Property', body: `Upon termination of this Agreement, you shall deliver all property (including keys, Records, Developments, notes, data, memoranda, software, models and equipment) that is in your possession or under your control which is ${C.name}' property or related to ${C.name}' business.` },
    { number: 15, title: 'Complete Agreement', body: 'This Agreement contains the entire agreement of all parties and there are no other promises or conditions in any other agreement whether oral or written. This Agreement supersedes any prior written or oral agreements between the parties.' },
    { number: 16, title: 'Governing Law', body: `This agreement shall be governed by, construed and enforced in accordance with the laws of the State of ${C.governingLawState}.` },
    { number: 17, title: 'Severability', body: 'The invalidity or unenforceability of any particular provision of the agreement will not affect the other provision mentioned.' },
    { number: 18, title: 'Binding Effect', body: `This Agreement shall be binding upon ${C.name} and you. Any successor to ${C.name} by merger or sale of all or substantially all of ${C.name}' assets is an intended third-party beneficiary of this Agreement with the same rights to enforce this Agreement as ${C.name}.` },
  ];
}

const UNPAID_DESIGNATIONS = [
  { value: 'SOFTWARE_DEVELOPER', label: 'Software Developer' },
  { value: 'TEST_ENGINEER', label: 'Test Engineer' },
  { value: 'SOFTWARE_TESTING_ENGINEER', label: 'Software Testing Engineer' },
  { value: OTHER_DESIGNATION, label: 'Other' },
];

const PAID_DESIGNATIONS = [
  { value: 'SDET', label: 'SDET' },
  { value: 'UI_UX', label: 'UI/UX' },
  { value: 'DATA_ENGINEER', label: 'Data Engineer' },
  { value: 'SOFTWARE_ENGINEER', label: 'Software Engineer' },
  { value: 'DEVOPS_ENGINEER', label: 'DevOps Engineer' },
  { value: OTHER_DESIGNATION, label: 'Other' },
];

export function getDesignationOptions(offerType) {
  return offerType === 'PAID' ? PAID_DESIGNATIONS : UNPAID_DESIGNATIONS;
}

export function getDesignationLabel(offerType, designation) {
  return getDesignationOptions(offerType).find((option) => option.value === designation)?.label || '';
}

const OFFER_LETTER_TEMPLATES = {
  'PAID:SDET': {
    name: 'SDET Paid Offer Letter Template',
    rolesAndResponsibilities: [
      'Design, develop and maintain robust automated test frameworks across UI, API and integration layers, using tools such as Selenium, Playwright, Cypress or REST-assured, to ensure broad and reliable coverage across the application stack.',
      'Translate functional and non-functional requirements into detailed test plans, test cases and traceability matrices, and review them with product and engineering stakeholders ahead of each release.',
      'Build and maintain reusable test libraries and page-object models to reduce duplication and speed up the creation of new automated tests as the product evolves.',
      'Integrate automated test suites into the CI/CD pipeline (e.g., Jenkins, GitHub Actions, GitLab CI) so that smoke and regression tests run automatically on every build and deployment.',
      'Identify, document and track defects with clear reproduction steps, severity and impact assessments, and collaborate closely with developers on root cause analysis and fix verification.',
      'Execute performance, load and stress testing using tools such as JMeter or k6, analyzing results to flag bottlenecks and scalability risks before releases reach production.',
      'Conduct exploratory and manual testing for new features and edge cases not yet covered by automation, and expand automated coverage based on the findings.',
      'Monitor test execution results and quality metrics such as pass/fail rates, flakiness and coverage, and report trends and risks to engineering leadership on a regular cadence.',
      'Participate in sprint planning, backlog grooming and design reviews to help build quality and testability into features from the earliest stages of development.',
    ],
  },
  'PAID:UI_UX': {
    name: 'UI/UX Paid Offer Letter Template',
    rolesAndResponsibilities: [
      'Lead end-to-end design for web and mobile features, from early concept sketches and user flows through wireframes, interactive prototypes and final high-fidelity mockups.',
      'Plan and conduct user research, including interviews, surveys and usability testing sessions, and translate the findings into clear, actionable design recommendations.',
      'Build, document and maintain a shared design system and component library so that designs stay consistent across the product and are easy for engineering to implement.',
      'Partner closely with product managers and engineers throughout the development lifecycle to ensure designs are technically feasible and are implemented faithfully.',
      'Create and iterate on prototypes using tools such as Figma or Adobe XD, incorporating stakeholder and user feedback across multiple rounds of design review.',
      'Apply and enforce accessibility standards (WCAG) and responsive-design best practices across all deliverables, so products work well for all users and screen sizes.',
      'Analyze product usage data and analytics to identify friction points in existing flows and surface opportunities for design improvement.',
      'Present design rationale and research findings clearly to cross-functional stakeholders, and incorporate their feedback constructively into future iterations.',
      'Stay current with design trends, tools and best practices, and bring relevant ideas back to strengthen the team\'s overall design practice.',
    ],
  },
  'PAID:DATA_ENGINEER': {
    name: 'Data Engineer Paid Offer Letter Template',
    rolesAndResponsibilities: [
      'Design, build and maintain scalable, reliable ETL/ELT pipelines that ingest, transform and load data from a variety of internal systems and third-party sources.',
      'Develop and optimize data models, schemas and warehouse structures that support analytics, reporting and machine learning use cases across the business.',
      'Build and maintain data integration workflows using tools such as Apache Airflow, dbt, Spark or Kafka, ensuring pipelines run reliably and on schedule.',
      'Implement data quality checks, validation rules and monitoring/alerting to catch issues such as missing data, schema drift or duplicate records before they reach downstream consumers.',
      'Optimize query performance and storage costs across data warehouses and lakes (e.g., Snowflake, BigQuery, Redshift, S3) as data volume and usage grow.',
      'Partner with data analysts, data scientists and business stakeholders to understand data needs and deliver clean, well-documented, trustworthy datasets.',
      'Establish and enforce data governance, security and access-control practices in line with company policy and applicable data-protection regulations.',
      'Troubleshoot production data pipeline issues, perform root cause analysis, and implement fixes and safeguards to prevent recurrence.',
      'Document data pipelines, schemas and data lineage so the broader team can understand, trust and build on the data infrastructure.',
    ],
  },
  'PAID:SOFTWARE_ENGINEER': {
    name: 'Software Engineer Paid Offer Letter Template',
    rolesAndResponsibilities: [
      'Design, develop, test and maintain scalable, high-quality software applications and services across the full development lifecycle, from requirements through deployment.',
      'Write clean, well-tested and maintainable code following the team\'s coding standards, and contribute to and follow established architecture and design patterns.',
      'Participate actively in code reviews, design discussions and sprint planning, providing constructive feedback to peers and incorporating feedback on your own work.',
      'Build and maintain automated unit, integration and end-to-end tests to ensure new features are reliable and do not introduce regressions.',
      'Debug and resolve production issues, perform root cause analysis, and implement fixes and monitoring to prevent similar issues from recurring.',
      'Collaborate closely with product managers, designers and QA to translate business requirements into well-scoped, practical technical solutions.',
      'Contribute to technical documentation, including design docs, runbooks and API documentation, to support the wider engineering team.',
      'Continuously look for opportunities to improve code quality, performance and developer tooling within the codebase.',
      'Stay current with relevant technologies and best practices, and share knowledge with the team through discussion, documentation or mentoring.',
    ],
  },
  'PAID:DEVOPS_ENGINEER': {
    name: 'DevOps Engineer Paid Offer Letter Template',
    rolesAndResponsibilities: [
      'Design, build and maintain CI/CD pipelines that automate build, test and deployment processes across development, staging and production environments.',
      'Provision, configure and manage cloud infrastructure (e.g., AWS, Azure or GCP) using infrastructure-as-code tools such as Terraform or CloudFormation.',
      'Implement and maintain containerization and orchestration platforms (e.g., Docker, Kubernetes) to support scalable, reliable application deployment.',
      'Set up and maintain monitoring, logging and alerting systems (e.g., Prometheus, Grafana, Datadog, the ELK stack) to ensure visibility into system health and performance.',
      'Respond to production incidents, lead or support root cause analysis, and drive post-incident reviews to prevent recurrence and improve system resilience.',
      'Implement and enforce security best practices across infrastructure and pipelines, including secrets management, vulnerability scanning and access controls.',
      'Collaborate with development teams to streamline release processes, reduce deployment friction and improve overall developer experience.',
      'Manage backup, disaster recovery and business continuity procedures for critical systems and data.',
      'Continuously evaluate and improve infrastructure reliability, scalability and cost efficiency as the platform grows.',
    ],
  },
  'UNPAID:SOFTWARE_DEVELOPER': {
    name: 'Software Developer Unpaid Offer Letter Template',
    rolesAndResponsibilities: [
      'Assist in designing, developing and testing software features under the guidance of senior engineers, working across the existing codebase and tooling.',
      'Learn and consistently apply the team\'s coding standards, style guides and code review process across all contributions.',
      'Support triage, reproduction and fixing of reported bugs, and help verify fixes before they are merged.',
      'Write and maintain basic unit tests for the code you contribute, under mentorship from the engineering team.',
      'Participate in daily stand-ups, sprint planning and backlog grooming sessions to build familiarity with agile development practices.',
      'Shadow senior developers during code reviews and design discussions to learn how architectural and technical decisions are made.',
      'Use version control (e.g., Git) and the team\'s CI/CD pipeline to submit, track and deploy changes following established workflows.',
      'Document code changes, setup steps and learnings in the team\'s internal wiki or README files to build technical writing skills.',
      'Take on small, well-scoped enhancement tasks and feature tickets as assigned, with regular check-ins and feedback from your mentor.',
    ],
  },
  'UNPAID:TEST_ENGINEER': {
    name: 'Test Engineer Unpaid Offer Letter Template',
    rolesAndResponsibilities: [
      'Execute manual and automated test cases under guidance, following existing test plans and scripts.',
      'Log, track and help triage defects using the team\'s issue tracker, including clear reproduction steps.',
      'Assist in preparing and updating test plans and test cases for upcoming releases and feature changes.',
      'Learn the team\'s quality assurance processes, tools and testing standards, including how test coverage is measured.',
      'Support exploratory testing sessions for new features to help identify edge cases not yet covered by existing tests.',
      'Help maintain and organize the team\'s test data and test environments under senior QA guidance.',
      'Participate in bug triage meetings and sprint ceremonies to understand how quality fits into the development lifecycle.',
      'Assist with basic regression testing ahead of releases to confirm existing functionality is not broken by new changes.',
      'Document testing outcomes, known issues and testing notes clearly for the wider engineering and QA team.',
    ],
  },
  'UNPAID:SOFTWARE_TESTING_ENGINEER': {
    name: 'Software Testing Engineer Unpaid Offer Letter Template',
    rolesAndResponsibilities: [
      'Execute manual and automated test cases under guidance, following existing test plans and scripts.',
      'Log, track and help triage defects using the team\'s issue tracker, including clear reproduction steps.',
      'Assist in preparing and updating test plans and test cases for upcoming releases and feature changes.',
      'Learn the team\'s quality assurance processes, tools and testing standards, including how test coverage is measured.',
      'Support exploratory testing sessions for new features to help identify edge cases not yet covered by existing tests.',
      'Help maintain and organize the team\'s test data and test environments under senior QA guidance.',
      'Participate in bug triage meetings and sprint ceremonies to understand how quality fits into the development lifecycle.',
      'Assist with basic regression testing ahead of releases to confirm existing functionality is not broken by new changes.',
      'Document testing outcomes, known issues and testing notes clearly for the wider engineering and QA team.',
    ],
  },
};

/**
 * Returns the saved template for a standard designation, or null when no
 * saved template applies (the "Other" designation, where HR supplies a
 * custom designation and roles/responsibilities instead).
 */
export function getOfferLetterTemplate(offerType, designation) {
  if (!offerType || !designation || designation === OTHER_DESIGNATION) return null;
  return OFFER_LETTER_TEMPLATES[`${offerType}:${designation}`] || null;
}
