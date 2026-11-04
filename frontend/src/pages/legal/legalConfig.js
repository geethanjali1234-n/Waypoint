/**
 * Details only the WayPoint operator can supply for the public Privacy Policy and Terms of Service. They are
 * deliberately EMPTY: nothing here is invented. While any of them is empty, both pages show a visible
 * "draft for legal review" notice and a marked placeholder where the value belongs, so an unreviewed page cannot be
 * mistaken for an approved one.
 *
 * To publish: have legal review the wording in PrivacyPolicyPage.jsx and TermsOfServicePage.jsx, then fill in these
 * three values. The notice and placeholders disappear on their own.
 */
export const LEGAL_DETAILS = {
  /** The legal name of the company that operates WayPoint. */
  operatorName: '',
  /** A monitored address for privacy and legal questions. */
  contactEmail: '',
  /** The date this version takes effect, written as people should read it (for example "1 January 2027"). */
  effectiveDate: '',
};

export function isLegalReviewPending(details = LEGAL_DETAILS) {
  return !details?.operatorName?.trim() || !details?.contactEmail?.trim() || !details?.effectiveDate?.trim();
}
