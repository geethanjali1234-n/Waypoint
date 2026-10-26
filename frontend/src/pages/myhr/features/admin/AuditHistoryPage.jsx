import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Dropdown, EmptyState, Loader, DetailPanel, FilterPills, SearchInput, StatusBadge as StatusTag, TextInput } from '../../../../components';
import myHrAuditApi from '../../../../api/myHrAuditApi';
import { formatDateTime } from '../../../../utils/dateFormat';
import { formatDisplayLabel } from '../../../../utils/myHrDisplayLabel';
import MyHrFeaturePage from '../MyHrFeaturePage';
import MyHrPagination from '../MyHrPagination';
import Table from '../../components/WorkspaceDataTable';
import StrokeIcon from '../../../../components/global/StrokeIcon';
import './auditHistory.css';

const PAGE_SIZE = 10;
const EMPTY_FILTERS = { search: '', category: 'ALL', actionType: 'ALL', from: '', to: '' };

// Percentage widths (not left implicit) so every column shares a bounded,
// proportional slice of the table's own width. Left unconstrained, table-layout:
// auto dumps 100% of any leftover width into whichever column has no explicit
// width - here that was "Details", which stretched to fill nearly the whole
// row while "Outcome" sat isolated at the far right. Percentages plus
// table-layout: fixed (auditHistory.css) make the row read as one balanced
// table at any width instead.
const COLUMNS = [
  { key: 'performedAt', header: 'When', width: '16%' },
  { key: 'actor', header: 'Performed by', width: '16%' },
  { key: 'action', header: 'Action', width: '20%' },
  { key: 'entity', header: 'Details', width: '38%' },
  { key: 'outcome', header: 'Outcome', width: '10%' },
];

function errorMessage(error, fallback) {
  return error?.response?.data?.message || fallback;
}

const humanizeCategory = formatDisplayLabel;

function formatTimestamp(value) {
  return value ? formatDateTime(value) : '—';
}

function roleLabel(role) {
  if (!role) return 'System';
  return { COMPANY_ADMIN: 'Company Admin', HR: 'HR', EMPLOYEE: 'Employee' }[role] || role;
}

const PROFILE_FIELD_LABELS = {
  NAME: 'Name', PERSONAL_EMAIL: 'Personal Email', PHONE_NUMBER: 'Phone Number', ADDRESS: 'Address', DESIGNATION: 'Designation',
};

function humanizeKey(key) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase())
    .replace(/\bUser Id\b/i, 'User')
    .replace(/\bId\b/, '');
}

function formatScalar(value) {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  return formatDisplayLabel(value);
}

function formatFieldValue(value) {
  if (value === null || value === undefined) return '—';
  if (Array.isArray(value)) return value.length ? value.map(formatScalar).join(', ') : '—';
  if (typeof value === 'object') {
    const parts = Object.values(value).map(formatScalar).filter((part) => part !== '—');
    return parts.length ? parts.join(', ') : '—';
  }
  return formatScalar(value);
}

function isEmptyValue(value) {
  return value === null || value === undefined || value === '';
}

function participantContext(row) {
  if (row.entityType !== 'DOCUMENT_REQUEST' || !row.updatedValue || typeof row.updatedValue !== 'object') return '';
  const parts = [];
  if (row.updatedValue.requestedBy) parts.push(`Requested by ${row.updatedValue.requestedBy}`);
  if (row.updatedValue.requestedFor) parts.push(`Requested for ${row.updatedValue.requestedFor}`);
  return parts.join(' · ');
}

