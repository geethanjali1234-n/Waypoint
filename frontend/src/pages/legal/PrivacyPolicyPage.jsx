import LegalPageLayout, { LegalTodo } from './LegalPageLayout';
import { LEGAL_DETAILS } from './legalConfig';

const SECTIONS = [
  { id: 'scope', label: 'What this policy covers' },
  { id: 'information', label: 'Information WayPoint handles' },
  { id: 'google', label: 'Connecting a Google account to send email' },
  { id: 'use', label: 'How information is used' },
  { id: 'sharing', label: 'Who information is shared with' },
  { id: 'security', label: 'How information is protected' },
  { id: 'retention', label: 'Retention and deletion' },
  { id: 'choices', label: 'Your choices and requests' },
  { id: 'changes', label: 'Changes to this policy' },
];

/**
 * Public Privacy Policy (draft for legal review). Everything stated about what the product does reflects how it works
 * today (for example that the Google permission requested is send-only, and that refresh tokens are stored encrypted).
 * Commitments only the operator can make, and anything legal must decide, are shown as marked placeholders.
 */
export default function PrivacyPolicyPage({ details = LEGAL_DETAILS }) {
  return (
    <LegalPageLayout title="Privacy Policy" details={details} sections={SECTIONS} other={{ to: '/terms', label: 'Terms of Service' }}>
      <section id="scope">
        <h2>What this policy covers</h2>
        <p>
          WayPoint is a web and mobile application that includes a job portal, where employers publish jobs and events and job
          seekers apply, and a workspace for organizations, where their administrators, HR staff and employees handle timesheets,
          document requests, profiles and related records. This policy describes the information WayPoint handles to provide these
          features, including when an organization connects a Google account so that WayPoint can send the organization's emails.
        </p>
        <p>
          Organizations decide what employment-related information they enter into their workspace. This policy describes how
          WayPoint itself handles that information; an organization may have its own policies for its people.
        </p>
      </section>

      <section id="information">
        <h2>Information WayPoint handles</h2>
        <ul>
          <li><strong>Account information:</strong> name, email address, sign-in ID and role. Passwords are stored only as a one-way hash.</li>
          <li><strong>Job portal information:</strong> job seeker profiles, resumes and profile photos; employer and company profiles; jobs, events, applications and registrations.</li>
          <li><strong>Workspace information:</strong> organization details, employee profiles, reporting relationships, timesheets, document requests and the documents attached to them, profile change requests, payroll readiness records and reports.</li>
          <li><strong>Notifications and activity records:</strong> in-app notifications and an audit history of significant actions in an organization's workspace.</li>
          <li><strong>Information kept in your browser:</strong> the web app stores a sign-in token and some preferences in your browser's local storage on your device.</li>
          <li><strong>Information from other services:</strong> where a feature uses one (for example address suggestions, location lookup for jobs and events, or event weather), the text needed for that lookup is sent to that service. <LegalTodo>list the providers and confirm</LegalTodo></li>
        </ul>
        <p>
          <LegalTodo>confirm and describe any server logs, analytics or other technical data collected, and their retention</LegalTodo>
        </p>
      </section>

      <section id="google">
        <h2>Connecting a Google account to send email</h2>
        <p className="wp-legal__callout">
          In short: an organization's administrator can authorize a Google account so WayPoint can <strong>send</strong> the
          organization's emails from it. WayPoint does <strong>not</strong> read the inbox or any other mail in that account.
        </p>

        <h3>What the connection is for</h3>
        <p>
          A Company Admin can choose to connect their organization's Google account (a Gmail or Google Workspace account) from
          Organization Settings. WayPoint then sends the organization's emails through that account instead of a shared WayPoint
          sender. These are the notifications the workspace produces, such as document requests and reminders, timesheet
          approvals and rejections, offer letters and account notifications, and a test message the administrator can request.
        </p>

        <h3>The permission WayPoint asks Google for</h3>
        <ul>
          <li><strong>Send email on your behalf</strong> (Gmail permission <code>gmail.send</code>). This is the only Gmail permission requested.</li>
          <li>Basic sign-in identity (<code>openid</code> and <code>email</code>), used to learn which Google account was connected.</li>
        </ul>

        <h3>What WayPoint does not do</h3>
        <ul>
          <li>WayPoint does not use this integration to read, search, list, download, modify or delete messages in anyone's Gmail inbox or any other folder. The permission it requests does not allow reading mail, and WayPoint does not attempt to.</li>
          <li>WayPoint does not request access to contacts, calendar, files or other Google data through this connection.</li>
        </ul>

        <h3>What WayPoint receives and stores</h3>
        <ul>
          <li>The connected account's email address, Google's identifier for the account, the account's domain (for Google Workspace accounts) and which permissions were granted.</li>
          <li>A refresh token, which lets WayPoint ask Google for short-lived access when it needs to send. It is stored in encrypted form and is never shown in the app. The short-lived access tokens are kept only in the server's memory for a short time and are not stored.</li>
          <li>A record, in the organization's audit history, of when a Google account was connected, tested or disconnected, by whom, and which account.</li>
        </ul>

        <h3>How the connection is used and limited</h3>
        <ul>
          <li>The connection is used only to send emails on behalf of the organization that connected it. Each organization's emails use only that organization's own connection.</li>
          <li>Only a Company Admin of the organization can connect, test or disconnect the account. WayPoint accepts only a Google account on the same domain as the organization's Organization Email.</li>
          <li><strong>Messages appear in the Sent folder.</strong> Emails sent this way are sent from the connected account, so they appear in that account's Gmail Sent folder, and recipients see that account as the sender. We therefore suggest using a dedicated mailbox.</li>
        </ul>

        <h3>Ending the connection</h3>
        <p>
          An administrator can disconnect the account at any time in Organization Settings. WayPoint then deletes the stored token
          and asks Google to revoke it. The account's owner can also remove WayPoint's access at any time from the security
          settings of their Google Account. If Google stops accepting the stored authorization (for example after access is
          removed, a password change, or a long period without use), WayPoint stops using it, tells the organization's administrators
          to reconnect, and sends the affected emails from the standard WayPoint sender in the meantime.
        </p>

        <h3>Google user data</h3>
        <p>
          WayPoint's use and transfer to any other app of information received from Google APIs will adhere to the{' '}
          <a href="https://developers.google.com/terms/api-services-user-data-policy" rel="noopener noreferrer" target="_blank">
            Google API Services User Data Policy
          </a>, including the Limited Use requirements. Information obtained through the Google connection is used only to send the
          organization's emails as described above, is not used to show advertisements, and is not sold.{' '}
          <LegalTodo>counsel to confirm each of these statements before publication</LegalTodo>
        </p>
      </section>

      <section id="use">
        <h2>How information is used</h2>
        <ul>
          <li>To provide the job portal and workspace features described above, and to show each person only what their role allows.</li>
          <li>To send emails and notifications about a person's own activity, such as verification codes, applications, events, requests and approvals.</li>
          <li>To keep the service secure, investigate problems and keep an audit history for organizations.</li>
          <li>To respond to questions and support requests. <LegalTodo>add any other purposes, such as analytics or product improvement, or confirm there are none</LegalTodo></li>
        </ul>
      </section>

      <section id="sharing">
        <h2>Who information is shared with</h2>
        <ul>
          <li><strong>Within an organization:</strong> according to roles. For example, a manager sees their team's timesheets and requests, and an administrator sees organization-wide records.</li>
          <li><strong>Employers and job seekers:</strong> an employer sees the profile and resume of a person who applies to its job.</li>
          <li><strong>Service providers</strong> that help WayPoint run, such as database hosting, file storage, email delivery and, where a feature uses them, lookup or document-reading services. <LegalTodo>name the providers and the purpose of each</LegalTodo></li>
          <li><strong>Google,</strong> when an organization connects a Google account: the emails WayPoint sends through that account pass through Google so they can be delivered.</li>
          <li><strong>Legal and safety:</strong> <LegalTodo>describe when information may be disclosed to comply with law or protect rights</LegalTodo></li>
        </ul>
      </section>

      <section id="security">
        <h2>How information is protected</h2>
        <p>Measures built into the product include:</p>
        <ul>
          <li>passwords stored only as one-way hashes;</li>
          <li>Google refresh tokens stored encrypted, and access tokens never stored;</li>
          <li>role-based access, with each organization's data and Google connection kept separate from other organizations;</li>
          <li>rate limits on sign-in and on connecting a Google account.</li>
        </ul>
        <p>No online service can promise complete security. <LegalTodo>counsel to review this section for accuracy and completeness</LegalTodo></p>
      </section>

      <section id="retention">
        <h2>Retention and deletion</h2>
        <p>
          The stored Google authorization is deleted when an administrator disconnects the account.{' '}
          <LegalTodo>state how long other information is kept, what happens when an organization or account is closed, and how deletion is requested</LegalTodo>
        </p>
      </section>

      <section id="choices">
        <h2>Your choices and requests</h2>
        <p>
          You can update much of your information in your profile. To ask about, correct or delete information, or to ask a question about
          this policy, use the contact address at the end of this page.{' '}
          <LegalTodo>describe the rights and request process that apply, and how identity is confirmed</LegalTodo>
        </p>
      </section>

      <section id="changes">
        <h2>Changes to this policy</h2>
        <p>
          When this policy changes, the effective date above is updated. <LegalTodo>describe how people are told about material changes</LegalTodo>
        </p>
      </section>
    </LegalPageLayout>
  );
}
