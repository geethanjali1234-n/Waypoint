import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Card, ConfirmationDialog, Dropdown, EmptyState, StatusBadge, TextInput, ValidationMessage } from '../../../../components';
import myHrKnowledgeApi from '../../../../api/myHrKnowledgeApi';
import { formatDateOnly } from '../../../../utils/dateFormat';
import MyHrFeaturePage from '../MyHrFeaturePage';
import { policyFileAccept, validatePolicyFile } from './policyFileValidation';
import ContentReviewPanel from './ContentReviewPanel';
import AiProcessingPermissions from './AiProcessingPermissions';
import { getMyHrSession } from '../../../../auth/myHrSession';
import './knowledgeBase.css';

const ACCESS_LEVEL_OPTIONS = [
  { value: 'COMPANY_WIDE', label: 'Company-wide (all employees)' },
  { value: 'HR_ONLY', label: 'HR and administrators only' },
];
const ACCESS_LABELS = { COMPANY_WIDE: 'Company-wide', HR_ONLY: 'HR only' };
const STAGES = {
  APPROVED: { label: 'Ready for processing', badge: 'pending' },
  NEEDS_REVIEW: { label: 'Awaiting review · some pages have no text', badge: 'pending' },
  INGESTING: { label: 'Processing', badge: 'pending' },
  INDEXED: { label: 'Ready for publication', badge: 'approved' },
  FAILED: { label: 'Processing failed', badge: 'rejected' },
};
const errorMessage = (error, fallback) => error?.response?.data?.message || fallback;

/** "Needs review" says why: pages without text, flagged content, or both. */
function stageInfo(version) {
  if (version.stage !== 'NEEDS_REVIEW') return STAGES[version.stage];
  const contentPending = version.contentReview?.required && !version.contentReview?.satisfied;
  const textPending = version.pagesWithoutText > 0 && !version.incompleteTextAccepted;
  if (contentPending && textPending) return { label: 'Awaiting review · flagged content and pages without text', badge: 'pending' };
  if (contentPending) return { label: 'Awaiting review · flagged content', badge: 'pending' };
  return STAGES.NEEDS_REVIEW;
}

const CONTENT_DECISION_LABELS = {
  INCLUDE_FLAGGED_TEXT: 'Flagged text included',
  REPLACE_DOCUMENT: 'Rejected · replacement required',
  DO_NOT_APPROVE: 'Rejected',
};

function documentBadge(document) {
  if (document.status === 'REVOKED') return { status: 'inactive', label: 'Retired' };
  if (document.activeVersion) return { status: 'active', label: 'Published' };
  return { status: 'pending', label: document.pendingVersion ? 'In review' : 'Not published' };
}

function wavyStatus(document, settings) {
  if (document.status === 'REVOKED' || !document.activeVersion) return 'Not published for Wavy';
  if (!settings?.retrievalEnabled || !settings?.chatGroundingEnabled) return 'Published; Wavy policy answers are unavailable';
  if (document.activeVersion.stage !== 'INDEXED' || !(document.activeVersion.indexedSectionCount > 0)) {
    return 'Published; search readiness cannot be confirmed';
  }
  return 'Published and indexed; Wavy availability depends on search readiness and access';
}

function PolicyFileInput({ inputKey, settings, onChange }) {
  return <label className="knowledge-base__file-input">
    <span>Policy file</span>
    <input key={inputKey} type="file" accept={policyFileAccept(settings)} onChange={(event) => onChange(event.target.files?.[0] || null)} />
  </label>;
}