function renderChangeValue(value) {
  if (isEmptyValue(value)) return <span className="audit-history__diff-empty">—</span>;
  if (typeof value !== 'object') return <span>{formatScalar(value)}</span>;

  // Profile-change convention: { fieldName, value, ...extra (e.g. status) }.
  if ('fieldName' in value && 'value' in value) {
    const label = PROFILE_FIELD_LABELS[value.fieldName] || humanizeKey(String(value.fieldName));
    return (
      <div className="audit-history__diff-field">
        <strong>{label}</strong>
        <span>{formatFieldValue(value.value)}</span>
      </div>
    );
  }

  // Raw ids (e.g. reportingManagerUserId) are never useful to a reader — the same
  // event always carries a human-readable counterpart (e.g. reportingManagerName).
  const entries = Object.entries(value)
    .filter(([key]) => !/UserId$/i.test(key))
    .filter(([, entryValue]) => !isEmptyValue(entryValue));
  if (entries.length === 0) return <span className="audit-history__diff-empty">—</span>;
  return (
    <dl className="audit-history__diff-fields">
      {entries.map(([key, entryValue]) => (
        <div key={key}>
          <dt>{humanizeKey(key)}</dt>
          <dd>{formatFieldValue(entryValue)}</dd>
        </div>
      ))}
    </dl>
  );
}

