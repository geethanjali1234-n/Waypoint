import { useState } from 'react';
import { RouteLine, PageHeader, FilterPills, StatusBadge, DataTable, NeedsYouList, DetailPanel, FloatingAssistant, Button } from '../../../components';
import { useTheme } from '../../../context/ThemeContext';
import './RouteLinePreview.css';

/** Development-only component gallery; no operational records or metrics. */
export default function RouteLinePreview() {
  const [filter, setFilter] = useState('all');
  const [open, setOpen] = useState(false);
  const { toggleTheme } = useTheme();
  const steps = [{ label: 'Requested', state: 'done' }, { label: 'Review', state: 'current', sublabel: 'Current action' }, { label: 'Filed', state: 'upcoming' }];
  return <main className="wp-primitive-preview">
    <PageHeader eyebrow="Design system · Phase 2" title="Route-line components" summary="Component preview only. No employee records or operational counts." primaryAction={<Button onClick={toggleTheme}>Toggle theme</Button>} />
    <section><h2>Progress</h2><RouteLine steps={steps} /><RouteLine steps={steps} orientation="vertical" label="Vertical progress" /></section>
    <section><h2>Filters and status</h2><FilterPills options={[{ value: 'all', label: 'All' }, { value: 'pending', label: 'Pending' }, { value: 'approved', label: 'Approved' }]} value={filter} onChange={setFilter} /><div className="wp-primitive-preview__badges">{['active', 'pending', 'approved', 'rejected', 'inactive', 'draft'].map((status) => <StatusBadge key={status} status={status} />)}</div></section>
    <section><h2>Table</h2><DataTable columns={[{ key: 'person', header: 'Person', type: 'person' }, { key: 'status', header: 'Status', type: 'status' }]} rows={[]} emptyMessage="This preview does not load employee records." /></section>
    <section><h2>Needs you today</h2><NeedsYouList items={[]} emptyMessage="Action rows appear when a page supplies real workflow data." /></section>
    <Button onClick={() => setOpen(true)}>Preview detail panel</Button>
    <DetailPanel open={open} onClose={() => setOpen(false)} title="Detail panel"><p>Desktop side drawer and mobile bottom sheet. Escape closes it and returns focus to the launcher.</p><label htmlFor="preview-note">Preview note</label><input id="preview-note" /></DetailPanel>
    <FloatingAssistant label="Preview assistant panel" onClick={() => setOpen(true)} />
  </main>;
}
