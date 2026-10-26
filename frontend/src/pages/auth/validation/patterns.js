/**
 * Shared validation patterns for the registration forms. Kept centralized so
 * Employer and Job Seeker registration always validate email/phone identically
 * on the frontend, and mirrored exactly (same regex) on the backend DTOs.
 */

/**
 * Requires exactly one '@', a domain containing at least one '.', and a
 * TLD of at least 2 letters. Deliberately stricter than a bare RFC-permissive
 * email regex - rejects local-part symbols like '#'.
 */
export const EMAIL_PATTERN = /^[A-Za-z0-9._-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/;

/** Matches a stored value of '+1' followed by exactly 10 digits. */
export const US_PHONE_PATTERN = /^\+1\d{10}$/;
