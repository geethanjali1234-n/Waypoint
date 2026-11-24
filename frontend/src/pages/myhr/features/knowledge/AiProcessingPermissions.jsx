import { useCallback, useEffect, useState } from 'react';
import { Button, Card, ConfirmationDialog, StatusBadge, TextInput, ValidationMessage } from '../../../../components';
import myHrKnowledgeApi from '../../../../api/myHrKnowledgeApi';

export const AUTOMATIC_COVERAGE_NOTICE = 'This allows relevant excerpts from eligible published company-wide policies, '
  + 'including future versions, to be sent to Anthropic Claude. HR-only and private policies remain excluded.';
const AUTOMATIC = 'ALL_PUBLISHED_COMPANY_WIDE';
const CLAUDE = 'ANTHROPIC_CLAUDE';
const MIN_TEXT = 3;

/** A friendly message for a failed request. The backend decides; this only explains its answer. */
export function permissionErrorMessage(error, fallback) {
  const status = error?.response?.status;
  if (status === 401) return 'Your session has expired. Please sign in again.';
  if (status === 403) return 'Only a Company Admin can change AI processing permissions.';
  if (status === 404) return 'That policy was not found in your organization.';
  return error?.response?.data?.message || fallback;
}

const validText = (value) => value.trim().length >= MIN_TEXT;

/**
 * Company Admin controls for sending policy excerpts to Anthropic Claude. Rendered only for Company Admins as a
 * convenience; every approval, revocation and exclusion is authorized and validated by the backend.
 */
