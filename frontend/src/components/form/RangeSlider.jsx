import FormLabel from './FormLabel';
import './RangeSlider.css';

/**
 * RangeSlider - dual-handle numeric range slider (min/max), the familiar
 * "modern job portal" salary-range control. Built from two overlaid native
 * range inputs so it needs no extra dependency.
 *
 * @param {Object} props
 * @param {string} [props.label] - Field label text.
 * @param {number} [props.min=0] - Absolute lower bound of the slider.
 * @param {number} [props.max=100] - Absolute upper bound of the slider.
 * @param {number} [props.step=1] - Step increment.
 * @param {[number, number]} [props.value] - Controlled [selectedMin, selectedMax]. Defaults to [min, max].
 * @param {(value: [number, number]) => void} [props.onChange] - Called with the new [min, max] whenever either handle moves.
 * @param {(value: number) => string} [props.formatValue] - Formats each endpoint for display (e.g. to "$10K").
 * @param {string} [props.className=''] - Additional class names on the wrapper.
 *
 * @example
 * <RangeSlider label="Salary range" min={10000} max={1000000} step={10000}
 *   value={[salaryMin, salaryMax]} onChange={([min, max]) => ...}
 *   formatValue={(v) => `$${Math.round(v / 1000)}K`} />
 */
export default function RangeSlider({
  label,
  min = 0,
  max = 100,
  step = 1,
  value,
  onChange,
  formatValue = (v) => v,
  className = '',
}) {
  const [selectedMin, selectedMax] = value ?? [min, max];
  const classes = ['wp-field', 'wp-range-slider', className].filter(Boolean).join(' ');

  const percentOf = (v) => ((v - min) / (max - min)) * 100;

  const handleMinChange = (event) => {
    const nextMin = Math.min(Number(event.target.value), selectedMax);
    onChange?.([nextMin, selectedMax]);
  };

  const handleMaxChange = (event) => {
    const nextMax = Math.max(Number(event.target.value), selectedMin);
    onChange?.([selectedMin, nextMax]);
  };

  return (
    <div className={classes}>
      {label && <FormLabel>{label}</FormLabel>}
      <div className="wp-range-slider__values">
        <span>{formatValue(selectedMin)}</span>
        <span>{formatValue(selectedMax)}</span>
      </div>
      <div className="wp-range-slider__track-wrap">
        <div className="wp-range-slider__track" />
        <div
          className="wp-range-slider__track-fill"
          style={{ left: `${percentOf(selectedMin)}%`, right: `${100 - percentOf(selectedMax)}%` }}
        />
        <input
          type="range"
          className="wp-range-slider__input"
          min={min}
          max={max}
          step={step}
          value={selectedMin}
          onChange={handleMinChange}
          aria-label={label ? `${label} minimum` : 'Minimum'}
        />
        <input
          type="range"
          className="wp-range-slider__input"
          min={min}
          max={max}
          step={step}
          value={selectedMax}
          onChange={handleMaxChange}
          aria-label={label ? `${label} maximum` : 'Maximum'}
        />
      </div>
    </div>
  );
}