function AddPolicyForm({ settings, busy, onSubmit }) {
  const [file, setFile] = useState(null);
  const [title, setTitle] = useState('');
  const [accessLevel, setAccessLevel] = useState('COMPANY_WIDE');
  const [error, setError] = useState('');
  const [inputKey, setInputKey] = useState(0);

  const submit = async (event) => {
    event.preventDefault();
    const fileError = validatePolicyFile(file, settings);
    if (fileError) { setError(fileError); return; }
    if (!title.trim()) { setError('Enter a title for the policy.'); return; }
    setError('');
    if (await onSubmit({ file, title: title.trim(), accessLevel })) {
      setFile(null); setTitle(''); setAccessLevel('COMPANY_WIDE'); setInputKey((key) => key + 1);
    }
  };

  return <form onSubmit={submit} className="knowledge-base__form" noValidate aria-label="Add a company policy">
    <TextInput name="knowledgeTitle" label="Policy title" required value={title} maxLength={200}
      onChange={(event) => setTitle(event.target.value)} />
    <Dropdown name="knowledgeAccessLevel" label="Who may the assistant answer from it?" value={accessLevel}
      onChange={(event) => setAccessLevel(event.target.value)} options={ACCESS_LEVEL_OPTIONS} />
    <PolicyFileInput inputKey={inputKey} settings={settings} onChange={setFile} />
    <p className="knowledge-base__hint">PDF, DOCX, XLSX, TXT or Markdown, up to 5 MB. The file is checked locally when you add it;
      its text is sent for indexing only when you choose Process for AI Search.</p>
    <ValidationMessage variant="error" message={error} />
    <div className="knowledge-base__form-actions"><Button type="submit" loading={busy} disabled={busy}>Add policy</Button></div>
  </form>;
}

function ReplacePolicyForm({ document, settings, busy, onSubmit, onCancel }) {
  const [file, setFile] = useState(null);
  const [accessLevel, setAccessLevel] = useState(
    document.pendingVersion?.accessLevel || document.activeVersion?.accessLevel || 'COMPANY_WIDE');
  const [error, setError] = useState('');

  const submit = async (event) => {
    event.preventDefault();
    const fileError = validatePolicyFile(file, settings);
    if (fileError) { setError(fileError); return; }
    setError('');
    if (await onSubmit({ file, accessLevel })) onCancel();
  };

  return <form onSubmit={submit} className="knowledge-base__form knowledge-base__form--inline" noValidate
    aria-label={`Replace ${document.title}`}>
    <Dropdown name={`replaceAccessLevel-${document.id}`} label="Access" value={accessLevel}
      onChange={(event) => setAccessLevel(event.target.value)} options={ACCESS_LEVEL_OPTIONS} />
    <PolicyFileInput inputKey={0} settings={settings} onChange={setFile} />
    <p className="knowledge-base__hint">The current active version keeps answering questions until the replacement is activated.</p>
    <ValidationMessage variant="error" message={error} />
    <div className="knowledge-base__form-actions">
      <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
      <Button type="submit" loading={busy} disabled={busy}>Approve replacement</Button>
    </div>
  </form>;
}

function VersionDetails({ kind, version }) {
  if (!version) return null;
  const stage = stageInfo(version);
  const contentDecision = CONTENT_DECISION_LABELS[version.contentReview?.decision];
  const pages = version.pageCount == null ? null
    : `${version.pageCount}${version.pagesWithoutText > 0
      ? ` (${version.pagesWithoutText} without text${version.incompleteTextAccepted ? ', accepted' : ''})` : ''}`;
  return <section className="knowledge-base__version" aria-label={kind === 'active' ? 'Active version' : 'Pending version'}>
    <p className="knowledge-base__version-label">{kind === 'active' ? 'Active version' : 'Pending version'}</p>
    <p className="knowledge-base__file">{version.fileName || 'Unnamed file'}</p>
    <dl className="knowledge-base__facts">
      <div><dt>Access</dt><dd>{ACCESS_LABELS[version.accessLevel] || 'Unknown'}</dd></div>
      {kind === 'active'
        ? <div><dt>Active since</dt><dd>{formatDateOnly(version.activatedAt) || 'Unknown'}</dd></div>
        : <div><dt>Status</dt><dd><StatusBadge status={stage?.badge} label={stage?.label || version.stage} /></dd></div>}
      {version.indexedSectionCount != null && <div><dt>Indexed sections</dt><dd>{version.indexedSectionCount}</dd></div>}
      {pages && <div><dt>Pages</dt><dd>{pages}</dd></div>}
      {contentDecision && <div><dt>Content review</dt><dd>{contentDecision}</dd></div>}
      <div><dt>Approved</dt><dd>{formatDateOnly(version.approvedAt) || 'Unknown'}</dd></div>
    </dl>
    {version.problem && <ValidationMessage variant="error" message={version.problem} />}
  </section>;
}

