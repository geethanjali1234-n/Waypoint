import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import myHrReportApi from '../../api/myHrReportApi';
import { getMyHrSession } from '../../auth/myHrSession';
import {
  ROLES,
  getRoleRouteSegment,
  navigationRegistry,
  resolveEffectiveRole,
} from '../../services/navigation/navigationRegistry';
import './CommandPalette.css';
import StrokeIcon from '../global/StrokeIcon';

export default function CommandPalette({ isOpen, onClose, actions, enableEmployeeSearch = true }) {
  const navigate = useNavigate();
  const location = useLocation();
  const session = getMyHrSession();
  const effectiveRole = resolveEffectiveRole(location.pathname, session);
  const roleSegment = getRoleRouteSegment(effectiveRole);

  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [employees, setEmployees] = useState([]);
  const [searching, setSearching] = useState(false);
  const inputRef = useRef(null);
  const listRef = useRef(null);
  const searchTimeoutRef = useRef(null);
  const panelRef = useRef(null);
  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; }, [onClose]);

  // Retrieve permitted actions strictly evaluated by role and query via NavigationRegistry
  const filteredActions = actions === undefined ? navigationRegistry.getActions(effectiveRole, query) : actions.filter((action) => `${action.title} ${action.subtitle || ''} ${action.category || ''}`.toLowerCase().includes(query.trim().toLowerCase()));


  // Debounced search for live employees
  useEffect(() => {
    if (!isOpen) return;
    if (!enableEmployeeSearch) {
      setEmployees([]);
      setSearching(false);
      return;
    }
    if (query.trim().length < 2) {
      setEmployees([]);
      setSearching(false);
      return;
    }

    if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
    setSearching(true);

    searchTimeoutRef.current = setTimeout(() => {
      myHrReportApi
        .searchEmployees(query.trim(), { limit: 6 })
        .then((res) => {
          setEmployees(res.data?.data || []);
        })
        .catch(() => {
          setEmployees([]);
        })
        .finally(() => {
          setSearching(false);
        });
    }, 200);

    return () => {
      if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
    };
  }, [query, isOpen, enableEmployeeSearch]);

  // Combined list of selectable items
  const allItems = [
    ...filteredActions.map((action) => ({ type: 'action', item: action })),
    ...employees.map((emp) => ({ type: 'employee', item: emp })),
  ];

  // Auto-focus input when opened
  useEffect(() => {
    if (isOpen) {
      const previousFocus = document.activeElement;
      const previousOverflow = document.body.style.overflow;
      const background = [...document.body.children].filter((child) => !child.contains(panelRef.current));
      const previousInert = background.map((child) => child.hasAttribute('inert'));
      background.forEach((child) => child.setAttribute('inert', ''));
      document.body.style.overflow = 'hidden';
      setQuery('');
      setSelectedIndex(0);
      const timeout = setTimeout(() => inputRef.current?.focus(), 50);
      const trapFocus = (event) => {
        if (!panelRef.current?.contains(event.target)) return;
        if (event.key === 'Escape' && !event.defaultPrevented) {
          event.preventDefault();
          closeRef.current();
        }
        if (event.key === 'Tab') {
          const controls = [...panelRef.current.querySelectorAll('input, button:not(:disabled)')];
          const first = controls[0];
          const last = controls.at(-1);
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }
      };
      document.addEventListener('keydown', trapFocus);
      return () => {
        clearTimeout(timeout);
        document.removeEventListener('keydown', trapFocus);
        document.body.style.overflow = previousOverflow;
        background.forEach((child, index) => { if (!previousInert[index]) child.removeAttribute('inert'); });
        if (previousFocus?.isConnected) previousFocus.focus();
      };
    }
  }, [isOpen]);

  // Keep selected index within bounds
  useEffect(() => {
    setSelectedIndex(0);
  }, [query, employees.length]);

  const handleSelect = (target) => {
    if (!target) return;
    onClose();
    if (target.type === 'action') {
      navigate(target.item.path);
    } else if (target.type === 'employee') {
      const emp = target.item;
      if (effectiveRole === ROLES.EMPLOYEE) {
        navigate(`/myhr/${roleSegment}/document-requests`);
      } else {
        navigate(`/myhr/${roleSegment}/reports?search=${encodeURIComponent(emp.name || emp.employeeId)}`);
      }
    }
  };

  // Keyboard navigation
  const handleKeyDown = (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIndex((prev) => (prev + 1) % Math.max(allItems.length, 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((prev) => (prev - 1 + allItems.length) % Math.max(allItems.length, 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (allItems[selectedIndex]) {
        handleSelect(allItems[selectedIndex]);
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    }
  };

  if (!isOpen) return null;

  return createPortal(
    <div
      className="wp-command-palette-backdrop"
    >
      <button type="button" className="wp-command-palette__dismiss" tabIndex={-1} aria-label="Dismiss Quick Jump" onClick={onClose} />
      <div ref={panelRef} className="wp-command-palette" role="dialog" aria-modal="true" aria-label="Universal Quick Jump">
        <div className="wp-command-palette__search-row">
          <span className="wp-command-palette__search-icon" aria-hidden="true">
            <StrokeIcon name="search" />
          </span>
          <input
            ref={inputRef}
            type="text"
            className="wp-command-palette__input"
            placeholder={enableEmployeeSearch ? 'Type a command, employee name, or jump to...' : 'Find a page in this workspace...'}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            aria-label="Search Quick Jump"
          />
          {searching && <span className="wp-command-palette__spinner" aria-hidden="true" />}
          <button type="button" aria-label="Close Quick Jump" className="wp-command-palette__esc-badge" onClick={onClose}>
            ESC
          </button>
        </div>

        <div className="wp-command-palette__body" ref={listRef}>
          {allItems.length === 0 && !searching && (
            <div className="wp-command-palette__empty">
              <p>No results found for &ldquo;{query}&rdquo;</p>
              <small>{enableEmployeeSearch ? 'Try searching for an employee name, timesheet, or document' : 'Try the name of a page in this workspace'}</small>
            </div>
          )}

          {filteredActions.length > 0 && (
            <div className="wp-command-palette__section">
              <span className="wp-command-palette__section-title">Navigation & Quick Actions</span>
              <div className="wp-command-palette__list">
                {filteredActions.map((action, idx) => {
                  const isSelected = selectedIndex === idx;
                  return (
                    <button
                      key={action.id}
                      type="button"
                      className={`wp-command-palette__item ${isSelected ? 'is-selected' : ''}`}
                      onClick={() => handleSelect({ type: 'action', item: action })}
                      onMouseEnter={() => setSelectedIndex(idx)}
                    >
                      <span className="wp-command-palette__item-icon" aria-hidden="true">
                        <StrokeIcon name={action.id.includes('timesheet') || action.id.includes('payroll') ? 'time' : action.id.includes('doc') || action.id.includes('offer') ? 'document' : action.id.includes('employee') ? 'people' : action.id.includes('org') ? 'organization' : 'arrow-right'} />
                      </span>
                      <div className="wp-command-palette__item-info">
                        <div className="wp-command-palette__item-title-row">
                          <strong>{action.title}</strong>
                          <span className="wp-command-palette__item-category">{action.category}</span>
                        </div>
                        <small>{action.subtitle}</small>
                      </div>
                      <span className="wp-command-palette__item-arrow" aria-hidden="true">
                        ↵
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {employees.length > 0 && (
            <div className="wp-command-palette__section">
              <span className="wp-command-palette__section-title">Matching Employees</span>
              <div className="wp-command-palette__list">
                {employees.map((emp, empIdx) => {
                  const globalIdx = filteredActions.length + empIdx;
                  const isSelected = selectedIndex === globalIdx;
                  return (
                    <button
                      key={emp.userId || emp.employeeId}
                      type="button"
                      className={`wp-command-palette__item ${isSelected ? 'is-selected' : ''}`}
                      onClick={() => handleSelect({ type: 'employee', item: emp })}
                      onMouseEnter={() => setSelectedIndex(globalIdx)}
                    >
                      <span className="wp-command-palette__avatar" aria-hidden="true">
                        {(emp.name || emp.employeeId || 'E').charAt(0).toUpperCase()}
                      </span>
                      <div className="wp-command-palette__item-info">
                        <div className="wp-command-palette__item-title-row">
                          <strong>{emp.name || 'Unnamed Employee'}</strong>
                          {emp.employeeId && (
                            <span className="wp-command-palette__id-pill">{emp.employeeId}</span>
                          )}
                          {emp.role && (
                            <span className="wp-command-palette__role-pill">{emp.role}</span>
                          )}
                        </div>
                        <small>
                          {emp.designation || 'Staff'}
                          {emp.reportingManagerName ? ` • Manager: ${emp.reportingManagerName}` : ''}
                        </small>
                      </div>
                      <span className="wp-command-palette__item-arrow" aria-hidden="true">
                        ↵
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        <div className="wp-command-palette__footer">
          <div className="wp-command-palette__shortcuts">
            <span>
              <kbd>↑</kbd> <kbd>↓</kbd> to navigate
            </span>
            <span>
              <kbd>↵</kbd> to select
            </span>
            <span>
              <kbd>esc</kbd> to close
            </span>
          </div>
          <span className="wp-command-palette__branding">Waypoint Quick Jump</span>
        </div>
      </div>
    </div>, document.body
  );
}