export default function AuditHistoryPage() {
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(1);
  const [state, setState] = useState({ loading: true, error: null, data: null });
  const [selected, setSelected] = useState(null);
  const [viewMode, setViewMode] = useState('cards');

  const load = useCallback(() => {
    setState((current) => ({ ...current, loading: true, error: null }));
    myHrAuditApi.history({
      page: page - 1,
      size: PAGE_SIZE,
      search: filters.search.trim(),
      category: filters.category,
      actionType: filters.actionType,
      from: filters.from || undefined,
      to: filters.to || undefined,
    })
      .then((response) => setState({ loading: false, error: null, data: response.data.data }))
      .catch((error) => {
        if (error?.response?.status === 401) return;
        setState({ loading: false, error: errorMessage(error, 'Unable to load audit history.'), data: null });
      });
  }, [page, filters]);

  useEffect(() => {
    const timer = window.setTimeout(load, 300);
    return () => window.clearTimeout(timer);
  }, [load]);

  const categoryOptions = useMemo(() => {
    const categories = state.data?.categories || [];
    return [{ value: 'ALL', label: 'All categories' }, ...categories.map((value) => ({ value, label: humanizeCategory(value) }))];
  }, [state.data?.categories]);

  const actionOptions = useMemo(() => {
    const catalog = state.data?.actionCatalog || [];
    const scoped = filters.category === 'ALL'
      ? catalog
      : catalog.filter((option) => option.category === filters.category);
    return [{ value: 'ALL', label: 'All actions' }, ...scoped.map((option) => ({ value: option.value, label: option.label }))];
  }, [state.data?.actionCatalog, filters.category]);

  const setFilter = (name) => (event) => {
    const { value } = event.target;
    setPage(1);
    setFilters((current) => ({
      ...current,
      [name]: value,
      ...(name === 'category' ? { actionType: 'ALL' } : {}),
    }));
  };

  const resetFilters = () => { setPage(1); setFilters(EMPTY_FILTERS); };

  const hasFilters = filters.search.trim() || filters.category !== 'ALL' || filters.actionType !== 'ALL' || filters.from || filters.to;

  const data = state.data;
  const rows = (data?.items || []).map((item) => ({ ...item, id: item.id }));
  const totalPages = data?.totalPages || 0;

  const renderCell = (column, row) => {
    if (column.key === 'performedAt') return formatTimestamp(row.performedAt);
    if (column.key === 'actor') {
      return (
        <span className="audit-history__cell-actor">
          <strong>{row.actorName || row.actorLoginId || 'System'}</strong>
          <small>{[row.actorLoginId, roleLabel(row.actorRole)].filter(Boolean).join(' · ')}</small>
        </span>
      );
    }
    if (column.key === 'action') {
      return (
        <button type="button" className="audit-history__row-link" onClick={() => setSelected(row)}>
          <span className="audit-history__cell-action">
            {row.actionLabel || row.actionType}
            <small>{row.category ? humanizeCategory(row.category) : ''}</small>
          </span>
        </button>
      );
    }
    if (column.key === 'entity') {
      const context = participantContext(row);
      return (
        <span className="audit-history__cell-action">
          {row.entityDisplayName || row.entityId || '—'}
          <small>{[row.description, context].filter(Boolean).join(' · ')}</small>
        </span>
      );
    }
    if (column.key === 'outcome') {
      return <StatusTag status={row.outcome === 'FAILURE' ? 'rejected' : row.outcome === 'SUCCESS' ? 'approved' : 'inactive'} label={row.outcome === 'FAILURE' ? 'Failed' : row.outcome === 'SUCCESS' ? 'Success' : formatDisplayLabel(row.outcome) || 'Outcome unavailable'} />;
    }
    return row[column.key] || '—';
  };

  return (
    <MyHrFeaturePage
      title="Audit History"
      description="Every significant change across the organization, newest first."
      hideBack className="phase-six audit-history-page"
    >
      <div className="audit-history">
        <div className="audit-history__toolbar">
          <div className="phase-six__search"><label htmlFor="auditSearch">Search audit history</label><SearchInput id="auditSearch"
            value={filters.search}
            onChange={setFilter('search')}
            placeholder="Search by person, record, or description"
          /></div>
          <FilterPills label="Audit categories" options={categoryOptions} value={filters.category} onChange={(value) => setFilter('category')({ target: { value } })} />
          <Dropdown name="actionType" label="Action" value={filters.actionType} onChange={setFilter('actionType')} options={actionOptions} />
          <TextInput name="from" label="From" type="date" value={filters.from} onChange={setFilter('from')} />
          <TextInput name="to" label="To" type="date" value={filters.to} onChange={setFilter('to')} />
          <Button type="button" variant="secondary" onClick={resetFilters} disabled={!hasFilters}>Clear filters</Button>
          <div className="audit-history__view-toggle" role="group" aria-label="View format">
            <button
              type="button"
              className={`audit-history__view-toggle-btn ${viewMode === 'table' ? 'is-active' : ''}`}
              onClick={() => setViewMode('table')}
              aria-pressed={viewMode === 'table'}
              title="Table View"
            >
              <StrokeIcon name="table" size={20} /> Table
            </button>
            <button
              type="button"
              className={`audit-history__view-toggle-btn ${viewMode === 'cards' ? 'is-active' : ''}`}
              onClick={() => setViewMode('cards')}
              aria-pressed={viewMode === 'cards'}
              title="Timeline View"
            >
              <StrokeIcon name="time" size={20} /> Timeline
            </button>
          </div>
        </div>

        {state.loading ? <Loader label="Loading audit history…" />
          : state.error ? <EmptyState title="Could not load audit history" description={state.error} action={<Button type="button" variant="secondary" onClick={load}>Try again</Button>} />
          : rows.length === 0 ? <EmptyState title={hasFilters ? 'No events match these filters' : 'No audit history yet'} description={hasFilters ? 'Adjust or reset the filters to see more activity.' : 'Organization changes will appear here as they happen.'} />
          : <>
            <p className="audit-history__summary">
              Showing {rows.length} of {data.totalElements} event{data.totalElements === 1 ? '' : 's'}
              {hasFilters ? ' (filtered)' : ''}
            </p>
            {viewMode === 'cards' ? (
              <ol className="audit-history__cards-grid" aria-label="Audit event timeline">
                {rows.map((row) => (
                  <li key={row.id} className="audit-history__card">
                    <div className="audit-history__card-header">
                      <div className="audit-history__card-action-wrap">
                        <span className="audit-history__card-category">{row.category ? humanizeCategory(row.category) : 'General'}</span>
                        <button
                          type="button"
                          className="audit-history__card-action-title"
                          onClick={() => setSelected(row)}
                        >
                          {row.actionLabel || row.actionType}
                        </button>
                      </div>
                      <StatusTag status={row.outcome === 'FAILURE' ? 'rejected' : row.outcome === 'SUCCESS' ? 'approved' : 'inactive'} label={row.outcome === 'FAILURE' ? 'Failed' : row.outcome === 'SUCCESS' ? 'Success' : formatDisplayLabel(row.outcome) || 'Outcome unavailable'} />
                    </div>
                    <div className="audit-history__card-body">
                      <div className="audit-history__card-row">
                        <span className="audit-history__card-label">Performed by</span>
                        <span><strong>{row.actorName || row.actorLoginId || 'System'}</strong> <small>({roleLabel(row.actorRole)})</small></span>
                      </div>
                      <div className="audit-history__card-row">
                        <span className="audit-history__card-label">When</span>
                        <span>{formatTimestamp(row.performedAt)}</span>
                      </div>
                      <div className="audit-history__card-row audit-history__card-row--full">
                        <span className="audit-history__card-label">Details</span>
                        <span><strong>{row.entityDisplayName || row.entityId || '—'}</strong> {[row.description, participantContext(row)].filter(Boolean).length > 0 ? ` · ${[row.description, participantContext(row)].filter(Boolean).join(' · ')}` : ''}</span>
                      </div>
                    </div>
                    <div className="audit-history__card-footer">
                      <Button type="button" variant="secondary" onClick={() => setSelected(row)}>
                        View Details
                      </Button>
                    </div>
                  </li>
                ))}
              </ol>
            ) : (
              <Table label="Audit history" pagination={{ page, pageSize: PAGE_SIZE, total: data.totalElements, onPageChange: setPage }} className="audit-history__table" columns={COLUMNS} rows={rows} renderCell={renderCell} emptyMessage="No events found." />
            )}
            {viewMode === 'cards' && totalPages > 1 && (
              <div className="audit-history__pagination">
                <MyHrPagination currentPage={page} totalPages={totalPages} onPageChange={setPage} />
              </div>
            )}
          </>}
      </div>

      <DetailPanel
        open={Boolean(selected)}
        title={selected ? (selected.actionLabel || formatDisplayLabel(selected.actionType)) : 'Audit event'}
        className="audit-history__modal"
        onClose={() => setSelected(null)}
        footer={<Button type="button" variant="secondary" onClick={() => setSelected(null)}>Close</Button>}
      >
        {selected && (
          <div className="audit-history__detail">
            <dl>
              <div><dt>When</dt><dd>{formatTimestamp(selected.performedAt)}</dd></div>
              <div><dt>Outcome</dt><dd>{formatDisplayLabel(selected.outcome || 'SUCCESS')}</dd></div>
              <div><dt>Performed by</dt><dd>{selected.actorName || selected.actorLoginId || 'System'}</dd></div>
              <div><dt>Role</dt><dd>{roleLabel(selected.actorRole)}</dd></div>
              <div><dt>Category</dt><dd>{selected.category ? humanizeCategory(selected.category) : '—'}</dd></div>
              <div><dt>Source</dt><dd>{formatDisplayLabel(selected.source) || '—'}</dd></div>
              <div><dt>Record type</dt><dd>{formatDisplayLabel(selected.entityType) || '—'}</dd></div>
              <div><dt>Record</dt><dd>{selected.entityDisplayName || selected.entityId || '—'}</dd></div>
            </dl>
            <p style={{ marginTop: '14px' }}>{selected.description}</p>
            {participantContext(selected) && <p className="audit-history__participant-context">{participantContext(selected)}</p>}
            {selected.source === 'PROFILE_CHANGE_RECONCILER' && (
              <p className="audit-history__derived-note">
                Recorded from the approved profile change request.
              </p>
            )}
            {selected.failureReason && <p className="myhr-feature__error">{selected.failureReason}</p>}

            {(!isEmptyValue(selected.previousValue) || !isEmptyValue(selected.updatedValue)) && (
              <>
                <h4>Change</h4>
                <div className="audit-history__diff">
                  <div>
                    <p className="audit-history__diff-label">Before</p>
                    {renderChangeValue(selected.previousValue)}
                  </div>
                  <div>
                    <p className="audit-history__diff-label">After</p>
                    {renderChangeValue(selected.updatedValue)}
                  </div>
                </div>
              </>
            )}
          </div>
        )}
      </DetailPanel>
    </MyHrFeaturePage>
  );
}
