import { beforeEach, describe, expect, it } from 'vitest';
import progress from '../../../pages/myhr/components/dashboardDocumentProgress';

beforeEach(() => localStorage.clear());
describe('Dashboard document next steps', () => {
  it('requires an issued offer before asking its requester to sign', () => {
    expect(progress({ documentType: 'OFFER_LETTER', status: 'PENDING' }).actionLabel).toBe('View request details');
    const issued = progress({ documentType: 'OFFER_LETTER', status: 'FULFILLED', fulfilledDocumentId: 'issued-file' });
    expect(issued.actionLabel).toBe('Review and sign');
    expect(issued.steps.find((step) => step.label === 'Filed').state).toBe('upcoming');
  });
  it('shows a completed offer only when the signed response exists', () => {
    const completed = progress({ documentType: 'OFFER_LETTER', status: 'FULFILLED', signedResponseDocumentId: 'signed-file' });
    expect(completed.actionLabel).toBe('View completed document');
    expect(completed.steps.every((step) => step.state === 'done')).toBe(true);
  });
  it('shows HR review for an uploaded I-983 without requesting another employee signature', () => {
    const uploaded = progress({ documentType: 'I983', status: 'SUBMITTED', originalDocumentId: 'original-file' });
    expect(uploaded.actionLabel).toBe('View signing progress');
    expect(uploaded.steps.find((step) => step.state === 'current').label).toBe('Review');
    expect(uploaded.needsAction).toBe(false);
  });
  it('prioritizes a returned I-983 for correction rather than declaring it filed', () => {
    const returned = progress({ documentType: 'I983', status: 'RETURNED_FOR_CORRECTION', originalDocumentId: 'original-file' });
    expect(returned.actionLabel).toBe('Correct and resubmit');
    expect(returned.needsAction).toBe(true);
    expect(returned.steps.find((step) => step.label === 'Filed').state).toBe('upcoming');
  });
  it('does not claim that an approved I-983 is filed when the issued file is missing', () => {
    const approved = progress({ documentType: 'I983', status: 'APPROVED', originalDocumentId: 'original-file' });
    expect(approved.steps.some((step) => step.label === 'Filed')).toBe(false);
    expect(approved.actionLabel).toBe('View request details');
  });
  it('retains a pending review after the employee signs but before an issued response exists', () => {
    const returned = progress({ documentType: 'OFFER_LETTER', status: 'SUBMITTED', employeeSignatureDocumentId: 'acceptance' });
    expect(returned.actionLabel).toBe('View signing progress');
    expect(returned.steps.find((step) => step.label === 'Filed').state).toBe('upcoming');
  });
  it.each(['REJECTED', 'CANCELLED'])('ends a %s request without presenting future filing as a next step', (status) => {
    const terminal = progress({ status });
    expect(terminal.steps.some((step) => step.label === 'Filed')).toBe(false);
    expect(terminal.needsAction).toBe(false);
  });
  it('omits invented progress for an unknown status and supports actual employee upload requests', () => {
    expect(progress({ status: 'UNKNOWN' }).steps).toEqual([]);
    expect(progress({ status: 'PENDING', requestDirection: 'TO_EMPLOYEE' }).actionLabel).toBe('Submit documents');
  });
});
