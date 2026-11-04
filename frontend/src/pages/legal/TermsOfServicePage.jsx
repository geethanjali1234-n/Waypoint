import LegalPageLayout, { LegalTodo } from './LegalPageLayout';
import { LEGAL_DETAILS } from './legalConfig';

const SECTIONS = [
  { id: 'acceptance', label: 'Acceptance of these terms' },
  { id: 'service', label: 'The service' },
  { id: 'accounts', label: 'Accounts and roles' },
  { id: 'google', label: 'Organization administrators and the Google connection' },
  { id: 'use', label: 'Acceptable use' },
  { id: 'content', label: 'Your content and organization data' },
  { id: 'third-party', label: 'Third-party services and email delivery' },
  { id: 'availability', label: 'Availability, changes and suspension' },
  { id: 'liability', label: 'Disclaimers and limits of liability' },
  { id: 'law', label: 'Governing law' },
  { id: 'changes', label: 'Changes to these terms' },
];

/**
 * Public Terms of Service (draft for legal review). Descriptions of the service match how it works today. The parts that
 * are legal commitments (liability, warranties, governing law, termination, eligibility) are left as marked placeholders
 * for counsel; nothing here is invented on the operator's behalf.
 */
export default function TermsOfServicePage({ details = LEGAL_DETAILS }) {
  return (
    <LegalPageLayout title="Terms of Service" details={details} sections={SECTIONS} other={{ to: '/privacy', label: 'Privacy Policy' }}>
      <section id="acceptance">
        <h2>Acceptance of these terms</h2>
        <p>
          By creating an account or using WayPoint you agree to these terms. If you use WayPoint for an organization, you confirm that you
          are authorized to do so for that organization. <LegalTodo>counsel to add eligibility (such as minimum age) and the effect of not accepting</LegalTodo>
        </p>
        <p>Please read the Privacy Policy as well; it explains how information is handled.</p>
      </section>

      <section id="service">
        <h2>The service</h2>
        <p>
          WayPoint provides a job portal, where employers publish jobs and events and job seekers apply and register, and a workspace
          for organizations, covering timesheets, document requests, profiles, reports and related workflows, through a website and a
          mobile app. Features can change over time, and some are optional or enabled per organization.
        </p>
      </section>

      <section id="accounts">
        <h2>Accounts and roles</h2>
        <ul>
          <li>You are responsible for keeping your sign-in details confidential and for activity under your account.</li>
          <li>What a person can see and do depends on their role (for example job seeker, employer, employee, HR, Company Admin). An organization's Company Admin is responsible for who has access to that organization's workspace and for the accuracy of the information the organization enters.</li>
          <li>Provide accurate information and keep it up to date.</li>
        </ul>
      </section>

      <section id="google">
        <h2>Organization administrators and the Google connection</h2>
        <p>
          A Company Admin can authorize a Google account so that WayPoint sends the organization's emails through it. How this works and
          what WayPoint does and does not do with the connection is described in the Privacy Policy. By connecting an account, the administrator:
        </p>
        <ul>
          <li>confirms they are authorized to connect that account on the organization's behalf and to allow emails to be sent from it;</li>
          <li>accepts that emails sent this way appear in that account's Sent folder and show that account as the sender, and understands that using a dedicated mailbox is recommended;</li>
          <li>remains responsible for complying with Google's terms and with any rules set by the organization's Google Workspace administrator;</li>
          <li>is responsible for the content and purpose of the organization's emails, including that recipients are appropriate and that the emails comply with applicable law.</li>
        </ul>
        <p>
          The connection can be disconnected at any time in Organization Settings. WayPoint may stop using a connection, and send from
          its standard sender instead, if Google no longer accepts the authorization, if the account no longer matches the organization's
          email domain, or to protect the service.
        </p>
      </section>

      <section id="use">
        <h2>Acceptable use</h2>
        <p>You agree not to:</p>
        <ul>
          <li>use WayPoint unlawfully, or to send unlawful, deceptive or unsolicited bulk messages;</li>
          <li>attempt to access another person's or organization's information or account, or to bypass the separation between organizations;</li>
          <li>interfere with, overload or attempt to compromise the service, or upload malicious files;</li>
          <li>misrepresent your identity or your authority to act for an organization.</li>
        </ul>
        <p><LegalTodo>counsel to add any further restrictions and the consequences of breach</LegalTodo></p>
      </section>

      <section id="content">
        <h2>Your content and organization data</h2>
        <p>
          You and your organization keep your rights in the information you provide. You allow WayPoint to process it as needed to provide the
          service, as described in the Privacy Policy. <LegalTodo>counsel to confirm the licence wording and any ownership statement</LegalTodo>
        </p>
      </section>

      <section id="third-party">
        <h2>Third-party services and email delivery</h2>
        <ul>
          <li>WayPoint relies on other services, including Google when an account is connected. Their availability and terms are outside WayPoint's control, and their terms apply to your use of them.</li>
          <li>Emails are handed to an email provider for delivery. Delivery is not guaranteed, and WayPoint may not be told if a message is delayed, filtered or bounced. Important decisions should not depend on an email alone; the same information is available in the app.</li>
        </ul>
      </section>

      <section id="availability">
        <h2>Availability, changes and suspension</h2>
        <p>
          WayPoint may change the service, and may suspend or end access to protect the service or comply with law.{' '}
          <LegalTodo>counsel to describe availability commitments (if any), termination rights and what happens to data on termination</LegalTodo>
        </p>
      </section>

      <section id="liability">
        <h2>Disclaimers and limits of liability</h2>
        <p><LegalTodo>to be written by counsel; no warranty or liability terms have been drafted</LegalTodo></p>
      </section>

      <section id="law">
        <h2>Governing law</h2>
        <p><LegalTodo>counsel to specify governing law, venue and dispute resolution</LegalTodo></p>
      </section>

      <section id="changes">
        <h2>Changes to these terms</h2>
        <p>
          When these terms change, the effective date above is updated. <LegalTodo>describe how people are told about material changes and when they take effect</LegalTodo>
        </p>
      </section>
    </LegalPageLayout>
  );
}