function KnowledgeDocumentItem({ document, settings, busy, feedback, onRun, onConfirm }) {
  const [replacing, setReplacing] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const { actions = {}, pendingVersion: pending } = document;
  const badge = documentBadge(document);
  // Offered only when the server says so; revoked entries never are, and the server re-checks every decision.
  const canReviewContent = Boolean(actions.reviewContent && pending && document.status !== 'REVOKED');
  const previousVersions = Array.isArray(document.previousVersions) ? document.previousVersions : [];

  const decide = async (decision, review) => {
    const succeeded = await onRun(document.id, () => myHrKnowledgeApi.reviewContent(document.id, review));
    if (succeeded) {
      setReviewing(false);
      // A rejected version is replaced through the existing replacement workflow.
      if (decision === 'REPLACE_DOCUMENT') setReplacing(true);
    }
    return succeeded;
  };

  return <li className={`knowledge-base__item ${document.status === 'REVOKED' ? 'is-revoked' : ''}`}>
    <div className="knowledge-base__item-header">
      <h3>{document.title}</h3>
      <StatusBadge status={badge.status} label={badge.label} />
    </div>
    <p className="knowledge-base__availability">{wavyStatus(document, settings)}</p>
    {!document.activeVersion && document.status !== 'REVOKED'
      && <p className="knowledge-base__hint">The assistant does not use this policy until a version is activated.</p>}
    <div className="knowledge-base__versions">
      <VersionDetails kind="active" version={document.activeVersion} />
      <VersionDetails kind="pending" version={pending} />
    </div>
    {document.previousVersionCount > 0
      && <p className="knowledge-base__hint">{document.previousVersionCount} earlier version{document.previousVersionCount === 1 ? '' : 's'} kept in history.</p>}
    {previousVersions.length > 0 && <section className="knowledge-base__history" aria-label="Previous policy versions">
      <p className="knowledge-base__version-label">Previous active versions</p>
      <ul>
        {previousVersions.map((version) => {
          const restoreAvailable = Boolean(actions.restoreHistoricalVersion && version?.sourceDocumentId && version?.restorable);
          return <li key={version.sourceDocumentId}>
            <span><strong>{version.fileName || 'Unnamed file'}</strong>
              {version.accessLevel && <> · {ACCESS_LABELS[version.accessLevel] || 'Unknown access'}</>}
              {version.supersededAt && <> · superseded {formatDateOnly(version.supersededAt)}</>}
            </span>
            {restoreAvailable && <Button variant="secondary" disabled={busy} onClick={() => onConfirm({
              id: document.id,
              title: 'Restore this policy version?',
              message: `${version.fileName || 'This prior file'} will be re-approved as a new pending version. It must pass current content inspection, any required review, ingestion, and activation before it can answer questions.`,
              confirmLabel: 'Restore for review',
              action: () => myHrKnowledgeApi.restoreHistoricalVersion(document.id, version.sourceDocumentId),
            })}>Restore for review</Button>}
          </li>;
        })}
      </ul>
      <p className="knowledge-base__hint">Restoring never reactivates historic chunks or skips the current review gates.</p>
    </section>}
    {document.status === 'REVOKED' && <p className="knowledge-base__hint">Revoked {formatDateOnly(document.revokedAt)}. To restore it, add the policy again.</p>}

    {canReviewContent && !reviewing && <p className="knowledge-base__hint">
      Content inspection flagged this version. Review the extracted text before it can be ingested.</p>}
    <div className="knowledge-base__actions">
      {canReviewContent && !reviewing && <Button disabled={busy} onClick={() => setReviewing(true)}>
        Review extracted content</Button>}
      {actions.acceptIncompleteText && pending && <Button variant="secondary" disabled={busy} onClick={() => onConfirm({
        id: document.id,
        title: 'Accept incomplete text?',
        message: `${pending.pagesWithoutText} of ${pending.pageCount} pages in ${pending.fileName} have no extractable text, so the assistant will not know what they say. Accept the file as it is?`,
        confirmLabel: 'Accept',
        action: () => myHrKnowledgeApi.acceptIncompleteText(document.id, pending.sourceDocumentId),
      })}>Accept incomplete text</Button>}
      {actions.ingest && pending && <Button disabled={busy} loading={busy}
        onClick={() => onRun(document.id, () => myHrKnowledgeApi.ingest(document.id))}>
        {pending.stage === 'APPROVED' ? 'Process for AI Search' : 'Retry processing'}</Button>}
      {actions.activate && pending && <Button disabled={busy} onClick={() => onConfirm({
        id: document.id,
        title: 'Publish this policy version?',
        message: document.activeVersion
          ? `${pending.fileName} will replace ${document.activeVersion.fileName} as the source for assistant answers.`
          : `${pending.fileName} will become a source for assistant answers (${ACCESS_LABELS[pending.accessLevel] || 'restricted'}).`,
        confirmLabel: 'Publish policy',
        action: () => myHrKnowledgeApi.activate(document.id, pending.ingestionId),
      })}>Publish policy</Button>}
      {actions.replace && <Button variant="secondary" disabled={busy} onClick={() => setReplacing((value) => !value)}
        aria-expanded={replacing}>{replacing ? 'Close replacement' : 'Replace file'}</Button>}
      {actions.revoke && <Button variant="secondary" disabled={busy} onClick={() => onConfirm({
        id: document.id,
        title: 'Retire this policy?',
        message: `"${document.title}" will immediately stop informing assistant answers and its indexed sections will be removed.`,
        confirmLabel: 'Retire policy',
        action: () => myHrKnowledgeApi.revoke(document.id),
      })}>Retire policy</Button>}
      {actions.retryCleanup && <Button variant="secondary" disabled={busy}
        onClick={() => onRun(document.id, () => myHrKnowledgeApi.revoke(document.id))}>Retry cleanup</Button>}
    </div>
    {canReviewContent && reviewing && <ContentReviewPanel key={pending.sourceDocumentId} document={document} busy={busy}
      onDecision={decide} onClose={() => setReviewing(false)} />}
    {replacing && <ReplacePolicyForm document={document} settings={settings} busy={busy}
      onSubmit={(values) => onRun(document.id, () => myHrKnowledgeApi.replacePolicy(document.id, values))}
      onCancel={() => setReplacing(false)} />}
    {feedback && <ValidationMessage variant={feedback.variant} message={feedback.message} />}
  </li>;
}

