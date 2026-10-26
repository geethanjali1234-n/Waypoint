import { useEffect, useRef, useState } from 'react';
import { RangeSlider, Button } from '../../../components';
import './SalaryFilterDropdown.css';

/**
 * SalaryFilterDropdown - filter-bar trigger button that opens a popover
 * containing the salary RangeSlider plus Apply/Reset actions. Mirrors the
 * open/close and outside-click behavior of the header's ProfileMenu /
 * NotificationMenu popovers, styled to match the other filter-bar dropdowns.
 *
 * @param {Object} props
 * @param {string} [props.label='Salary'] - Trigger label shown when no range is applied.
 * @param {number} props.min - Absolute lower bound of the slider.
 * @param {number} props.max - Absolute upper bound of the slider.
 * @param {number} [props.step=1] - Step increment.
 * @param {[number, number]} props.value - Currently applied [min, max].
 * @param {(value: [number, number]) => void} props.onApply - Called with the new range when Apply is clicked.
 * @param {() => void} props.onReset - Called when Reset is clicked.
 * @param {(value: number) => string} [props.formatValue] - Formats each endpoint for display.
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <SalaryFilterDropdown
 *   min={10000} max={1000000} step={10000}
 *   value={appliedSalaryRange}
 *   onApply={setAppliedSalaryRange}
 *   onReset={() => setAppliedSalaryRange([10000, 1000000])}
 *   formatValue={(v) => `$${Math.round(v / 1000)}K`}
 * />
 */
export default function SalaryFilterDropdown({
  label = 'Salary',
  min,
  max,
  step = 1,
  value,
  onApply,
  onReset,
  formatValue = (v) => v,
  className = '',
}) {
  const [isOpen, setIsOpen] = useState(false);
  const [draftRange, setDraftRange] = useState(value);
  const containerRef = useRef(null);

  const handleToggle = () => {
    setIsOpen((open) => {
      if (!open) setDraftRange(value);
      return !open;
    });
  };

  useEffect(() => {
    if (!isOpen) return undefined;

    const handleOutsideClick = (event) => {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleOutsideClick);
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, [isOpen]);

  const isDefault = value[0] === min && value[1] === max;
  const triggerLabel = isDefault ? label : `${formatValue(value[0])} – ${formatValue(value[1])}`;

  const handleApply = () => {
    onApply(draftRange);
    setIsOpen(false);
  };

  const handleReset = () => {
    setDraftRange([min, max]);
    onReset();
    setIsOpen(false);
  };

  const classes = ['wp-salary-filter', className].filter(Boolean).join(' ');

  return (
    <div className={classes} ref={containerRef}>
      <button
        type="button"
        className="wp-salary-filter__trigger"
        onClick={handleToggle}
        aria-haspopup="dialog"
        aria-expanded={isOpen}
      >
        <span>{triggerLabel}</span>
        <svg
          className="wp-salary-filter__chevron"
          width="10"
          height="10"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {isOpen && (
        <div className="wp-salary-filter__panel" role="dialog" aria-label={label}>
          <RangeSlider
            label={label}
            min={min}
            max={max}
            step={step}
            value={draftRange}
            onChange={setDraftRange}
            formatValue={formatValue}
          />
          <div className="wp-salary-filter__actions">
            <button type="button" className="wp-salary-filter__reset" onClick={handleReset}>
              Reset
            </button>
            <Button variant="primary" className="wp-salary-filter__apply" onClick={handleApply}>
              Apply
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
