import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import PrivacyPolicyPage from '../../../pages/legal/PrivacyPolicyPage';
import TermsOfServicePage from '../../../pages/legal/TermsOfServicePage';
import { LEGAL_DETAILS, isLegalReviewPending } from '../../../pages/legal/legalConfig';

const COMPLETE = { operatorName: 'Example Operator Inc.', contactEmail: 'privacy@example.test', effectiveDate: '1 January 2027' };

function renderPage(Page, details) {
  return render(<MemoryRouter><Page {...(details ? { details } : {})} /></MemoryRouter>);
}

describe('Privacy Policy page', () => {
  it('is a normal public page: heading, contents and links, with nothing that needs a sign-in', () => {
    renderPage(PrivacyPolicyPage);

    expect(screen.getByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    const contents = screen.getByRole('navigation', { name: 'Contents' });
    expect(within(contents).getByRole('link', { name: 'Connecting a Google account to send email' })).toHaveAttribute('href', '#google');
    expect(screen.getAllByRole('link', { name: 'Terms of Service' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'WayPoint home' })).toHaveAttribute('href', '/');
    expect(document.title).toBe('Privacy Policy - WayPoint');
  });

  it('explains that Google authorization is used to send organization emails', () => {
    renderPage(PrivacyPolicyPage);
    const google = document.getElementById('google');

    expect(within(google).getByRole('heading', { name: 'Connecting a Google account to send email' })).toBeInTheDocument();
    expect(google).toHaveTextContent(/send the organization's emails/i);
    expect(google).toHaveTextContent(/document requests and reminders, timesheet approvals and rejections, offer letters/);
    expect(google).toHaveTextContent('gmail.send');
    expect(google).toHaveTextContent('This is the only Gmail permission requested');
  });

  it('states clearly that WayPoint does not read users\' Gmail', () => {
    renderPage(PrivacyPolicyPage);
    const google = document.getElementById('google');

    expect(google).toHaveTextContent(/does not use this integration to read, search, list, download, modify or delete messages/);
    expect(google).toHaveTextContent('does not allow reading mail');
    expect(google).toHaveTextContent(/does \s*not\s* read the inbox/i);
  });

  it('discloses the Sent folder, how tokens are stored, who can connect, and how to end the connection', () => {
    renderPage(PrivacyPolicyPage);
    const google = document.getElementById('google');

    expect(google).toHaveTextContent("appear in that account's Gmail Sent folder");
    expect(google).toHaveTextContent('stored in encrypted form');
    expect(google).toHaveTextContent('not stored');
    expect(google).toHaveTextContent('Only a Company Admin');
    expect(google).toHaveTextContent('disconnect the account at any time');
    expect(google).toHaveTextContent(/revoke/);
    expect(google).toHaveTextContent('standard WayPoint sender');
  });

  it('links to Google\'s user data policy and the Limited Use requirement, flagged for counsel to confirm', () => {
    renderPage(PrivacyPolicyPage);
    const google = document.getElementById('google');

    const link = within(google).getByRole('link', { name: 'Google API Services User Data Policy' });
    expect(link).toHaveAttribute('href', 'https://developers.google.com/terms/api-services-user-data-policy');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(google).toHaveTextContent('Limited Use requirements');
    expect(within(google).getByText(/counsel to confirm each of these statements/)).toBeInTheDocument();
  });

  it('makes no unqualified security promise', () => {
    renderPage(PrivacyPolicyPage);

    const text = document.body.textContent;
    expect(text).not.toMatch(/\b(100% secure|completely secure|guarantee[sd]? (the )?security|military[- ]grade|unbreakable|bank[- ]level)\b/i);
    expect(text).toContain('No online service can promise complete security');
  });
});

describe('Terms of Service page', () => {
  it('is a normal public page', () => {
    renderPage(TermsOfServicePage);

    expect(screen.getByRole('heading', { level: 1, name: 'Terms of Service' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Privacy Policy' }).length).toBeGreaterThan(0);
    expect(document.title).toBe('Terms of Service - WayPoint');
  });

  it('describes the administrator\'s responsibilities when connecting a Google account', () => {
    renderPage(TermsOfServicePage);
    const google = document.getElementById('google');

    expect(google).toHaveTextContent('authorized to connect that account');
    expect(google).toHaveTextContent('Sent folder');
    expect(google).toHaveTextContent("Google's terms");
    expect(google).toHaveTextContent('disconnected at any time');
  });

  it('does not invent warranty, liability or governing-law terms; those are left for counsel', () => {
    renderPage(TermsOfServicePage);

    expect(within(document.getElementById('liability')).getByText(/to be written by counsel/)).toBeInTheDocument();
    expect(within(document.getElementById('law')).getByText(/counsel to specify governing law/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/limited to the (amount|fees)|shall not exceed|laws of the state of/i);
  });

  it('is honest that email delivery is not guaranteed', () => {
    renderPage(TermsOfServicePage);

    expect(document.getElementById('third-party')).toHaveTextContent('Delivery is not guaranteed');
  });
});

describe('Operator details and the draft notice', () => {
  it('ships with no invented company details, so both pages show the draft notice and marked placeholders', () => {
    expect(LEGAL_DETAILS).toEqual({ operatorName: '', contactEmail: '', effectiveDate: '' });
    expect(isLegalReviewPending()).toBe(true);

    for (const Page of [PrivacyPolicyPage, TermsOfServicePage]) {
      const { unmount } = renderPage(Page);
      expect(screen.getByRole('note')).toHaveTextContent('Draft for legal review');
      expect(screen.getByText('[Legal review: effective date]')).toBeInTheDocument();
      expect(screen.getByText('[Legal review: legal name of the operating company]')).toBeInTheDocument();
      expect(screen.getByText('[Legal review: contact email address]')).toBeInTheDocument();
      unmount();
    }
  });

  it('shows the supplied details and drops the draft notice once all three are provided', () => {
    expect(isLegalReviewPending(COMPLETE)).toBe(false);
    renderPage(PrivacyPolicyPage, COMPLETE);

    expect(screen.queryByText('Draft for legal review.')).not.toBeInTheDocument();
    expect(screen.getByText(/Example Operator Inc\./)).toBeInTheDocument();
    expect(screen.getByText(/1 January 2027/)).toBeInTheDocument();
    expect(screen.getByText(/privacy@example.test/)).toBeInTheDocument();
  });

  it('keeps the draft notice if even one detail is missing or blank', () => {
    for (const missing of ['operatorName', 'contactEmail', 'effectiveDate']) {
      expect(isLegalReviewPending({ ...COMPLETE, [missing]: '  ' })).toBe(true);
    }
    renderPage(TermsOfServicePage, { ...COMPLETE, contactEmail: '' });
    expect(screen.getByRole('note')).toHaveTextContent('Draft for legal review');
  });
});