export default function KnowledgeBasePage() {
  const [settings, setSettings] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [feedback, setFeedback] = useState(null);
  const [busyId, setBusyId] = useState('');
  const [confirmation, setConfirmation] = useState(null);
  const [showAdd, setShowAdd] = useState(false);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const requestInFlight = useRef(false);

  const load = useCallback(async () => {
    try {
      const [settingsResponse, documentsResponse] = await Promise.all([
        myHrKnowledgeApi.getSettings(), myHrKnowledgeApi.listDocuments()]);
      setSettings(settingsResponse?.data?.data || null);
      setDocuments(Array.isArray(documentsResponse?.data?.data) ? documentsResponse.data.data : []);
      setLoadError('');
    } catch (error) {
      if (error?.response?.status !== 401) setLoadError(errorMessage(error, 'Unable to load the knowledge base.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /** Runs one request, shows its message next to the policy, then reloads; resolves true on success. */
  const run = async (id, action) => {
    if (requestInFlight.current) return false;
    requestInFlight.current = true;
    setBusyId(id);
    setFeedback(null);
    let succeeded = false;
    try {
      const response = await action();
      const outcome = response?.data?.data?.outcome;
      succeeded = outcome !== 'FAILED';
      setFeedback({ id, variant: succeeded ? 'hint' : 'error', message: response?.data?.message || 'Done.' });
    } catch (error) {
      if (error?.response?.status !== 401) {
        setFeedback({ id, variant: 'error', message: errorMessage(error, 'The request could not be completed.') });
      }
    } finally {
      await load();
      setBusyId('');
      requestInFlight.current = false;
    }
    return succeeded;
  };

  const confirm = async () => {
    const pending = confirmation;
    setConfirmation(null);
    if (pending) await run(pending.id, pending.action);
  };

  const visibleDocuments = useMemo(() => documents.filter((document) => {
    const matchesSearch = (document.title || '').toLocaleLowerCase().includes(search.trim().toLocaleLowerCase());
    const status = document.status === 'REVOKED' ? 'retired' : document.activeVersion ? 'published'
      : document.pendingVersion ? 'review' : 'unpublished';
    return matchesSearch && (statusFilter === 'all' || statusFilter === status);
  }), [documents, search, statusFilter]);
  const renderItem = (document) => <KnowledgeDocumentItem key={document.id} document={document} settings={settings}
    busy={Boolean(busyId)} feedback={feedback?.id === document.id ? feedback : null}
    onRun={run} onConfirm={setConfirmation} />;

  return <MyHrFeaturePage title="Knowledge Base"
    description="Manage company policies and prepare approved documents for Wavy." hideBack
    className="knowledge-base-page">
    {loading ? <p className="myhr-feature__notice" role="status">Loading knowledge base…</p>
      : loadError ? <ValidationMessage message={loadError} variant="error" />
      : <div className="knowledge-base">
        <div className="knowledge-base__toolbar">
          <div><h2>Company policies</h2><p>Review, publish and manage your organization's policy documents.</p></div>
          {settings?.managementEnabled && <Button disabled={Boolean(busyId)} onClick={() => setShowAdd((value) => !value)} aria-expanded={showAdd}>
            {showAdd ? 'Close' : 'Add policy'}</Button>}
        </div>
        {!settings?.managementEnabled && <ValidationMessage message="Adding policies is currently unavailable. Existing policies can still be reviewed." />}
        {settings?.managementEnabled && !settings?.ingestionEnabled && <ValidationMessage
          message="Processing for AI Search is currently turned off for this environment, so new or replaced policies cannot be processed yet." />}
        {settings?.managementEnabled && !settings?.activationEnabled && <ValidationMessage
          message="Publishing is currently turned off for this environment, so processed policies cannot be published to Wavy yet." />}
        {settings?.managementEnabled && showAdd && <Card title="Add a company policy">
          <AddPolicyForm settings={settings} busy={busyId === 'new'}
            onSubmit={(values) => run('new', () => myHrKnowledgeApi.addPolicy(values))} />
          {feedback?.id === 'new' && <ValidationMessage variant={feedback.variant} message={feedback.message} />}
        </Card>}

        <Card title="Policies">
          <div className="knowledge-base__filters">
            <TextInput name="policySearch" label="Search policies" value={search} onChange={(event) => setSearch(event.target.value)} />
            <Dropdown name="policyStatus" label="Status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} options={[
              { value: 'all', label: 'All policies' }, { value: 'published', label: 'Published' },
              { value: 'review', label: 'In review' }, { value: 'unpublished', label: 'Not published' },
              { value: 'retired', label: 'Retired' },
            ]} />
          </div>
          {visibleDocuments.length === 0
            ? <EmptyState title={documents.length ? 'No matching policies' : 'No policies yet'} description={documents.length ? 'Try another search or status.' : 'Add a policy to begin the review process.'} />
            : <ul className="knowledge-base__list">{visibleDocuments.map(renderItem)}</ul>}
        </Card>

        {/* Shown to Company Admins only; the backend authorizes every request regardless. */}
        {getMyHrSession()?.role === 'COMPANY_ADMIN' && <AiProcessingPermissions documents={documents} />}
      </div>}

    <ConfirmationDialog isOpen={Boolean(confirmation)} title={confirmation?.title} message={confirmation?.message}
      confirmLabel={confirmation?.confirmLabel} onConfirm={confirm} onCancel={() => setConfirmation(null)} />
  </MyHrFeaturePage>;
}
