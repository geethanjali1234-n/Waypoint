import './JobSeekerContactUsPage.css';

function MailIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <rect x="3" y="5" width="18" height="14" rx="3" fill="none" stroke="currentColor" strokeWidth="1.7" />
      <path d="m4.5 7 7.5 6 7.5-6" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function PhoneIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M7.1 3.8 9.8 7 8.2 9.5c1.1 2.2 2.9 4 5.1 5.1l2.5-1.6 3.2 2.7-1 3.2c-.3 1-1.3 1.6-2.3 1.4C9.2 19.1 4.9 14.8 3.7 8.3c-.2-1 .4-2 1.4-2.3l2-2.2Z" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <circle cx="12" cy="12" r="8.5" fill="none" stroke="currentColor" strokeWidth="1.7" />
      <path d="M12 7.5v5l3.2 2" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function PinIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d="M19 10.5c0 5.2-7 10-7 10s-7-4.8-7-10a7 7 0 1 1 14 0Z" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
      <circle cx="12" cy="10.5" r="2.25" fill="none" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

export default function JobSeekerContactUsPage() {
  return (
    <main className="wp-contact-us">
      <section className="wp-contact-us__intro" aria-labelledby="contact-us-heading">
        <p className="wp-contact-us__eyebrow">Waypoint support</p>
        <h1 id="contact-us-heading">Contact support</h1>
        <p>
          Need a hand with your Waypoint account, profile, or next step? Our support team is here to help.
        </p>
      </section>

      <section className="wp-contact-us__details" aria-label="Mock support contact details">
        <a className="wp-contact-us__card" href="mailto:support@waypoint.example">
          <span className="wp-contact-us__icon"><MailIcon /></span>
          <span className="wp-contact-us__card-copy">
            <small>Email support</small>
            <strong>support@waypoint.example</strong>
            <span>For account access and general questions</span>
          </span>
        </a>
        <a className="wp-contact-us__card" href="tel:+13135550148">
          <span className="wp-contact-us__icon"><PhoneIcon /></span>
          <span className="wp-contact-us__card-copy">
            <small>Call support</small>
            <strong>(313) 555-0148</strong>
            <span>For help that is easier to talk through</span>
          </span>
        </a>
        <article className="wp-contact-us__card">
          <span className="wp-contact-us__icon"><ClockIcon /></span>
          <span className="wp-contact-us__card-copy">
            <small>Support hours</small>
            <strong>Monday to Friday</strong>
            <span>9:00 AM to 5:00 PM ET</span>
          </span>
        </article>
        <article className="wp-contact-us__card">
          <span className="wp-contact-us__icon"><PinIcon /></span>
          <span className="wp-contact-us__card-copy">
            <small>Waypoint office</small>
            <strong>100 Woodward Ave, Suite 400</strong>
            <span>Detroit, MI 48226</span>
          </span>
        </article>
      </section>

      <section className="wp-contact-us__note" aria-labelledby="contact-us-note-heading">
        <h2 id="contact-us-note-heading">When you contact us</h2>
        <p>
          Include the email or Login ID connected to your account and a short description of what you need. We will use that information to get you to the right team faster.
        </p>
      </section>
    </main>
  );
}