export default function AiProcessingPermissions({ documents }) {
  const [status, setStatus] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [feedback, setFeedback] = useState(null);
  const [busy, setBusy] = useState(false);
  const [reference, setReference] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const [revocationReason, setRevocationReason] = useState('');
  const [exclusionReason, setExclusionReason] = useState('');
  const [confirmation, setConfirmation] = useState(null);

  const load = useCallback(async () => {
    try {
      const response = await myHrKnowledgeApi.getExternalProcessing();
      setStatus(response?.data?.data || null);
      setLoadError('');
    } catch (error) {
      setLoadError(permissionErrorMessage(error, 'AI processing permissions could not be loaded.'));
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const approvals = Array.isArray(status?.approvals) ? status.approvals : [];
  const claude = approvals.filter((record) => record?.provider === CLAUDE);
  const organization = claude.find((record) => record.scope === 'ORGANIZATION' && record.status === 'ACTIVE'
    && (record.accessLevels || []).includes('COMPANY_WIDE')) || null;
  const automatic = organization?.coverage === AUTOMATIC;
  const excluded = new Set(claude.filter((record) => record.scope === 'DOCUMENT_EXCLUSION' && record.status === 'ACTIVE')
    .map((record) => record.knowledgeDocumentId));
  const policies = (documents || []).filter((document) => document.status !== 'REVOKED');

  /** Sends one request and always reloads, so the page shows the backend's actual state afterwards. */
  const run = async (call, success) => {
    if (busy) return false;
    setBusy(true);
    setFeedback(null);
    try {
      await call();
      setFeedback({ variant: 'hint', message: success });
      return true;
    } catch (error) {
      setFeedback({ variant: 'error', message: permissionErrorMessage(error, 'The request could not be completed.') });
      return false;
    } finally {
      await load();
      setBusy(false);
    }
  };

  const enableAutomatic = async () => {
    // With an existing approval, one atomic request replaces it; on failure the previous approval stays in force.
    const call = organization
      ? () => myHrKnowledgeApi.replaceClaudeOrganizationCoverage({ expectedApprovalId: organization.id,
        coverage: AUTOMATIC, approvalReference: reference.trim(), reason: revocationReason.trim() })
      : () => myHrKnowledgeApi.approveClaudeOrganization({ approvalReference: reference.trim(), coverage: AUTOMATIC });
    if (await run(call, 'Automatic coverage is on for published company-wide policies.')) {
      setReference('');
      setAcknowledged(false);
      setRevocationReason('');
    }
  };

  const revokeOrganization = async () => {
    if (await run(() => myHrKnowledgeApi.revokeClaudeOrganization(revocationReason.trim()),
      'The organization approval was revoked. Claude no longer receives policy excerpts.')) setRevocationReason('');
  };

  const toggleExclusion = async (document) => {
    const lifting = excluded.has(document.id);
    const call = lifting
      ? () => myHrKnowledgeApi.liftClaudeExclusion(document.id, exclusionReason.trim())
      : () => myHrKnowledgeApi.excludeFromClaude(document.id, exclusionReason.trim());
    if (await run(call, lifting ? `"${document.title}" is no longer excluded from Claude.`
      : `"${document.title}" is excluded from Claude.`)) setExclusionReason('');
  };

  const needsReason = Boolean(organization);
  const canEnable = !automatic && validText(reference) && acknowledged && (!needsReason || validText(revocationReason));
  const mode = automatic ? 'Automatic coverage of published company-wide policies'
    : organization ? 'Per-version approval' : 'Not approved';

  return <Card title="AI Processing Permissions">
    <section className="knowledge-base__ai-permissions" aria-label="AI processing permissions">
      {loadError ? <ValidationMessage variant="error" message={loadError} /> : !status
        ? <p className="knowledge-base__hint" role="status">Loading AI processing permissions…</p> : <>
          <div className="knowledge-base__ai-mode">
            <span>Claude approval mode:</span>
            <StatusBadge status={organization ? 'active' : 'inactive'} label={mode} />
          </div>
          {!status.claudeSwitchEnabled && <ValidationMessage
            message="Claude policy sharing is turned off for this environment, so no policy excerpts are sent to Claude." />}
          <p className="knowledge-base__hint">
            <strong>Per-version approval:</strong> a Company Admin approves each published policy version before Claude
            may use it. <strong>Automatic coverage:</strong> every eligible published company-wide policy, including
            future versions, may be used once it passes review and publication. HR-only and private policies are never
            sent to Claude, and excluded policies stay excluded in both modes.</p>

          {!automatic && <div className="knowledge-base__form">
            <TextInput name="claudeApprovalReference" label="Approval reference (agreement or ticket)" value={reference}
              onChange={(event) => setReference(event.target.value)} />
            <label className="knowledge-base__ai-ack">
              <input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
              <span>{AUTOMATIC_COVERAGE_NOTICE}</span>
            </label>
          </div>}
          {organization && <div className="knowledge-base__form">
            <TextInput name="claudeRevocationReason"
              label={automatic ? 'Reason for revoking the organization approval'
                : 'Reason for replacing the current per-version approval'}
              value={revocationReason} onChange={(event) => setRevocationReason(event.target.value)} />
          </div>}
          <div className="knowledge-base__actions">
            {!automatic && <Button disabled={busy || !canEnable} onClick={() => setConfirmation({
              title: 'Enable automatic coverage?', message: AUTOMATIC_COVERAGE_NOTICE
                + (organization ? ' The current per-version approval will be replaced in a single step; if anything fails it stays in force.' : ''),
              confirmLabel: 'Enable automatic coverage', action: enableAutomatic,
            })}>Enable automatic coverage</Button>}
            {organization && <Button variant="secondary" disabled={busy || !validText(revocationReason)} onClick={() => setConfirmation({
              title: 'Revoke organization approval?',
              message: 'Claude will immediately stop receiving policy excerpts from your organization.',
              confirmLabel: 'Revoke approval', action: revokeOrganization,
            })}>Revoke organization approval</Button>}
          </div>

          <h3 className="knowledge-base__ai-heading">Policy exclusions</h3>
          <p className="knowledge-base__hint">An excluded policy is never sent to Claude, whatever the approval mode.</p>
          <TextInput name="claudeExclusionReason" label="Reason for excluding or including a policy" value={exclusionReason}
            onChange={(event) => setExclusionReason(event.target.value)} />
          {policies.length === 0 ? <p className="knowledge-base__hint">No policies yet.</p>
            : <ul className="knowledge-base__ai-exclusions">{policies.map((document) => {
              const isExcluded = excluded.has(document.id);
              return <li key={document.id}>
                <span>{document.title}</span>
                {isExcluded && <StatusBadge status="inactive" label="Excluded from Claude" />}
                <Button variant="secondary" disabled={busy || !validText(exclusionReason)}
                  aria-label={`${isExcluded ? 'Remove exclusion for' : 'Exclude'} ${document.title}`}
                  onClick={() => toggleExclusion(document)}>{isExcluded ? 'Remove exclusion' : 'Exclude from Claude'}</Button>
              </li>;
            })}</ul>}
          {feedback && <ValidationMessage variant={feedback.variant} message={feedback.message} />}
        </>}
    </section>
    <ConfirmationDialog isOpen={Boolean(confirmation)} title={confirmation?.title} message={confirmation?.message}
      confirmLabel={confirmation?.confirmLabel} onCancel={() => setConfirmation(null)}
      onConfirm={async () => { const pending = confirmation; setConfirmation(null); if (pending) await pending.action(); }} />
  </Card>;
}
